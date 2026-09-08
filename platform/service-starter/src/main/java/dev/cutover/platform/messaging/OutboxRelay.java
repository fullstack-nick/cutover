package dev.cutover.platform.messaging;

import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.OperationTrace;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class OutboxRelay {
    @FunctionalInterface public interface Publisher { void publish(String exchange, String routingKey, UUID id, String body); }
    public record Claimed(UUID eventId, UUID leaseId, String exchange, String type, String body, int attempt) {}
    private record Batch(List<Claimed> rows, OffsetDateTime leaseUntil) {}
    private final DSLContext database;
    private final Publisher publisher;
    private final Clock clock;
    private final DeliveryHooks hooks;
    private java.time.Instant nextPublishAttempt=java.time.Instant.MIN;
    private int consecutivePublishFailures;

    public OutboxRelay(DSLContext database, Publisher publisher, Clock clock, DeliveryHooks hooks) {
        this.database = database; this.publisher = publisher; this.clock = clock; this.hooks = hooks;
    }

    /** At most one leased event per stream; an earlier paused event also blocks overtaking. */
    public Claimed claim() {
        var batch=claimBatch(1);
        return batch.rows().isEmpty()?null:batch.rows().getFirst();
    }

    private Batch claimBatch(int limit) {
        // An empty poll must not lock a tuple and force a WAL flush. This read is
        // only a fast path; the transaction below still guards every real write.
        if (!database.fetchOne("SELECT EXISTS(SELECT 1 FROM outbox WHERE published_at IS NULL AND NOT paused AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz))", now(), now()).get(0, Boolean.class)) return new Batch(List.of(),now());
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return new Batch(List.of(),now());
            if (sql.fetchOne("SELECT relay_paused FROM service_control WHERE singleton").get(0,Boolean.class)) return new Batch(List.of(),now());
            var rows = sql.fetch("""
                    SELECT o.* FROM outbox o
                    WHERE o.published_at IS NULL AND NOT o.paused AND o.next_attempt_at <= ?::timestamptz
                      AND (o.lease_until IS NULL OR o.lease_until < ?::timestamptz)
                      AND NOT EXISTS (SELECT 1 FROM outbox p WHERE p.source=o.source AND p.site_id=o.site_id
                        AND p.aggregate_type=o.aggregate_type AND p.aggregate_id=o.aggregate_id
                        AND p.aggregate_version<o.aggregate_version AND p.published_at IS NULL)
                    ORDER BY o.created_at,o.event_id LIMIT ? FOR UPDATE OF o SKIP LOCKED
                    """, now(), now(),limit);
            OffsetDateTime until=now().plusSeconds(20);
            if(rows.isEmpty())return new Batch(List.of(),until);
            var claimed=new ArrayList<Claimed>();var values=new StringJoiner(",");var bindings=new ArrayList<Object>();bindings.add(until);
            for(var row:rows){
                UUID lease=UUID.randomUUID(),id=row.get("event_id",UUID.class);int attempt=row.get("attempts",Integer.class)+1;
                values.add("(?::uuid,?::uuid,?::integer)");bindings.add(id);bindings.add(lease);bindings.add(attempt);
                claimed.add(new Claimed(id,lease,"cutover."+row.get("source",String.class)+".v1",row.get("event_type",String.class),row.get("envelope").toString(),attempt));
            }
            int changed=sql.execute("UPDATE outbox o SET lease_until=?::timestamptz,lease_id=c.lease_id,attempts=c.attempt,version=o.version+1 FROM (VALUES "+values+") c(event_id,lease_id,attempt) WHERE o.event_id=c.event_id",bindings.toArray());
            if(changed!=claimed.size())throw new IllegalStateException("The locked relay claim changed unexpectedly.");
            return new Batch(List.copyOf(claimed),until);
        });
    }

    public int poll(int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Relay batch must be 1..32");
        if (clock.instant().isBefore(nextPublishAttempt)) return 0;
        int count = 0;
        while (count < limit) {
            Batch batch=claimBatch(limit-count);if(batch.rows().isEmpty())break;
            var confirmed=new ArrayList<Claimed>();Claimed failed=null;String error=null;int attempted=0;boolean expiring=false;
            for(Claimed row:batch.rows()) {
                // Bound the transport burst as well as the count. An expired/replaced lease can never mark delivery.
                if(!clock.instant().plusSeconds(10).isBefore(batch.leaseUntil().toInstant())){expiring=true;break;}
                hooks.reached("AFTER_BUSINESS_COMMIT",row.eventId());attempted++;
                try(var trace=OperationTrace.event("cutover.event.publish",JsonSupport.MAPPER.readValue(row.body(),Events.Envelope.class))){
                    publisher.publish(row.exchange(),row.type(),row.eventId(),row.body());
                    hooks.reached("AFTER_BROKER_CONFIRM",row.eventId());confirmed.add(row);consecutivePublishFailures=0;
                }catch(RuntimeException unavailable){
                    failed=row;error=unavailable instanceof DeliveryFailure?unavailable.getMessage():"BROKER_UNAVAILABLE";break;
                }
            }
            // A real crash/Error deliberately skips settlement; every leased original event remains replayable.
            int marked=settle(confirmed,failed,error,batch.rows().subList(attempted,batch.rows().size()));count+=marked;
            if(failed!=null){
                consecutivePublishFailures=Math.min(consecutivePublishFailures+1,RetryDelay.MAX_ATTEMPTS);
                nextPublishAttempt=clock.instant().plus(RetryDelay.after(failed.eventId(),consecutivePublishFailures));break;
            }
            if(expiring || marked==0)break;
        }
        return count;
    }

    public void confirmed(Claimed row) {
        settle(List.of(row),null,null,List.of());
    }

    private static String leaseMatches(List<Claimed> rows,List<Object> bindings){
        var values=new StringJoiner(",");
        for(var row:rows){values.add("(?::uuid,?::uuid)");bindings.add(row.eventId());bindings.add(row.leaseId());}
        return "(event_id,lease_id) IN (VALUES "+values+")";
    }

    /** Only positively confirmed originals are marked; all budget changes share their settlement transaction. */
    private int settle(List<Claimed> confirmed,Claimed failed,String code,List<Claimed> unattempted) {
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return 0;
            sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
            int count=0;
            if(!confirmed.isEmpty()){
                var bindings=new ArrayList<Object>();bindings.add(now());String match=leaseMatches(confirmed,bindings);
                var changed=sql.fetch("UPDATE outbox SET published_at=?::timestamptz,lease_id=NULL,lease_until=NULL,last_error=NULL,version=version+1 WHERE published_at IS NULL AND "+match+" RETURNING payload_bytes",bindings.toArray());
                count=changed.size();long bytes=changed.stream().mapToLong(row->row.get(0,Integer.class)).sum();
                if(count>0)sql.execute("UPDATE admission SET unpublished_events=unpublished_events-?,unpublished_bytes=unpublished_bytes-? WHERE singleton",count,bytes);
            }
            if(failed!=null)sql.execute("UPDATE outbox SET lease_id=NULL,lease_until=NULL,last_error=?,next_attempt_at=?::timestamptz,paused=?,version=version+1 WHERE event_id=? AND lease_id=? AND published_at IS NULL",
                code,now().plus(RetryDelay.after(failed.eventId(),failed.attempt())),failed.attempt()>=RetryDelay.MAX_ATTEMPTS,failed.eventId(),failed.leaseId());
            if(!unattempted.isEmpty()){
                var bindings=new ArrayList<Object>();String match=leaseMatches(unattempted,bindings);
                sql.execute("UPDATE outbox SET lease_id=NULL,lease_until=NULL,attempts=attempts-1,version=version+1 WHERE published_at IS NULL AND "+match,bindings.toArray());
            }
            return count;
        });
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
