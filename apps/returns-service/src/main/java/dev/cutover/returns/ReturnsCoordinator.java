package dev.cutover.returns;

import dev.cutover.platform.*;
import dev.cutover.platform.messaging.RetryDelay;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** A bounded FIFO coordinator for assigned sorting movements, independent of outbound scheduling. */
public final class ReturnsCoordinator {
    private final DSLContext database;
    private final DispatchPort adapter;
    private final ReceiptService receipts;
    private final Clock clock;
    public ReturnsCoordinator(DSLContext database,DispatchPort adapter,ReceiptService receipts,Clock clock){
        this.database=database;this.adapter=adapter;this.receipts=receipts;this.clock=clock;
    }
    public int poll(){
        if(database.fetchOne("SELECT workers_paused FROM service_control WHERE singleton").get(0,Boolean.class))return 0;
        // Empty polling must not allocate a transaction ID or generate row-lock WAL.
        if(!database.fetchOne("SELECT EXISTS(SELECT 1 FROM return_tasks WHERE state NOT IN ('COMPLETED','RECONCILIATION_REQUIRED') AND NOT transport_paused AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz))",now(),now()).get(0,Boolean.class))return 0;
        var tasks=database.transactionResult(configuration->{var sql=DSL.using(configuration);
            if(!Database.workersMayWrite(sql))return sql.fetch("SELECT t.*,m.movement,m.receipt_id FROM return_tasks t JOIN return_movements m USING(movement_id) WHERE false");
            var rows=sql.fetch("SELECT t.*,m.movement,m.receipt_id FROM return_tasks t JOIN return_movements m USING(movement_id) WHERE t.state NOT IN ('COMPLETED','RECONCILIATION_REQUIRED') AND NOT t.transport_paused AND t.next_attempt_at<=?::timestamptz AND (t.lease_until IS NULL OR t.lease_until<?::timestamptz) ORDER BY t.created_at,t.movement_id LIMIT 16 FOR UPDATE OF t SKIP LOCKED",now(),now());
            for(var task:rows){UUID lease=UUID.randomUUID();task.set(DSL.field("lease_id",UUID.class),lease);sql.execute("UPDATE return_tasks SET lease_id=?,lease_until=?::timestamptz WHERE task_id=?",lease,now().plusSeconds(60),task.get("task_id"));}
            return rows;
        });
        var groups=new LinkedHashMap<String,List<Record>>();
        for(var task:tasks)groups.computeIfAbsent(task.get("site_id",String.class),ignored->new ArrayList<>()).add(task);
        for(var group:groups.values()){
            try{coordinate(group);}catch(Problem failure){for(var task:group)defer(task,failure.code(),failure.status()==503);}
            catch(ServiceHttp.Unavailable failure){for(var task:group)defer(task,"ADAPTER_UNAVAILABLE",true);}
        }
        return tasks.size();
    }
    private void coordinate(List<Record> tasks){
        String site=tasks.getFirst().get("site_id",String.class);
        JsonNode context=adapter.context(site,tasks.stream().map(task->task.get("movement_id",UUID.class)).toList());
        var items=new HashMap<UUID,JsonNode>();context.path("movements").forEach(item->items.put(Database.uuid(item,"movementId"),item));
        var equipment=context.path("equipment");var route=context.path("route");
        var lanes=new ArrayList<String>();
        for(var lane:equipment.path("lanes"))if(site.equals(lane.path("siteId").asString()) && "returns".equals(lane.path("zoneId").asString()) && !lane.path("blocked").asBoolean())lanes.add(lane.path("laneId").asString());
        lanes.sort(Comparator.naturalOrder());
        for(var task:tasks){
            try{
                UUID movement=task.get("movement_id",UUID.class);JsonNode item=items.get(movement);
                if(item!=null && item.hasNonNull("command")){observe(task,item.path("command"));continue;}
                var assignment=item==null?JsonSupport.MAPPER.nullNode():item.path("allocation");
                if(!"returns-service".equals(route.path("owner").asString()) || task.get("epoch",Long.class)!=route.path("epoch").asLong(-1)
                        || !task.get("allocation_id").toString().equals(assignment.path("allocationId").asString())
                        || !"returns-service".equals(assignment.path("owner").asString()) || task.get("epoch",Long.class)!=assignment.path("epoch").asLong(-1)){
                    update(task,"RECONCILIATION_REQUIRED","RETURN_OWNERSHIP_MISMATCH",false,null);continue;
                }
                if(equipment.path("worldMismatch").asBoolean() || "RECONCILIATION_REQUIRED".equals(route.path("state").asString())){update(task,"RECONCILIATION_REQUIRED","EQUIPMENT_HISTORY_UNCERTAIN",false,null);continue;}
                if(equipment.path("stale").asBoolean(true)){update(task,"BLOCKED","EQUIPMENT_STALE",false,null);continue;}
                if(!"ACTIVE".equals(route.path("state").asString()) || !"ASSIGNED".equals(assignment.path("state").asString())){update(task,"BLOCKED","RETURN_ROUTE_WAITING",false,null);continue;}
                if(lanes.isEmpty()){update(task,"BLOCKED","LANE_BLOCKED",false,null);continue;}
                if(dispatchable(task))observe(task,adapter.dispatch(site,movement,task.get("allocation_id",UUID.class),task.get("epoch",Long.class),lanes.getFirst(),JsonSupport.read(task.get("movement").toString())));
            }catch(Problem failure){defer(task,failure.code(),failure.status()==503);}
            catch(ServiceHttp.Unavailable failure){defer(task,"ADAPTER_UNAVAILABLE",true);}
        }
    }
    private boolean dispatchable(Record task){return database.transactionResult(configuration->{var sql=DSL.using(configuration);Database.requireDurability(sql,false);
        return sql.fetchOne("SELECT EXISTS(SELECT 1 FROM return_tasks WHERE task_id=? AND lease_id=? AND lease_until>?::timestamptz AND state NOT IN ('COMPLETED','RECONCILIATION_REQUIRED') AND NOT transport_paused)",task.get("task_id"),task.get("lease_id"),now()).get(0,Boolean.class);
    });}
    private void observe(Record task,JsonNode command){
        var payload=command.path("payload");var intent=JsonSupport.read(task.get("movement").toString());
        if(!task.get("movement_id").toString().equals(command.path("commandId").asString()) || !task.get("site_id").equals(command.path("siteId").asString())
                || !"returns-service".equals(command.path("owner").asString()) || !task.get("allocation_id").toString().equals(command.path("allocationId").asString())
                || task.get("epoch",Long.class)!=command.path("epoch").asLong(-1))throw Problem.conflict("COMMAND_EVIDENCE_MISMATCH","Investigate the original returns command identity.");
        for(String field:List.of("loadId","movementId","siteId","source","destination","zoneId","quantity"))if(!intent.path(field).equals(payload.path(field)))throw Problem.conflict("COMMAND_EVIDENCE_MISMATCH","The command differs from the original sorting movement.");
        String state=command.path("state").asString();
        if(state.equals("COMPLETED")){receipts.complete(task.get("site_id",String.class),task.get("movement_id",UUID.class),command);return;}
        String next=switch(state){case "ACCEPTED_BY_SIMULATOR","EXECUTING"->"IN_PROGRESS";case "RECORDED","SEND_PENDING"->"DISPATCH_REQUESTED";case "OUTCOME_UNKNOWN","QUARANTINED","REJECTED_BEFORE_EXECUTION"->"RECONCILIATION_REQUIRED";default->throw Problem.conflict("COMMAND_STATE_UNRECOGNIZED","An unrecognized physical command state needs investigation.");};
        update(task,next,command.path("lastError").asString(null),false,command.path("createdAt").asString(now().toString()));
    }
    private void defer(Record task,String error,boolean transport){update(task,transport || Set.of("LANE_BLOCKED","EQUIPMENT_STALE","DURABILITY_PAUSED").contains(error)?"BLOCKED":"RECONCILIATION_REQUIRED",error,transport,null);}
    private void update(Record task,String state,String error,boolean transport,String acceptedAt){database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
        // Receipt then task is also the completion/inbox lock order. A late response cannot undo completion.
        sql.fetchOne("SELECT receipt_id FROM receipts WHERE receipt_id=? FOR UPDATE",task.get("receipt_id"));
        int failures=transport?task.get("transport_failures",Integer.class)+1:0;
        var next=transport?now().plus(RetryDelay.after(task.get("movement_id",UUID.class),failures)):now().plusNanos(300_000_000);
        int changed=sql.execute("UPDATE return_tasks SET state=?,last_error=?,version=version+1,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL,transport_failures=?,transport_paused=?,dispatch_accepted_at=COALESCE(dispatch_accepted_at,?::timestamptz) WHERE task_id=? AND lease_id=? AND state NOT IN ('COMPLETED','RECONCILIATION_REQUIRED')",state,error,next,failures,failures>=RetryDelay.MAX_ATTEMPTS,acceptedAt,task.get("task_id"),task.get("lease_id"));
        if(changed>0 && state.equals("RECONCILIATION_REQUIRED"))sql.execute("UPDATE receipts SET state='RECONCILIATION_REQUIRED',version=version+1 WHERE receipt_id=? AND state NOT IN ('COMPLETED','RECONCILIATION_REQUIRED')",task.get("receipt_id"));
    });}
    public JsonNode resume(String actor,String site,UUID id,String key,JsonNode body){
        String reason=body.path("reason").asString("").strip();if(reason.length()<8 || reason.length()>500 || !body.path("expectedVersion").isIntegralNumber())throw Problem.invalid("A reason of 8–500 characters and expectedVersion are required.");
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);return Idempotency.execute(sql,actor,site,"returns-task-recovery:"+id,key,body,()->{
            if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Returns recovery is paused for a checkpoint.");
            var task=sql.fetchOne("SELECT * FROM return_tasks WHERE site_id=? AND task_id=? FOR UPDATE",site,id);if(task==null)throw Problem.missing();long version=task.get("version",Long.class);
            if(version!=body.path("expectedVersion").asLong() || !task.get("transport_paused",Boolean.class) || Set.of("COMPLETED","RECONCILIATION_REQUIRED").contains(task.get("state",String.class)))throw Problem.conflict("RETURN_RECOVERY_CONFLICT","Refresh the paused task. Physical uncertainty must be reconciled through the adapter.");
            sql.execute("UPDATE return_tasks SET transport_paused=false,transport_failures=0,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL,version=version+1 WHERE task_id=?",now(),id);
            sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome) VALUES (?,?,?,'returns-task-recovery',?,?,?,?, 'STATUS_INVESTIGATION_REQUESTED')",UUID.randomUUID(),site,actor,id.toString(),reason,version,version+1);
            return JsonSupport.MAPPER.createObjectNode().put("taskId",id.toString()).put("version",version+1).put("outcome","STATUS_INVESTIGATION_REQUESTED");
        });});
    }
    public JsonNode tasks(String site){return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('id',task_id,'movementId',movement_id,'allocationId',allocation_id,'receiptId',receipt_id,'classification',classification,'zoneId','returns','state',state,'owner','returns-service','epoch',epoch,'version',version,'lastError',last_error,'transportFailures',transport_failures,'transportPaused',transport_paused,'dispatchAcceptedAt',dispatch_accepted_at) ORDER BY created_at,movement_id),'[]'::jsonb) FROM (SELECT t.*,m.receipt_id,m.classification FROM return_tasks t JOIN return_movements m USING(movement_id) WHERE t.site_id=? ORDER BY t.created_at,t.movement_id LIMIT 100) tasks",site);}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
