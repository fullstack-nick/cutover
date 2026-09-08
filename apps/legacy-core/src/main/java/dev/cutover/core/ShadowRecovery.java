package dev.cutover.core;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Idempotency;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Reopens only an exhausted observation's original transport record; never grants dispatch. */
public final class ShadowRecovery {
    private final DSLContext database;
    private final Clock clock;
    public ShadowRecovery(DSLContext database,Clock clock){this.database=database;this.clock=clock;}

    public JsonNode status(String site){
        return Database.json(database,"""
            SELECT jsonb_build_object('observedAt',now(),
              'pending',(SELECT count(*) FROM shadow_observation_outbox o JOIN legacy_decision_rounds r ON r.round_id=o.event_id WHERE r.site_id=? AND o.published_at IS NULL),
              'paused',(SELECT count(*) FROM shadow_observation_outbox o JOIN legacy_decision_rounds r ON r.round_id=o.event_id WHERE r.site_id=? AND o.published_at IS NULL AND o.paused),
              'items',(SELECT coalesce(jsonb_agg(jsonb_build_object('eventId',event_id,'inputHash',input_hash,'version',recovery_version,'attempts',attempts,'paused',paused,'lastError',last_error,'createdAt',created_at) ORDER BY created_at,event_id),'[]'::jsonb)
                FROM (SELECT o.*,r.input_hash,r.created_at FROM shadow_observation_outbox o JOIN legacy_decision_rounds r ON r.round_id=o.event_id WHERE r.site_id=? AND o.published_at IS NULL ORDER BY r.created_at,o.event_id LIMIT 100) pending))
            """,site,site,site);
    }

    public JsonNode replay(String actor,String site,UUID id,String key,JsonNode request){
        try{Contracts.validate("reconciliation-request.v1",JsonSupport.write(request));}
        catch(IllegalArgumentException invalid){throw Problem.invalid("Provide an expected version and a reason of 8–500 characters.");}
        String reason=request.path("reason").asString().trim();
        if(reason.length()<8)throw Problem.invalid("The recovery reason must explain the correction.");
        return database.transactionResult(configuration->{
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"recover-shadow-observation",key,Map.of("id",id,"request",request),()->{
                if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Observation recovery is paused for the checkpoint.");
                // Same capacity-before-outbox order as successful relay settlement and retention.
                sql.fetchOne("SELECT singleton FROM shadow_observation_capacity WHERE singleton FOR UPDATE");
                var row=sql.fetchOne("SELECT o.* FROM shadow_observation_outbox o JOIN legacy_decision_rounds r ON r.round_id=o.event_id WHERE o.event_id=? AND r.site_id=? FOR UPDATE OF o",id,site);
                if(row==null)throw Problem.missing();
                long before=row.get("recovery_version",Long.class);
                if(before!=request.path("expectedVersion").asLong())throw Problem.conflict("VERSION_CONFLICT","The observation recovery revision changed; inspect its latest evidence.");
                if(row.get("published_at")!=null || !row.get("paused",Boolean.class))throw Problem.conflict("DELIVERY_STATE","Only an exhausted unpublished observation can be resumed.");
                var now=OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
                var leaseUntil=row.get("lease_until",OffsetDateTime.class);
                if(leaseUntil!=null && leaseUntil.isAfter(now))throw Problem.conflict("DELIVERY_IN_FLIGHT","The current observation publish lease must settle before replay.");
                sql.execute("UPDATE shadow_observation_outbox SET paused=false,attempts=0,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL,last_error=NULL,recovery_version=recovery_version+1 WHERE event_id=?",now,id);
                var response=JsonSupport.MAPPER.valueToTree(Map.of("eventId",id,"state","RETRY_RECORDED","version",before+1));
                sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,?,?,?,?,?,'RECORDED',?::jsonb)",UUID.randomUUID(),site,actor,"recover-shadow-observation",id.toString(),reason,before,before+1,JsonSupport.write(response));
                return response;
            });
        });
    }
}
