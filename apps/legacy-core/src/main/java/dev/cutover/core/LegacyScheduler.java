package dev.cutover.core;

import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.ServiceHttp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** This original coordinator polls trigger-created legacy task rows and retains inventory ownership. */
public final class LegacyScheduler {
    private final DSLContext database;private final DispatchPort adapter;private final OrderService orders;private final Clock clock;
    public LegacyScheduler(DSLContext database,DispatchPort adapter,OrderService orders,Clock clock){this.database=database;this.adapter=adapter;this.orders=orders;this.clock=clock;}
    public int poll(){
        if(database.fetchOne("SELECT workers_paused FROM service_control WHERE singleton").get(0,Boolean.class))return 0;
        var tasks=database.transactionResult(configuration->{
            var sql=DSL.using(configuration);
            var rows=sql.fetch("SELECT t.*,m.movement FROM legacy_tasks t JOIN movement_intents m ON m.movement_id=t.movement_id JOIN orders o ON o.order_id=t.order_id WHERE t.state NOT IN ('COMPLETED','CANCELLED') AND NOT o.cancellation_pending AND t.next_attempt_at <= ?::timestamptz AND (t.lease_until IS NULL OR t.lease_until < ?::timestamptz) ORDER BY t.priority DESC,t.eligible_at,t.movement_id LIMIT 16 FOR UPDATE OF t SKIP LOCKED",now(),now());
            for(var row:rows)sql.execute("UPDATE legacy_tasks SET lease_until=?::timestamptz WHERE task_id=?",now().plusSeconds(20),row.get("task_id"));
            return rows;
        });
        for(var task:tasks)process(task);
        return tasks.size();
    }
    private void process(Record task){
        UUID movement=task.get("movement_id",UUID.class);String site=task.get("site_id",String.class);
        try{
            JsonNode payload=JsonSupport.read(task.get("movement").toString());
            UUID allocation=task.get("allocation_id",UUID.class);Long epoch=task.get("epoch",Long.class);
            if(allocation==null){
                var assigned=adapter.allocate(site,payload);
                if(!"ASSIGNED".equals(assigned.path("state").asString())){update(movement,"BLOCKED","ZONE_DRAINING");return;}
                if(!"legacy-core".equals(assigned.path("owner").asString())){update(movement,"RECONCILIATION_REQUIRED","MOVEMENT_OWNED_ELSEWHERE");return;}
                allocation=Database.uuid(assigned,"allocationId");epoch=assigned.path("epoch").asLong();
                database.execute("UPDATE legacy_tasks SET allocation_id=?,epoch=? WHERE movement_id=?",allocation,epoch,movement);
                database.execute("UPDATE movement_intents SET state='ASSIGNED' WHERE movement_id=? AND state='REQUESTED'",movement);
            }
            JsonNode command=adapter.command(site,movement);
            if(command==null){
                var observed=adapter.equipment(site);
                if(observed.path("stale").asBoolean(true)||observed.path("worldMismatch").asBoolean()){update(movement,"BLOCKED","EQUIPMENT_EVIDENCE_STALE");return;}
                String lane=null;
                for(JsonNode candidate:observed.path("lanes"))if(site.equals(candidate.path("siteId").asString())&&payload.path("zoneId").equals(candidate.path("zoneId"))&&!candidate.path("blocked").asBoolean()){lane=candidate.path("laneId").asString();break;}
                if(lane==null){update(movement,"BLOCKED","LANE_BLOCKED");return;}
                command=adapter.dispatch(site,movement,allocation,epoch,lane,payload);
            }
            String state=command.path("state").asString();
            if(state.equals("COMPLETED")){orders.complete(site,movement,command);return;}
            update(movement,switch(state){case "ACCEPTED_BY_SIMULATOR","EXECUTING"->"IN_PROGRESS";case "OUTCOME_UNKNOWN","QUARANTINED","REJECTED_BEFORE_EXECUTION"->"RECONCILIATION_REQUIRED";default->"DISPATCH_REQUESTED";},command.path("lastError").isNull()?null:command.path("lastError").asString());
        }catch(Problem conflict){update(movement,conflict.code().equals("LANE_BLOCKED")?"BLOCKED":"RECONCILIATION_REQUIRED",conflict.code());}
        catch(ServiceHttp.Unavailable unavailable){update(movement,"RECONCILIATION_REQUIRED","ADAPTER_UNAVAILABLE");}
    }
    private void update(UUID movement,String state,String error){
        database.execute("UPDATE legacy_tasks SET state=?,last_error=?,version=version+1,next_attempt_at=?::timestamptz,lease_until=NULL WHERE movement_id=? AND state NOT IN ('COMPLETED','CANCELLED')",state,error,now().plusNanos(300_000_000),movement);
    }
    public JsonNode tasks(String site){
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('id',task_id,'movementId',movement_id,'orderId',order_id,'zoneId',zone_id,'state',state,'owner',owner,'epoch',epoch,'version',version,'lastError',last_error,'eligibleAt',eligible_at) ORDER BY priority DESC,eligible_at,movement_id),'[]'::jsonb) FROM (SELECT * FROM legacy_tasks WHERE site_id=? ORDER BY priority DESC,eligible_at,movement_id LIMIT 100) t",site);
    }
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
