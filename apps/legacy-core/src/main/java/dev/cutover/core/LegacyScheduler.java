package dev.cutover.core;

import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.ServiceHttp;
import dev.cutover.platform.OperationTrace;
import dev.cutover.platform.messaging.RetryDelay;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The retained legacy owner dispatches the actual SQL proposal from a persisted input snapshot. */
public final class LegacyScheduler {
    private final DSLContext database;private final DispatchPort adapter;private final OrderService orders;private final Clock clock;
    private final ShadowObservations observations;
    public LegacyScheduler(DSLContext database,DispatchPort adapter,OrderService orders,Clock clock){this(database,adapter,orders,clock,new ShadowObservations(database,clock,null));}
    public LegacyScheduler(DSLContext database,DispatchPort adapter,OrderService orders,Clock clock,ShadowObservations observations){this.database=database;this.adapter=adapter;this.orders=orders;this.clock=clock;this.observations=observations;}
    public int poll(){
        if(database.fetchOne("SELECT workers_paused FROM service_control WHERE singleton").get(0,Boolean.class))return 0;
        if(!database.fetchOne("SELECT EXISTS(SELECT 1 FROM legacy_tasks t JOIN orders o ON o.order_id=t.order_id WHERE t.state NOT IN ('COMPLETED','CANCELLED') AND NOT t.transport_paused AND NOT o.cancellation_pending AND t.next_attempt_at<=?::timestamptz AND (t.lease_until IS NULL OR t.lease_until<?::timestamptz))",now(),now()).get(0,Boolean.class))return 0;
        var tasks=database.transactionResult(configuration->{
            var sql=DSL.using(configuration);
            if(!Database.workersMayWrite(sql))return sql.fetch("SELECT t.*,m.movement FROM legacy_tasks t JOIN movement_intents m ON m.movement_id=t.movement_id WHERE false");
            var rows=sql.fetch("SELECT t.*,m.movement FROM legacy_tasks t JOIN movement_intents m ON m.movement_id=t.movement_id JOIN orders o ON o.order_id=t.order_id WHERE t.state NOT IN ('COMPLETED','CANCELLED') AND NOT t.transport_paused AND NOT o.cancellation_pending AND t.next_attempt_at<=?::timestamptz AND (t.lease_until IS NULL OR t.lease_until<?::timestamptz) ORDER BY t.priority DESC,t.eligible_at,t.movement_id LIMIT 16 FOR UPDATE OF t SKIP LOCKED",now(),now());
            for(var row:rows){UUID lease=UUID.randomUUID();row.set(DSL.field("lease_id",UUID.class),lease);sql.execute("UPDATE legacy_tasks SET lease_id=?,lease_until=?::timestamptz WHERE task_id=?",lease,now().plusSeconds(60),row.get("task_id"));}
            return rows;
        });
        var groups=new LinkedHashMap<String,List<Record>>();
        for(var task:tasks){
            try{
                if(ensureAllocation(task))groups.computeIfAbsent(task.get("site_id",String.class)+"/"+task.get("zone_id",String.class),ignored->new ArrayList<>()).add(task);
            }catch(Problem problem){defer(task,problem.code(),problem.status()==503);}
            catch(ServiceHttp.Unavailable unavailable){defer(task,"ADAPTER_UNAVAILABLE",true);}
        }
        for(var group:groups.values()){
            if(database.fetchOne("SELECT workers_paused FROM service_control WHERE singleton").get(0,Boolean.class))break;
            try{decide(group);}catch(Problem problem){for(var task:group)defer(task,problem.code(),problem.status()==503);}
            catch(ServiceHttp.Unavailable unavailable){for(var task:group)defer(task,"ADAPTER_UNAVAILABLE",true);}
        }
        return tasks.size();
    }
    private boolean ensureAllocation(Record task){
        if(task.get("allocation_id")!=null)return true;
        ensureDispatchWritable();String site=task.get("site_id",String.class);UUID movement=task.get("movement_id",UUID.class);
        var assigned=adapter.allocate(site,JsonSupport.read(task.get("movement").toString()));
        if(!"ASSIGNED".equals(assigned.path("state").asString())){update(task,"BLOCKED","ZONE_DRAINING",false);return false;}
        if(!"legacy-core".equals(assigned.path("owner").asString())){update(task,"RECONCILIATION_REQUIRED","MOVEMENT_OWNED_ELSEWHERE",false);return false;}
        UUID allocation=Database.uuid(assigned,"allocationId");long epoch=assigned.path("epoch").asLong();
        boolean stored=database.transactionResult(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return false;
            int changed=sql.execute("UPDATE legacy_tasks SET allocation_id=?,epoch=? WHERE movement_id=? AND lease_id=? AND state NOT IN ('COMPLETED','CANCELLED')",allocation,epoch,movement,task.get("lease_id"));
            if(changed==1)sql.execute("UPDATE movement_intents SET state='ASSIGNED' WHERE movement_id=? AND state='REQUESTED'",movement);
            return changed==1;
        });
        if(stored){task.set(DSL.field("allocation_id",UUID.class),allocation);task.set(DSL.field("epoch",Long.class),epoch);}
        return stored;
    }
    private void decide(List<Record> tasks){
        String site=tasks.getFirst().get("site_id",String.class),zone=tasks.getFirst().get("zone_id",String.class);
        var context=adapter.context(site,zone,tasks.stream().map(task->task.get("movement_id",UUID.class)).toList());
        var evidence=new HashMap<UUID,JsonNode>();context.path("movements").forEach(item->evidence.put(Database.uuid(item,"movementId"),item));
        ObjectNode snapshot=snapshot(site,zone,tasks,context,evidence);
        var unresolved=new HashMap<UUID,Record>();for(var task:tasks)unresolved.put(task.get("movement_id",UUID.class),task);
        // Known commands are observed separately. An unknown outcome never becomes a new scheduling candidate.
        for(var task:tasks){var item=evidence.get(task.get("movement_id",UUID.class));if(item!=null && !item.path("command").isNull() && !item.path("command").isMissingNode()){observe(task,item.path("command"));unresolved.remove(task.get("movement_id",UUID.class));}}
        for(int round=0;round<=tasks.size();round++){
            snapshot.put("decisionAt",timestamp(clock.instant()));
            var decision=observations.capture(snapshot,"LIVE").proposal();
            var selected=decision.path("selectedMovementId");
            if(selected.isNull()){
                for(var ranked:decision.path("ranking")){
                    var task=unresolved.remove(Database.uuid(ranked,"movementId"));if(task==null)continue;
                    String reason=ranked.path("reason").asString();
                    update(task,Set.of("WORLD_MISMATCH","OWNER_MISMATCH","ROUTE_UNCERTAIN").contains(reason)?"RECONCILIATION_REQUIRED":"BLOCKED",reason,false);
                }
                return;
            }
            UUID id=UUID.fromString(selected.asString());var task=unresolved.remove(id);
            if(task==null)throw new IllegalStateException("SQL selected a command that was already observed");
            try(var trace=OperationTrace.movement(database,"legacy-core",site,id,"cutover.task.dispatch")){
                trace.field("cutover.task_id",task.get("task_id").toString()).field("cutover.movement_id",id.toString());
                ensureDispatchWritable();
                var command=adapter.dispatch(site,id,task.get("allocation_id",UUID.class),task.get("epoch",Long.class),decision.path("selectedLaneId").asString(),JsonSupport.read(task.get("movement").toString()));
                observe(task,command);
            }catch(Problem problem){defer(task,problem.code(),problem.status()==503);}
            catch(ServiceHttp.Unavailable unavailable){defer(task,"ADAPTER_UNAVAILABLE",true);}
            var remaining=JsonSupport.MAPPER.createArrayNode();for(var candidate:snapshot.path("candidates"))if(!id.toString().equals(candidate.path("movementId").asString()))remaining.add(candidate);snapshot.set("candidates",remaining);
            if(remaining.isEmpty())return;
        }
    }
    private ObjectNode snapshot(String site,String zone,List<Record> tasks,JsonNode context,Map<UUID,JsonNode> evidence){
        var equipment=context.path("equipment");
        var result=JsonSupport.MAPPER.createObjectNode().put("ruleVersion",1).put("siteId",site).put("zoneId",zone)
                .put("decisionAt",timestamp(clock.instant())).put("observedAt",timestamp(Instant.parse(equipment.path("observedAt").asString("1970-01-01T00:00:00Z"))))
                .put("worldMismatch",equipment.path("worldMismatch").asBoolean(false));
        result.set("route",context.required("route"));var lanes=result.putArray("lanes");long topology=0;
        for(var lane:equipment.path("lanes"))if(Set.of("ambient","chilled").contains(lane.path("zoneId").asString())){lanes.add(lane);topology+=lane.path("version").asLong();}
        result.put("topologyVersion",topology);var candidates=result.putArray("candidates");
        for(var task:tasks){
            UUID id=task.get("movement_id",UUID.class);var item=evidence.get(id);var allocation=item==null?JsonSupport.MAPPER.nullNode():item.path("allocation");
            var candidate=candidates.addObject().put("movementId",id.toString()).put("priority",task.get("priority",Integer.class)).put("eligibleAt",timestamp(task.get("eligible_at",OffsetDateTime.class).toInstant())).put("zoneId",task.get("zone_id",String.class));
            // The local assignment is authoritative for this task; a changed adapter allocation cannot silently rewrite it.
            candidate.put("owner","legacy-core");candidate.put("epoch",task.get("epoch",Long.class));
            boolean same=!allocation.isNull() && task.get("allocation_id").toString().equals(allocation.path("allocationId").asString());
            candidate.put("allocationState",same?allocation.path("state").asString():"PENDING");
            if(item==null || item.path("command").isNull() || item.path("command").isMissingNode())candidate.putNull("commandState");else candidate.put("commandState",item.path("command").path("state").asString());
        }
        return result;
    }
    private void observe(Record task,JsonNode command){
        String state=command.path("state").asString();
        if(state.equals("COMPLETED")){orders.complete(task.get("site_id",String.class),task.get("movement_id",UUID.class),command);return;}
        update(task,switch(state){case "ACCEPTED_BY_SIMULATOR","EXECUTING"->"IN_PROGRESS";case "OUTCOME_UNKNOWN","QUARANTINED","REJECTED_BEFORE_EXECUTION"->"RECONCILIATION_REQUIRED";default->"DISPATCH_REQUESTED";},command.path("lastError").isNull()?null:command.path("lastError").asString(),false);
        database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
            sql.execute("UPDATE legacy_tasks SET dispatch_accepted_at=COALESCE(dispatch_accepted_at,?::timestamptz) WHERE movement_id=?",command.path("createdAt").asString(now().toString()),task.get("movement_id"));
        });
    }
    private void defer(Record task,String error,boolean transport){update(task,error.equals("LANE_BLOCKED")?"BLOCKED":"RECONCILIATION_REQUIRED",error,transport);}
    private void update(Record task,String state,String error,boolean transport){
        database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
            int failures=transport?task.get("transport_failures",Integer.class)+1:0;
            var next=transport?now().plus(RetryDelay.after(task.get("movement_id",UUID.class),failures)):now().plusNanos(300_000_000);
            sql.execute("UPDATE legacy_tasks SET state=?,last_error=?,version=version+1,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL,transport_failures=?,transport_paused=? WHERE movement_id=? AND lease_id=? AND state NOT IN ('COMPLETED','CANCELLED')",state,error,next,failures,failures>=RetryDelay.MAX_ATTEMPTS,task.get("movement_id"),task.get("lease_id"));
        });
    }
    private void ensureDispatchWritable(){database.transaction(configuration->Database.requireDurability(DSL.using(configuration),false));}
    public JsonNode resume(String actor,String site,UUID taskId,String key,JsonNode body){
        var reason=body.path("reason").asString("").strip();
        if(reason.length()<8 || reason.length()>500 || !body.path("expectedVersion").isIntegralNumber())throw Problem.invalid("A reason of 8–500 characters and expectedVersion are required.");
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);
            return dev.cutover.platform.Idempotency.execute(sql,actor,site,"legacy-task-recovery:"+taskId,key,body,()->{
                if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Task recovery is paused for a checkpoint.");
                var task=sql.fetchOne("SELECT * FROM legacy_tasks WHERE site_id=? AND task_id=? FOR UPDATE",site,taskId);
                if(task==null)throw Problem.missing();long version=task.get("version",Long.class);
                if(version!=body.path("expectedVersion").asLong() || !task.get("transport_paused",Boolean.class)
                        || Set.of("COMPLETED","CANCELLED").contains(task.get("state",String.class)))throw Problem.conflict("TASK_RECOVERY_CONFLICT","Refresh the paused task before requesting another status investigation.");
                sql.execute("UPDATE legacy_tasks SET transport_paused=false,transport_failures=0,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL,version=version+1 WHERE task_id=?",now(),taskId);
                sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome) VALUES (?,?,?,'legacy-task-recovery',?,?,?,?, 'STATUS_INVESTIGATION_REQUESTED')",UUID.randomUUID(),site,actor,taskId.toString(),reason,version,version+1);
                return JsonSupport.MAPPER.createObjectNode().put("taskId",taskId.toString()).put("version",version+1).put("outcome","STATUS_INVESTIGATION_REQUESTED");
            });
        });
    }
    public JsonNode tasks(String site){
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('id',task_id,'movementId',movement_id,'orderId',order_id,'zoneId',zone_id,'state',state,'owner',owner,'epoch',epoch,'version',version,'lastError',last_error,'eligibleAt',eligible_at,'transportFailures',transport_failures,'transportPaused',transport_paused,'dispatchAcceptedAt',dispatch_accepted_at) ORDER BY priority DESC,eligible_at,movement_id),'[]'::jsonb) FROM (SELECT * FROM legacy_tasks WHERE site_id=? ORDER BY priority DESC,eligible_at,movement_id LIMIT 100) t",site);
    }
    private static String timestamp(Instant instant){return instant.truncatedTo(ChronoUnit.MICROS).toString();}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
