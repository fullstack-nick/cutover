package dev.cutover.platform.control;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Idempotency;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Process-wide controls for the single active lab site. No SQL or arbitrary operation is accepted. */
public final class RuntimeControls {
    private final DSLContext database;
    public RuntimeControls(DSLContext database) { this.database=database; }
    public JsonNode status(String site) {
        activeSite(site);
        return Database.json(database,"SELECT jsonb_build_object('version',version,'intakePaused',intake_paused,'dispatchPaused',dispatch_paused,'workersPaused',workers_paused,'criticalStorage',critical_storage,'relayPaused',relay_paused,'consumerPaused',consumer_paused) FROM service_control WHERE singleton");
    }
    public JsonNode change(String actor,String site,String key,JsonNode request) {
        activeSite(site); String reason=validate("runtime-control-request.v1",request);
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"runtime-control",key,request,()-> {
                long before=lockVersion(sql,request);
                // Hold the exclusive control lock before checking the freeze; two writers must not upgrade shared locks.
                if (!Database.workersMayWrite(sql) && !(request.has("workersPaused") && !request.path("workersPaused").asBoolean()))
                    throw new Problem(503,"WORKERS_PAUSED","Only an explicit worker resume can change controls during a checkpoint.");
                var names=Map.of("intakePaused","intake_paused","dispatchPaused","dispatch_paused","workersPaused","workers_paused","criticalStorage","critical_storage","relayPaused","relay_paused","consumerPaused","consumer_paused");
                for (var field:names.entrySet()) if (request.has(field.getKey())) sql.execute("UPDATE service_control SET "+field.getValue()+"=? WHERE singleton",request.path(field.getKey()).asBoolean());
                sql.execute("UPDATE service_control SET version=version+1 WHERE singleton");
                JsonNode response=JsonSupport.MAPPER.valueToTree(Map.of("siteId",site,"state","CONTROL_RECORDED","version",before+1));
                audit(sql,actor,site,"runtime-control","process",reason,before,before+1,request);
                return response;
            });
        });
    }
    public JsonNode arm(String actor,String site,String key,JsonNode request) {
        activeSite(site); String reason=validate("process-fault-request.v1",request);
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"process-fault-arm",key,request,()-> {
                long before=lockVersion(sql,request);
                if (!Database.workersMayWrite(sql)) throw new Problem(503,"WORKERS_PAUSED","Fault changes are paused for the checkpoint.");
                if (sql.fetchExists(sql.selectOne().from("process_faults").where("site_id=? AND checkpoint=? AND remaining=1",site,request.path("checkpoint").asString())))
                    throw Problem.conflict("FAULT_ALREADY_ARMED","Clear the existing one-shot fault before arming this checkpoint again.");
                UUID id=UUID.randomUUID();
                sql.execute("INSERT INTO process_faults(fault_id,site_id,checkpoint,event_selector,event_type,actor,reason) VALUES (?,?,?,?,?,?,?)",id,site,request.path("checkpoint").asString(),
                        request.hasNonNull("eventId")?Database.uuid(request,"eventId"):null,request.hasNonNull("eventType")?request.path("eventType").asString():null,actor,reason);
                sql.execute("UPDATE service_control SET version=version+1 WHERE singleton");
                JsonNode response=JsonSupport.MAPPER.valueToTree(Map.of("faultId",id,"state","ARMED","version",1,"controlVersion",before+1));
                audit(sql,actor,site,"process-fault-arm",id.toString(),reason,before,before+1,request);
                return response;
            });
        });
    }
    public JsonNode faults(String site) {
        activeSite(site);
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('faultId',fault_id,'checkpoint',checkpoint,'eventId',event_selector,'eventType',event_type,'remaining',remaining,'version',version,'armedAt',armed_at,'firedAt',fired_at,'firedEventId',fired_event_id,'clearedAt',cleared_at) ORDER BY armed_at,fault_id),'[]'::jsonb) FROM (SELECT * FROM process_faults WHERE site_id=? ORDER BY armed_at DESC,fault_id LIMIT 100) f",site);
    }
    public JsonNode clear(String actor,String site,UUID id,String key,JsonNode request) {
        activeSite(site); String reason=validate("reconciliation-request.v1",request);
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"process-fault-clear",key,Map.of("faultId",id,"request",request),()-> {
                if (!Database.workersMayWrite(sql)) throw new Problem(503,"WORKERS_PAUSED","Fault changes are paused for the checkpoint.");
                var row=sql.fetchOne("SELECT version FROM process_faults WHERE fault_id=? AND site_id=? FOR UPDATE",id,site);
                if (row==null) throw Problem.missing();
                long before=row.get(0,Long.class);
                if (before!=request.path("expectedVersion").asLong()) throw Problem.conflict("VERSION_CONFLICT","The fault activation state changed.");
                sql.execute("UPDATE process_faults SET remaining=0,cleared_at=now(),version=version+1 WHERE fault_id=?",id);
                JsonNode response=JsonSupport.MAPPER.valueToTree(Map.of("faultId",id,"state","CLEARED","version",before+1));
                audit(sql,actor,site,"process-fault-clear",id.toString(),reason,before,before+1,response); return response;
            });
        });
    }
    private static void activeSite(String site) { if (!"site-a".equals(site)) throw Problem.missing(); }
    private static String validate(String schema,JsonNode request) {
        try { Contracts.validate(schema,JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("The control request violates its bounded contract."); }
        String reason=request.path("reason").asString().trim();
        if (reason.length()<8) throw Problem.invalid("Explain the intended fault or recovery action."); return reason;
    }
    private static long lockVersion(DSLContext sql,JsonNode request) {
        Database.controlWriteLock(sql);
        long before=sql.fetchOne("SELECT version FROM service_control WHERE singleton FOR UPDATE").get(0,Long.class);
        if (before!=request.path("expectedVersion").asLong()) throw Problem.conflict("VERSION_CONFLICT","The process controls changed; inspect them before retrying."); return before;
    }
    private static void audit(DSLContext sql,String actor,String site,String action,String resource,String reason,long before,long after,JsonNode detail) {
        sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,?,?,?,?,?,'RECORDED',?::jsonb)",UUID.randomUUID(),site,actor,action,resource,reason,before,after,JsonSupport.write(detail));
    }
}
