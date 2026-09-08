package dev.cutover.core;

import dev.cutover.platform.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Owner-run maintenance step, separate from a business ownership cutover. All terminal task IDs are retained. */
public final class LegacyBoundaryRegistration {
    @FunctionalInterface public interface Adapter { JsonNode register(String site,JsonNode body); }
    private final DSLContext database;private final Adapter adapter;
    public LegacyBoundaryRegistration(DSLContext database,Adapter adapter){this.database=database;this.adapter=adapter;}
    public JsonNode register(String site,UUID registration,long adapterVersion,String reason){
        if(!"site-a".equals(site))throw Problem.missing();
        if(reason==null || reason.strip().length()<8 || reason.length()>500 || adapterVersion<0)throw Problem.invalid("A control version and bounded maintenance reason are required.");
        String requestHash=JsonSupport.hash(Map.of("siteId",site,"registrationId",registration,"adapterVersion",adapterVersion,"reason",reason));
        var previous=database.fetchOne("SELECT request_hash,response FROM legacy_boundary_checkpoints WHERE site_id=? AND registration_id=?",site,registration);
        if(previous!=null){if(!requestHash.equals(previous.get("request_hash")))throw Problem.conflict("REGISTRATION_CONFLICT","The completed registration request cannot change.");return JsonSupport.read(previous.get("response").toString());}
        var captured=database.transactionResult(configuration->{var sql=DSL.using(configuration);
            long version=gate(sql);JsonNode inventory=inventory(sql,site);
            if(inventory.size()>20000)throw Problem.conflict("BASELINE_INVENTORY_LIMIT","The declared baseline inventory limit is 20,000 tasks.");
            if(sql.fetchOne("SELECT count(*) FROM movement_intents WHERE site_id=?",site).get(0,Long.class)!=inventory.size())throw Problem.conflict("BASELINE_TASK_MISSING","Every pre-boundary movement must still have its original legacy task.");
            for(var item:inventory)if(!Set.of("COMPLETED","CANCELLED").contains(item.path("state").asString()))throw Problem.conflict("BASELINE_UNSETTLED","Finish or safely cancel the original baseline before this one-time task-creation transition.");
            return new Captured(version,inventory);
        });
        var receipts=JsonSupport.MAPPER.createArrayNode();
        for(int offset=0;offset<captured.inventory().size();offset+=32){
            var request=request(registration,adapterVersion);var items=request.putArray("items");
            for(int i=offset;i<Math.min(offset+32,captured.inventory().size());i++)items.add(captured.inventory().get(i));
            var response=adapter.register(site,request);
            if(!registration.toString().equals(response.path("registrationId").asString()) || response.path("controlVersion").asLong(-1)!=adapterVersion || !site.equals(response.path("siteId").asString()) || response.path("receipts").size()!=items.size())throw Problem.conflict("REGISTRATION_RESPONSE","The adapter did not attest the complete requested batch.");
            response.path("receipts").forEach(receipts::add);
        }
        // An empty batch also verifies the adapter pause/owner gate for an empty initial database.
        var confirmation=request(registration,adapterVersion);confirmation.putArray("items");
        var confirmed=adapter.register(site,confirmation);
        if(confirmed.path("controlVersion").asLong(-1)!=adapterVersion || !site.equals(confirmed.path("siteId").asString()) || !registration.toString().equals(confirmed.path("registrationId").asString()) || confirmed.path("receipts").size()!=0)throw Problem.conflict("REGISTRATION_GATE","The adapter gate changed during registration.");
        var byTask=new HashMap<UUID,JsonNode>();
        for(var receipt:receipts)if(byTask.put(Database.uuid(receipt,"taskId"),receipt)!=null)throw Problem.conflict("REGISTRATION_DUPLICATE","The adapter repeated a task identity.");
        for(var item:captured.inventory()){
            var receipt=byTask.get(Database.uuid(item,"taskId"));
            if(receipt==null || !registration.toString().equals(receipt.path("registrationId").asString()) || !site.equals(receipt.path("siteId").asString())
                    || !item.path("movementId").equals(receipt.path("movementId")) || !JsonSupport.hash(item.path("movement")).equals(receipt.path("payloadHash").asString())
                    || !receipt.path("owner").asString().equals("legacy-core") || !item.path("state").equals(receipt.path("state")) || receipt.path("controlVersion").asLong(-1)!=adapterVersion)throw Problem.conflict("REGISTRATION_EVIDENCE","A receipt does not match the retained legacy inventory.");
            Database.uuid(receipt,"allocationId");
            if(!receipt.path("epoch").isIntegralNumber() || receipt.path("epoch").asLong()<0)throw Problem.conflict("REGISTRATION_EVIDENCE","A receipt is missing its original allocation epoch.");
            if(item.hasNonNull("allocationId") && !item.path("allocationId").equals(receipt.path("allocationId")))throw Problem.conflict("BASELINE_LINK_MISMATCH","An existing legacy allocation link cannot change.");
            if(item.hasNonNull("epoch") && item.path("epoch").asLong()!=receipt.path("epoch").asLong())throw Problem.conflict("BASELINE_EPOCH_MISMATCH","An existing legacy epoch cannot change.");
        }
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);Database.lock(sql,"legacy-boundary",site);
            var concurrent=sql.fetchOne("SELECT request_hash,response FROM legacy_boundary_checkpoints WHERE site_id=? AND registration_id=?",site,registration);
            if(concurrent!=null){if(!requestHash.equals(concurrent.get("request_hash")))throw Problem.conflict("REGISTRATION_CONFLICT","The completed registration request cannot change.");return JsonSupport.read(concurrent.get("response").toString());}
            if(gate(sql)!=captured.version() || !inventory(sql,site).equals(captured.inventory()))throw Problem.conflict("REGISTRATION_INVENTORY_CHANGED","The paused core inventory changed; collect a new registration checkpoint.");
            String inventoryHash=JsonSupport.hash(captured.inventory());
            var response=JsonSupport.MAPPER.createObjectNode().put("registrationId",registration.toString()).put("siteId",site).put("state","VERIFIED").put("taskCount",captured.inventory().size()).put("inventoryHash",inventoryHash).put("coreControlVersion",captured.version()).put("adapterControlVersion",adapterVersion);
            sql.execute("INSERT INTO legacy_boundary_checkpoints(registration_id,site_id,request_hash,inventory_hash,inventory,adapter_receipts,task_count,core_control_version,adapter_control_version,reason,response) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::jsonb)",registration,site,requestHash,inventoryHash,JsonSupport.write(captured.inventory()),JsonSupport.write(receipts),captured.inventory().size(),captured.version(),adapterVersion,reason,JsonSupport.write(response));
            for(var item:captured.inventory()){
                UUID task=Database.uuid(item,"taskId");var receipt=byTask.get(task);UUID allocation=Database.uuid(receipt,"allocationId");long epoch=receipt.path("epoch").asLong();
                sql.execute("UPDATE legacy_tasks SET allocation_id=?,epoch=?,version=version+1 WHERE site_id=? AND task_id=? AND (allocation_id IS NULL OR epoch IS NULL)",allocation,epoch,site,task);
                sql.execute("INSERT INTO legacy_task_registration(site_id,task_id,movement_id,registration_id,allocation_id,epoch,payload_hash,receipt) VALUES (?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(site_id,task_id) DO UPDATE SET registration_id=excluded.registration_id,receipt=excluded.receipt",site,task,Database.uuid(item,"movementId"),registration,allocation,epoch,receipt.path("payloadHash").asString(),JsonSupport.write(receipt));
            }
            sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,'legacy-core','legacy-boundary-registration',?,?,?,?, 'VERIFIED',?::jsonb)",UUID.randomUUID(),site,registration.toString(),reason,captured.version(),captured.version(),JsonSupport.write(response));
            return JsonSupport.read(JsonSupport.write(response));
        });
    }
    private record Captured(long version,JsonNode inventory){}
    private static tools.jackson.databind.node.ObjectNode request(UUID id,long version){return JsonSupport.MAPPER.createObjectNode().put("registrationId",id.toString()).put("expectedControlVersion",version);}
    private static long gate(DSLContext sql){
        var control=sql.fetchOne("SELECT * FROM service_control WHERE singleton FOR SHARE");
        if(!control.get("intake_paused",Boolean.class) || !control.get("dispatch_paused",Boolean.class) || control.get("workers_paused",Boolean.class) || control.get("critical_storage",Boolean.class))throw Problem.conflict("REGISTRATION_GATE","Pause core intake and dispatch while keeping checkpoint/registration processing writable.");
        StorageBudget.requireHeadroom(sql);return control.get("version",Long.class);
    }
    private static JsonNode inventory(DSLContext sql,String site){return Database.json(sql,"SELECT COALESCE(jsonb_agg(jsonb_build_object('taskId',t.task_id,'siteId',t.site_id,'movementId',t.movement_id,'state',t.state,'allocationId',t.allocation_id,'epoch',t.epoch,'movement',t.movement) ORDER BY t.movement_id),'[]'::jsonb) FROM (SELECT t.task_id,t.site_id,t.movement_id,t.state,t.allocation_id,t.epoch,m.movement FROM legacy_tasks t JOIN movement_intents m ON m.site_id=t.site_id AND m.movement_id=t.movement_id WHERE t.site_id=? ORDER BY t.movement_id LIMIT 20001) t",site);}
}
