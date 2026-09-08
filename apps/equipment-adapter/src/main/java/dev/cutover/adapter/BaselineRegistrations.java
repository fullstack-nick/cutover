package dev.cutover.adapter;

import dev.cutover.platform.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** One-time baseline evidence. This endpoint cannot change ownership, execute work or invent a terminal outcome. */
public final class BaselineRegistrations {
    private final DSLContext database;
    public BaselineRegistrations(DSLContext database){this.database=database;}
    public JsonNode register(String site,String client,JsonNode body){
        if(!client.equals("legacy-core"))throw new Problem(403,"REGISTRATION_IDENTITY","Only the legacy inventory owner can register its baseline.");
        try{Contracts.validate("baseline-registration-request.v1",JsonSupport.write(body));}catch(IllegalArgumentException invalid){throw Problem.invalid("Invalid bounded registration request.");}
        UUID registration=Database.uuid(body,"registrationId");
        if(!body.path("expectedControlVersion").isIntegralNumber() || !body.path("items").isArray() || body.path("items").size()>32)throw Problem.invalid("A control version and at most 32 registration items are required.");
        var identities=new HashSet<UUID>();var taskIds=new HashSet<UUID>();
        for(var item:body.path("items")){
            if(!identities.add(Database.uuid(item.path("movement"),"movementId")) || !taskIds.add(Database.uuid(item,"taskId")))throw Problem.invalid("The registration batch repeats an identity.");
            try{Contracts.validate("movement.v1",JsonSupport.write(item.path("movement")));}catch(IllegalArgumentException invalid){throw Problem.invalid("Invalid baseline movement contract.");}
            if(!site.equals(item.path("movement").path("siteId").asString()) || !item.path("movement").path("product").asString().equals("fulfilment"))throw Problem.missing();
            if(!site.equals(item.path("siteId").asString()) || !item.path("movementId").equals(item.path("movement").path("movementId")))throw Problem.missing();
            if(!Set.of("COMPLETED","CANCELLED").contains(item.path("state").asString()))throw Problem.conflict("BASELINE_UNSETTLED","Settle the original baseline before recording the task-creation boundary.");
        }
        return database.transactionResult(configuration->{
            var sql=DSL.using(configuration);Database.controlReadLock(sql);
            var control=sql.fetchOne("SELECT * FROM service_control WHERE singleton");
            if(!control.get("dispatch_paused",Boolean.class) || control.get("workers_paused",Boolean.class) || control.get("critical_storage",Boolean.class)
                    || control.get("version",Long.class)!=body.path("expectedControlVersion").asLong())throw Problem.conflict("REGISTRATION_GATE","Registration requires the observed paused adapter dispatch gate.");
            StorageBudget.requireHeadroom(sql);
            for(String zone:List.of("ambient","chilled")){
                var route=sql.fetchOne("SELECT * FROM zone_routes WHERE site_id=? AND zone_id=? FOR UPDATE",site,zone);
                if(route==null || !route.get("owner",String.class).equals("legacy-core") || !route.get("state",String.class).equals("ACTIVE"))throw Problem.conflict("BASELINE_OWNER","The baseline must still have its original owner in both zones.");
            }
            var result=JsonSupport.MAPPER.createObjectNode().put("registrationId",registration.toString()).put("siteId",site).put("controlVersion",control.get("version",Long.class));
            var receipts=result.putArray("receipts");
            for(var item:body.path("items")){
                UUID task=Database.uuid(item,"taskId"),movement=Database.uuid(item.path("movement"),"movementId");
                Database.lock(sql,"movement",site,movement);
                var allocation=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id=? AND movement_id=? FOR UPDATE",site,movement);
                if(allocation==null)throw Problem.conflict("BASELINE_ALLOCATION_MISSING","Existing terminal work requires its retained allocation and physical or cancellation evidence.");
                String hash=JsonSupport.hash(item.path("movement"));
                if(!hash.equals(allocation.get("payload_hash")) || !"legacy-core".equals(allocation.get("owner")) || !item.path("state").asString().equals(allocation.get("state")))throw Problem.conflict("BASELINE_EVIDENCE_MISMATCH","The retained allocation does not match the legacy task.");
                if(item.hasNonNull("allocationId") && !item.path("allocationId").asString().equals(allocation.get("allocation_id").toString()))throw Problem.conflict("BASELINE_LINK_MISMATCH","The task already refers to a different allocation.");
                if(item.hasNonNull("epoch") && item.path("epoch").asLong()!=allocation.get("epoch",Long.class))throw Problem.conflict("BASELINE_EPOCH_MISMATCH","The existing task epoch must be preserved.");
                if(item.path("state").asString().equals("COMPLETED")){
                    var command=CommandJournal.view(sql,site,movement);
                    if(!command.path("state").asString().equals("COMPLETED") || command.path("evidence").path("executionSequence").asLong()<1)throw Problem.conflict("BASELINE_COMPLETION_MISSING","A completed baseline task needs retained physical evidence.");
                }
                String requestHash=JsonSupport.hash(item);
                var old=sql.fetchOne("SELECT request_hash,receipt FROM legacy_registration_receipts WHERE registration_id=? AND site_id=? AND task_id=?",registration,site,task);
                if(old!=null){if(!requestHash.equals(old.get("request_hash")))throw Problem.conflict("REGISTRATION_CONFLICT","A retained registration item cannot change.");receipts.add(JsonSupport.read(old.get("receipt").toString()));continue;}
                var receipt=JsonSupport.MAPPER.createObjectNode().put("registrationId",registration.toString()).put("taskId",task.toString()).put("movementId",movement.toString()).put("siteId",site)
                        .put("allocationId",allocation.get("allocation_id").toString()).put("owner","legacy-core").put("epoch",allocation.get("epoch",Long.class)).put("state",allocation.get("state",String.class)).put("payloadHash",hash).put("controlVersion",control.get("version",Long.class));
                sql.execute("INSERT INTO legacy_registration_receipts(registration_id,site_id,task_id,movement_id,request_hash,receipt) VALUES (?,?,?,?,?,?::jsonb)",registration,site,task,movement,requestHash,JsonSupport.write(receipt));
                receipts.add(receipt);
            }
            return result;
        });
    }
}
