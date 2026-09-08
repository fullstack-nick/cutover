package dev.cutover.execution;

import dev.cutover.platform.*;
import dev.cutover.platform.messaging.RetryDelay;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Owns only execution tasks. The adapter gates physical authority; core owns inventory effects. */
public final class ExecutionScheduler {
    private final DSLContext database;private final DispatchPort adapter;private final Clock clock;
    public ExecutionScheduler(DSLContext database,DispatchPort adapter,Clock clock){this.database=database;this.adapter=adapter;this.clock=clock;}
    public int poll(){
        if(database.fetchOne("SELECT workers_paused FROM service_control WHERE singleton").get(0,Boolean.class))return 0;
        if(!database.fetchOne("SELECT EXISTS(SELECT 1 FROM execution_tasks WHERE state NOT IN ('COMPLETED','CANCELLED') AND NOT transport_paused AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz))",now(),now()).get(0,Boolean.class))return 0;
        var tasks=database.transactionResult(configuration->{var sql=DSL.using(configuration);
            if(!Database.workersMayWrite(sql))return sql.fetch("SELECT * FROM execution_tasks WHERE false");
            var rows=sql.fetch("SELECT * FROM execution_tasks WHERE state NOT IN ('COMPLETED','CANCELLED') AND NOT transport_paused AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz) ORDER BY priority DESC,eligible_at,movement_id LIMIT 16 FOR UPDATE SKIP LOCKED",now(),now());
            for(var row:rows){UUID lease=UUID.randomUUID();row.set(DSL.field("lease_id",UUID.class),lease);sql.execute("UPDATE execution_tasks SET lease_id=?,lease_until=?::timestamptz WHERE task_id=?",lease,now().plusSeconds(60),row.get("task_id"));}return rows;
        });
        var groups=new LinkedHashMap<String,List<Record>>();
        for(var task:tasks)groups.computeIfAbsent(task.get("site_id",String.class)+"/"+task.get("zone_id",String.class),ignored->new ArrayList<>()).add(task);
        for(var group:groups.values()){
            try{decide(group);}catch(Problem problem){for(var task:group)defer(task,problem.code(),problem.status()==503);}
            catch(ServiceHttp.Unavailable unavailable){for(var task:group)defer(task,"ADAPTER_UNAVAILABLE",true);}
        }
        return tasks.size();
    }
    private void decide(List<Record> tasks){
        String site=tasks.getFirst().get("site_id",String.class),zone=tasks.getFirst().get("zone_id",String.class);
        var context=adapter.context(site,zone,tasks.stream().map(task->task.get("movement_id",UUID.class)).toList());
        var evidence=new HashMap<UUID,JsonNode>();context.path("movements").forEach(item->evidence.put(Database.uuid(item,"movementId"),item));
        var snapshot=snapshot(site,zone,tasks,context,evidence);var unresolved=new HashMap<UUID,Record>();
        for(var task:tasks){UUID id=task.get("movement_id",UUID.class);var item=evidence.get(id);
            if(item!=null && item.hasNonNull("command"))observe(task,item.path("command"));else unresolved.put(id,task);
        }
        for(int round=0;round<=tasks.size();round++){
            snapshot.put("decisionAt",timestamp(clock.instant()));var proposal=SchedulingDecision.propose(snapshot);retain(snapshot,proposal);
            if(proposal.path("selectedMovementId").isNull()){
                for(var ranked:proposal.path("ranking")){var task=unresolved.remove(Database.uuid(ranked,"movementId"));if(task==null)continue;String reason=ranked.path("reason").asString();
                    update(task,Set.of("WORLD_MISMATCH","OWNER_MISMATCH","ROUTE_UNCERTAIN").contains(reason)?"RECONCILIATION_REQUIRED":"BLOCKED",reason,false,null);
                }return;
            }
            UUID id=Database.uuid(proposal,"selectedMovementId");var task=unresolved.remove(id);
            if(task==null)throw new IllegalStateException("A recorded command cannot be a new scheduling candidate.");
            try(var trace=OperationTrace.movement(database,"execution-service",site,id,"cutover.task.dispatch")){
                trace.field("cutover.task_id",task.get("task_id").toString()).field("cutover.movement_id",id.toString());
                if(dispatchable(task))observe(task,adapter.dispatch(site,id,task.get("allocation_id",UUID.class),task.get("epoch",Long.class),proposal.path("selectedLaneId").asString(),JsonSupport.read(task.get("movement").toString())));
            }
            catch(Problem problem){defer(task,problem.code(),problem.status()==503);}
            catch(ServiceHttp.Unavailable unavailable){defer(task,"ADAPTER_UNAVAILABLE",true);}
            var remaining=JsonSupport.MAPPER.createArrayNode();for(var candidate:snapshot.path("candidates"))if(!id.toString().equals(candidate.path("movementId").asString()))remaining.add(candidate);snapshot.set("candidates",remaining);
            if(remaining.isEmpty())return;
        }
    }
    private ObjectNode snapshot(String site,String zone,List<Record> tasks,JsonNode context,Map<UUID,JsonNode> evidence){
        var equipment=context.path("equipment");var result=JsonSupport.MAPPER.createObjectNode().put("ruleVersion",SchedulingDecision.RULE_VERSION).put("siteId",site).put("zoneId",zone)
            .put("decisionAt",timestamp(clock.instant())).put("observedAt",timestamp(Instant.parse(equipment.path("observedAt").asString("1970-01-01T00:00:00Z")))).put("worldMismatch",equipment.path("worldMismatch").asBoolean(false));
        result.set("route",context.required("route"));var lanes=result.putArray("lanes");long topology=0;
        for(var lane:equipment.path("lanes"))if(Set.of("ambient","chilled").contains(lane.path("zoneId").asString())){lanes.add(lane);topology+=lane.path("version").asLong();}result.put("topologyVersion",topology);
        var candidates=result.putArray("candidates");
        for(var task:tasks){UUID id=task.get("movement_id",UUID.class);var item=evidence.get(id);var allocation=item==null?JsonSupport.MAPPER.nullNode():item.path("allocation");
            var candidate=candidates.addObject().put("movementId",id.toString()).put("priority",task.get("priority",Integer.class)).put("eligibleAt",timestamp(task.get("eligible_at",OffsetDateTime.class).toInstant())).put("zoneId",task.get("zone_id",String.class)).put("owner","execution-service").put("epoch",task.get("epoch",Long.class));
            boolean same=!allocation.isNull() && task.get("allocation_id").toString().equals(allocation.path("allocationId").asString()) && "execution-service".equals(allocation.path("owner").asString()) && task.get("epoch",Long.class)==allocation.path("epoch").asLong(-1);
            candidate.put("allocationState",same?allocation.path("state").asString():"PENDING");
            if(item==null || !item.hasNonNull("command"))candidate.putNull("commandState");else candidate.put("commandState",item.path("command").path("state").asString());
        }return result;
    }
    private boolean dispatchable(Record task){return database.transactionResult(configuration->{var sql=DSL.using(configuration);Database.requireDurability(sql,false);
        return sql.fetchOne("SELECT EXISTS(SELECT 1 FROM execution_tasks WHERE task_id=? AND lease_id=? AND lease_until>?::timestamptz AND state NOT IN ('COMPLETED','CANCELLED') AND NOT transport_paused)",task.get("task_id"),task.get("lease_id"),now()).get(0,Boolean.class);
    });}
    private void observe(Record task,JsonNode command){
        String id=task.get("movement_id").toString();var payload=command.path("payload");var movement=JsonSupport.read(task.get("movement").toString());
        if(!id.equals(command.path("commandId").asString()) || !task.get("site_id").equals(command.path("siteId").asString())
            || !task.get("allocation_id").toString().equals(payload.path("allocationId").asString()))throw Problem.conflict("COMMAND_EVIDENCE_MISMATCH","Investigate the existing command identity before proceeding.");
        for(String field:Set.of("loadId","movementId","siteId","source","destination","zoneId","quantity"))if(!movement.path(field).equals(payload.path(field)))throw Problem.conflict("COMMAND_EVIDENCE_MISMATCH","The command payload differs from the assigned movement.");
        String state=command.path("state").asString();
        if(state.equals("COMPLETED") && command.path("evidence").path("executionSequence").asLong()<1)throw Problem.conflict("UNVERIFIED_COMPLETION","Physical completion requires retained positive evidence.");
        String taskState=switch(state){case "COMPLETED"->"COMPLETED";case "ACCEPTED_BY_SIMULATOR","EXECUTING"->"IN_PROGRESS";case "OUTCOME_UNKNOWN","QUARANTINED","REJECTED_BEFORE_EXECUTION"->"RECONCILIATION_REQUIRED";case "SEND_PENDING"->"DISPATCH_REQUESTED";default->throw Problem.conflict("COMMAND_STATE_UNRECOGNIZED","An unrecognized command state requires investigation.");};
        update(task,taskState,command.path("lastError").asString(null),false,command.path("createdAt").asString(now().toString()));
    }
    private void defer(Record task,String error,boolean transport){update(task,error.equals("LANE_BLOCKED")?"BLOCKED":"RECONCILIATION_REQUIRED",error,transport,null);}
    private void update(Record task,String state,String error,boolean transport,String acceptedAt){database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
        int failures=transport?task.get("transport_failures",Integer.class)+1:0;var next=transport?now().plus(RetryDelay.after(task.get("movement_id",UUID.class),failures)):now().plusNanos(300_000_000);
        sql.execute("UPDATE execution_tasks SET state=?,last_error=?,version=version+1,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL,transport_failures=?,transport_paused=?,dispatch_accepted_at=COALESCE(dispatch_accepted_at,?::timestamptz),completed_at=CASE WHEN ?='COMPLETED' THEN COALESCE(completed_at,?::timestamptz) ELSE completed_at END WHERE task_id=? AND lease_id=? AND state NOT IN ('COMPLETED','CANCELLED')",state,error,next,failures,failures>=RetryDelay.MAX_ATTEMPTS,acceptedAt,state,now(),task.get("task_id"),task.get("lease_id"));
    });}
    private void retain(JsonNode input,JsonNode proposal){database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Execution decision capture is paused for a checkpoint.");
        String snapshot=JsonSupport.write(input),decision=JsonSupport.write(proposal);int bytes=snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+decision.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int retained=sql.execute("UPDATE execution_decision_capacity SET retained_rounds=retained_rounds+1,retained_bytes=retained_bytes+? WHERE singleton AND retained_rounds<8192 AND retained_bytes + ? <= 67108864",bytes,bytes);
        if(retained==0){sql.execute("UPDATE execution_decision_capacity SET omitted_rounds=omitted_rounds+1 WHERE singleton");return;}
        sql.execute("INSERT INTO decision_rounds(round_id,site_id,input_hash,input,proposal,rule_version) VALUES (?,?,?,?::jsonb,?::jsonb,?)",UUID.randomUUID(),input.path("siteId").asString(),JsonSupport.hash(input),snapshot,decision,SchedulingDecision.RULE_VERSION);
    });}
    public JsonNode resume(String actor,String site,UUID taskId,String key,JsonNode body){
        String reason=body.path("reason").asString("").strip();if(reason.length()<8 || reason.length()>500 || !body.path("expectedVersion").isIntegralNumber())throw Problem.invalid("A reason of 8–500 characters and expectedVersion are required.");
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);return Idempotency.execute(sql,actor,site,"execution-task-recovery:"+taskId,key,body,()->{
            if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Task recovery is paused for a checkpoint.");
            var task=sql.fetchOne("SELECT * FROM execution_tasks WHERE site_id=? AND task_id=? FOR UPDATE",site,taskId);if(task==null)throw Problem.missing();long version=task.get("version",Long.class);
            if(version!=body.path("expectedVersion").asLong() || !task.get("transport_paused",Boolean.class) || Set.of("COMPLETED","CANCELLED").contains(task.get("state",String.class)))throw Problem.conflict("TASK_RECOVERY_CONFLICT","Refresh the paused task before requesting another status investigation.");
            sql.execute("UPDATE execution_tasks SET transport_paused=false,transport_failures=0,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL,version=version+1 WHERE task_id=?",now(),taskId);
            sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome) VALUES (?,?,?,'execution-task-recovery',?,?,?,?, 'STATUS_INVESTIGATION_REQUESTED')",UUID.randomUUID(),site,actor,taskId.toString(),reason,version,version+1);
            return JsonSupport.MAPPER.createObjectNode().put("taskId",taskId.toString()).put("version",version+1).put("outcome","STATUS_INVESTIGATION_REQUESTED");
        });});
    }
    public JsonNode tasks(String site){return taskRows(site,null);}
    public JsonNode task(String site,UUID id){var rows=taskRows(site,id);if(rows.isEmpty())throw Problem.missing();return rows.get(0);}
    private JsonNode taskRows(String site,UUID taskId){return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('id',task_id,'movementId',movement_id,'allocationId',allocation_id,'zoneId',zone_id,'state',state,'owner','execution-service','epoch',epoch,'version',version,'lastError',last_error,'eligibleAt',eligible_at,'transportFailures',transport_failures,'transportPaused',transport_paused,'dispatchAcceptedAt',dispatch_accepted_at) ORDER BY (state IN ('COMPLETED','CANCELLED')),CASE WHEN state IN ('COMPLETED','CANCELLED') THEN eligible_at END DESC,priority DESC,eligible_at,movement_id),'[]'::jsonb) FROM (SELECT * FROM execution_tasks WHERE site_id=? AND (?::uuid IS NULL OR task_id=?::uuid) ORDER BY (state IN ('COMPLETED','CANCELLED')),CASE WHEN state IN ('COMPLETED','CANCELLED') THEN eligible_at END DESC,priority DESC,eligible_at,movement_id LIMIT 100) t",site,taskId,taskId);}
    private static String timestamp(Instant value){return value.truncatedTo(ChronoUnit.MICROS).toString();}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
