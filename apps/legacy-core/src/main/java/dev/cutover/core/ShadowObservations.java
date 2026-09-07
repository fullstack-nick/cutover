package dev.cutover.core;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.DeliveryFailure;
import dev.cutover.platform.messaging.OutboxRelay;
import dev.cutover.platform.messaging.RetryDelay;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Optional observations have their own quota, transactions and relay; they never join business outbox admission. */
public final class ShadowObservations {
    public record Round(UUID id,JsonNode proposal,boolean retained) {}
    private final DSLContext database;
    private final Clock clock;
    private final OutboxRelay.Publisher publisher;
    private java.time.Instant nextPublish=java.time.Instant.MIN;
    private int failures;
    public ShadowObservations(DSLContext database,Clock clock,OutboxRelay.Publisher publisher){this.database=database;this.clock=clock;this.publisher=publisher;}
    public Round capture(JsonNode snapshot,String origin){
        JsonNode proposal=LegacyDecision.propose(database,snapshot);
        UUID id=UUID.randomUUID();String site=snapshot.path("siteId").asString();
        var payload=JsonSupport.MAPPER.createObjectNode().put("roundId",id.toString()).put("siteId",site)
                .put("inputHash",JsonSupport.hash(snapshot)).put("origin",origin);
        payload.set("input",snapshot);payload.set("legacyProposal",proposal);
        Contracts.validate("scheduling-observation.v1",JsonSupport.write(payload));
        var event=new Events.Envelope(id,"SchedulingSnapshotRecorded.v1",1,clock.instant(),site,"legacy-core","scheduling-round",id,1,id,null,null,payload);
        String envelope=JsonSupport.write(event);Contracts.validate("event-envelope.v1",envelope);
        int bytes=envelope.getBytes(StandardCharsets.UTF_8).length;
        boolean retained=database.transactionResult(configuration->{
            var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return false;
            var capacity=sql.fetchOne("SELECT * FROM shadow_observation_capacity WHERE singleton FOR UPDATE");
            if(bytes>65536 || capacity.get("pending_count",Integer.class)>=1000 || capacity.get("pending_bytes",Long.class)+bytes>8388608
                    || capacity.get("retained_count",Integer.class)>=4096 || capacity.get("retained_bytes",Long.class)+bytes>67108864){
                sql.execute("UPDATE shadow_observation_capacity SET omitted_rounds=omitted_rounds+1 WHERE singleton");return false;
            }
            sql.execute("INSERT INTO legacy_decision_rounds(round_id,site_id,zone_id,input_hash,input,proposal,rule_version,decision_at) VALUES (?,?,?,?,?::jsonb,?::jsonb,1,?::timestamptz)",id,site,snapshot.path("zoneId").asString(),payload.path("inputHash").asString(),JsonSupport.write(snapshot),JsonSupport.write(proposal),snapshot.path("decisionAt").asString());
            sql.execute("INSERT INTO shadow_observation_outbox(event_id,envelope,payload_bytes,next_attempt_at) VALUES (?,?::jsonb,?,?::timestamptz)",id,envelope,bytes,now());
            sql.execute("UPDATE shadow_observation_capacity SET pending_count=pending_count+1,pending_bytes=pending_bytes+?,retained_count=retained_count+1,retained_bytes=retained_bytes+? WHERE singleton",bytes,bytes);
            return true;
        });
        return new Round(id,proposal,retained);
    }
    public int relay(){
        if(publisher==null || clock.instant().isBefore(nextPublish))return 0;
        int count=0;
        while(count<16){
            var claimed=database.transactionResult(configuration->{
                var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return null;
                if(sql.fetchOne("SELECT relay_paused FROM service_control WHERE singleton").get(0,Boolean.class))return null;
                var row=sql.fetchOne("SELECT * FROM shadow_observation_outbox WHERE published_at IS NULL AND NOT paused AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",now(),now());
                if(row==null)return null;
                UUID lease=UUID.randomUUID(),id=row.get("event_id",UUID.class);int attempt=row.get("attempts",Integer.class)+1;
                sql.execute("UPDATE shadow_observation_outbox SET lease_id=?,lease_until=?::timestamptz,attempts=? WHERE event_id=?",lease,now().plusSeconds(20),attempt,id);
                return new OutboxRelay.Claimed(id,lease,"cutover.observation.v1","SchedulingSnapshotRecorded.v1",row.get("envelope").toString(),attempt);
            });
            if(claimed==null)break;
            try{
                publisher.publish(claimed.exchange(),claimed.type(),claimed.eventId(),claimed.body());
                database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
                    sql.fetchOne("SELECT * FROM shadow_observation_capacity WHERE singleton FOR UPDATE");
                    var changed=sql.fetchOne("UPDATE shadow_observation_outbox SET published_at=?::timestamptz,lease_id=NULL,lease_until=NULL,last_error=NULL WHERE event_id=? AND lease_id=? AND published_at IS NULL RETURNING payload_bytes",now(),claimed.eventId(),claimed.leaseId());
                    if(changed!=null)sql.execute("UPDATE shadow_observation_capacity SET pending_count=pending_count-1,pending_bytes=pending_bytes-? WHERE singleton",changed.get(0,Integer.class));
                });count++;failures=0;
            }catch(RuntimeException failure){
                String code=failure instanceof DeliveryFailure?failure.getMessage():"OBSERVATION_BROKER_UNAVAILABLE";
                database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
                    sql.execute("UPDATE shadow_observation_outbox SET lease_id=NULL,lease_until=NULL,last_error=?,next_attempt_at=?::timestamptz,paused=? WHERE event_id=? AND lease_id=? AND published_at IS NULL",code,now().plus(RetryDelay.after(claimed.eventId(),claimed.attempt())),claimed.attempt()>=RetryDelay.MAX_ATTEMPTS,claimed.eventId(),claimed.leaseId());
                });
                failures=Math.min(failures+1,RetryDelay.MAX_ATTEMPTS);nextPublish=clock.instant().plus(RetryDelay.after(claimed.eventId(),failures));break;
            }
        }
        return count;
    }
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
