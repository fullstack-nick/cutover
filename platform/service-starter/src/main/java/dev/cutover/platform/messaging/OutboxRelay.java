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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class OutboxRelay {
    @FunctionalInterface public interface Publisher {
        void publish(String exchange, String routingKey, UUID id, String body);
        default CompletableFuture<Void> publishAsync(String exchange,String routingKey,UUID id,String body) {
            publish(exchange,routingKey,id,body);return CompletableFuture.completedFuture(null);
        }
        default void inBatch(Runnable work) { work.run(); }
    }
    public record Claimed(UUID eventId, UUID leaseId, String exchange, String type, String body, int attempt) {}
    private record Batch(List<Claimed> rows, OffsetDateTime leaseUntil) {}
    private record Failed(Claimed row,String code) {}
    private record Pending(Claimed row,Events.Envelope event,CompletableFuture<Void> confirmation) {}
    private static final class Publication {
        final List<Claimed> confirmed=new ArrayList<>();
        final List<Failed> failed=new ArrayList<>();
        int attempted;
        boolean expiring;
    }
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
            var publication=new Publication();
            try { publisher.inBatch(()->publish(batch,publication)); }
            catch(RuntimeException unavailable) {
                // Opening the scoped channel may fail before any send. Charge one attempt and release the untouched originals.
                if(publication.attempted!=0)throw unavailable;
                publication.attempted=1;publication.failed.add(new Failed(batch.rows().getFirst(),failureCode(unavailable)));
            }
            // A real crash/Error deliberately skips settlement; every leased original event remains replayable.
            int marked=settle(publication.confirmed,publication.failed,batch.rows().subList(publication.attempted,batch.rows().size()));count+=marked;
            if(!publication.failed.isEmpty()){
                consecutivePublishFailures=Math.min(consecutivePublishFailures+1,RetryDelay.MAX_ATTEMPTS);
                nextPublishAttempt=clock.instant().plus(RetryDelay.after(publication.failed.getFirst().row().eventId(),consecutivePublishFailures));break;
            }
            consecutivePublishFailures=0;
            if(publication.expiring || marked==0)break;
        }
        return count;
    }

    public void confirmed(Claimed row) {
        settle(List.of(row),List.of(),List.of());
    }

    private void publish(Batch batch,Publication publication) {
        var pending=new ArrayList<Pending>();
        for(Claimed row:batch.rows()) {
            if(!clock.instant().plusSeconds(10).isBefore(batch.leaseUntil().toInstant())){publication.expiring=true;break;}
            hooks.reached("AFTER_BUSINESS_COMMIT",row.eventId());publication.attempted++;
            var event=JsonSupport.MAPPER.readValue(row.body(),Events.Envelope.class);
            try(var trace=OperationTrace.event("cutover.event.publish",event)) {
                try {pending.add(new Pending(row,event,publisher.publishAsync(row.exchange(),row.type(),row.eventId(),row.body())));}
                catch(RuntimeException unavailable){trace.failed(unavailable);publication.failed.add(new Failed(row,failureCode(unavailable)));break;}
            }
        }
        // A common deadline bounds the entire confirmation wait, not four seconds multiplied by the batch size.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        for(Pending sent:pending) {
            try(var trace=OperationTrace.event("cutover.event.confirm",sent.event())) {
                try {
                    sent.confirmation().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                    hooks.reached("AFTER_BROKER_CONFIRM",sent.row().eventId());publication.confirmed.add(sent.row());
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();trace.failed(interrupted);publication.failed.add(new Failed(sent.row(),"CONFIRM_INTERRUPTED"));
                } catch(Exception unavailable) {
                    trace.failed(unavailable);publication.failed.add(new Failed(sent.row(),failureCode(unavailable)));
                }
            }
        }
    }
    private static String failureCode(Throwable failure) {
        while((failure instanceof java.util.concurrent.ExecutionException || failure instanceof java.util.concurrent.CompletionException) && failure.getCause()!=null)failure=failure.getCause();
        return failure instanceof DeliveryFailure?failure.getMessage():"CONFIRM_UNAVAILABLE";
    }

    private static String leaseMatches(List<Claimed> rows,List<Object> bindings){
        var values=new StringJoiner(",");
        for(var row:rows){values.add("(?::uuid,?::uuid)");bindings.add(row.eventId());bindings.add(row.leaseId());}
        return "(event_id,lease_id) IN (VALUES "+values+")";
    }

    /** Only positively confirmed originals are marked; all budget changes share their settlement transaction. */
    private int settle(List<Claimed> confirmed,List<Failed> failed,List<Claimed> unattempted) {
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
            for(var failure:failed){var row=failure.row();sql.execute("UPDATE outbox SET lease_id=NULL,lease_until=NULL,last_error=?,next_attempt_at=?::timestamptz,paused=?,version=version+1 WHERE event_id=? AND lease_id=? AND published_at IS NULL",
                failure.code(),now().plus(RetryDelay.after(row.eventId(),row.attempt())),row.attempt()>=RetryDelay.MAX_ATTEMPTS,row.eventId(),row.leaseId());}
            if(!unattempted.isEmpty()){
                var bindings=new ArrayList<Object>();String match=leaseMatches(unattempted,bindings);
                sql.execute("UPDATE outbox SET lease_id=NULL,lease_until=NULL,attempts=attempts-1,version=version+1 WHERE published_at IS NULL AND "+match,bindings.toArray());
            }
            return count;
        });
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
