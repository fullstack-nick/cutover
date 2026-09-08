package dev.cutover.returns;

import dev.cutover.platform.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import static dev.cutover.generated.returns_service.Tables.RECEIPTS;

/** Owns receipt totals and sorting effects; no fulfilment tables or scheduler dependency. */
public final class ReceiptService {
    private static final Map<String,String> DESTINATIONS = Map.of("REUSABLE","reusable","NEEDS_CLEANING","cleaning","DAMAGED","damaged");
    private final DSLContext database;
    private final Clock clock;
    public ReceiptService(DSLContext database, Clock clock) { this.database=database; this.clock=clock; }

    public JsonNode register(String caller,String site,String key,JsonNode body) {
        return OperationTrace.call("cutover.receipt.register",site,null,()->registerTraced(caller,site,key,body));
    }
    private JsonNode registerTraced(String caller,String site,String key,JsonNode body) {
        try { Contracts.validate("receipt-request.v1",JsonSupport.write(body)); }
        catch(IllegalArgumentException invalid) { throw Problem.invalid("Use the bounded source reference and all three documented integer classification counts."); }
        String source=body.path("sourceSystem").asString(),reference=body.path("externalReceiptRef").asString();
        int total=DESTINATIONS.keySet().stream().mapToInt(type->body.path("counts").path(type).asInt()).sum();
        if(source.isBlank() || reference.isBlank() || total==0)throw Problem.invalid("A receipt needs a nonblank external reference and at least one crate.");
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,caller,site,"register-return-receipt",key,body,()->{
                if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Receipt writes are paused for a checkpoint.");
                Database.lock(sql,"external-return-receipt",site,source,reference);
                // A fresh key allocates response metadata even when the business reference already exists.
                Database.requireDurability(sql,true);
                var old=sql.fetchOne("SELECT receipt_id,payload_hash FROM receipts WHERE site_id=? AND source_system=? AND external_ref=?",site,source,reference);
                if(old!=null){
                    if(!JsonSupport.hash(body).equals(old.get("payload_hash",String.class)))throw Problem.conflict("RECEIPT_REFERENCE_CONFLICT","This external receipt reference already has different contents.");
                    return accepted(site,old.get("receipt_id",UUID.class));
                }
                if(!sql.fetchExists(sql.selectOne().from("return_sites").where("site_id=?",site)))throw Problem.missing();
                var quota=sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
                if(quota.get("active_requests",Integer.class)>=800 || quota.get("unpublished_events",Integer.class)>=8000 || quota.get("unpublished_bytes",Long.class)>=53687091)
                    throw new Problem(503,"ADMISSION_CAPACITY","Accepted receipts are near capacity; retry after backlog recovery.");
                UUID id=UUID.randomUUID();
                OperationTrace.annotate("cutover.receipt_id",id.toString());
                sql.execute("INSERT INTO receipts(receipt_id,site_id,source_system,external_ref,payload_hash,state,created_at) VALUES (?,?,?,?,?,'REGISTERED',?::timestamptz)",id,site,source,reference,JsonSupport.hash(body),now());
                sql.execute("UPDATE admission SET active_requests=active_requests+1 WHERE singleton");
                for(String type:DESTINATIONS.keySet().stream().sorted().toList()) {
                    int quantity=body.path("counts").path(type).asInt();
                    sql.execute("INSERT INTO receipt_counts(site_id,receipt_id,classification,received) VALUES (?,?,?,?)",site,id,type,quantity);
                    sql.execute("UPDATE crate_counters SET received=received+? WHERE site_id=? AND classification=?",quantity,site,type);
                    if(quantity==0)continue;
                    UUID movementId=UUID.nameUUIDFromBytes(("cutover-return/"+id+"/"+type).getBytes(StandardCharsets.UTF_8));
                    var movement=JsonSupport.MAPPER.createObjectNode().put("movementId",movementId.toString()).putNull("reservationId")
                        .put("siteId",site).put("product","returns").put("zoneId","returns").put("loadId",movementId.toString())
                        .put("source","returns-inbound").put("destination",DESTINATIONS.get(type)).put("quantity",quantity)
                        .put("priority",5).put("eligibleAt",now().toInstant().toString()).put("receiptId",id.toString()).put("classification",type);
                    Contracts.validate("movement.v1",JsonSupport.write(movement));
                    sql.execute("INSERT INTO return_movements(movement_id,site_id,receipt_id,classification,movement,payload_hash) VALUES (?,?,?,?,?::jsonb,?)",movementId,site,id,type,JsonSupport.write(movement),JsonSupport.hash(movement));
                    Events.append(sql,site,"returns-service","movement",movementId,1,"MovementRequested.v1",id,movement);
                }
                Events.append(sql,site,"returns-service","return-receipt",id,1,"ReturnReceiptRegistered.v1",id,view(sql,site,id));
                return accepted(site,id);
            });
        });
    }

    public void complete(String site,UUID movement,JsonNode command) {
        database.transaction(configuration->complete(DSL.using(configuration),site,movement,command));
    }
    void complete(DSLContext sql,String site,UUID id,JsonNode command) {
        OperationTrace.call("cutover.sorting.complete",site,id,()->{completeTraced(sql,site,id,command);return null;});
    }
    private void completeTraced(DSLContext sql,String site,UUID id,JsonNode command) {
        if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Sorting effects are paused for a checkpoint.");
        sql.fetchOne("SELECT singleton FROM admission WHERE singleton FOR UPDATE");
        var intent=sql.fetchOne("SELECT * FROM return_movements WHERE site_id=? AND movement_id=?",site,id);
        if(intent==null)throw Problem.missing();
        UUID receipt=intent.get("receipt_id",UUID.class);
        OperationTrace.annotate("cutover.receipt_id",receipt.toString());
        sql.fetchOne("SELECT receipt_id FROM receipts WHERE site_id=? AND receipt_id=? FOR UPDATE",site,receipt);
        var task=sql.fetchOne("SELECT * FROM return_tasks WHERE site_id=? AND movement_id=? FOR UPDATE",site,id);
        if(task==null)throw Problem.conflict("ASSIGNMENT_MISSING","Sorting completion requires its original adapter assignment.");
        JsonNode movement=JsonSupport.read(intent.get("movement").toString()),payload=command.path("payload"),proof=command.path("evidence");
        if(!command.path("state").asString().equals("COMPLETED") || !command.path("owner").asString().equals("returns-service")
            || !command.path("commandId").asString().equals(id.toString()) || !command.path("siteId").asString().equals(site)
            || !command.path("allocationId").asString().equals(task.get("allocation_id").toString()) || command.path("epoch").asLong(-1)!=task.get("epoch",Long.class)
            || !proof.path("state").asString().equals("COMPLETED") || !proof.path("completeHistory").asBoolean()
            || !proof.path("commandId").asString().equals(id.toString()) || !proof.path("movementId").asString().equals(id.toString())
            || !proof.path("siteId").asString().equals(site) || proof.path("executionSequence").asLong()<1
            || !proof.path("worldId").equals(payload.path("worldId")) || !proof.path("journalGeneration").equals(payload.path("journalGeneration")))
            throw Problem.conflict("UNVERIFIED_SORTING_COMPLETION","The original returns assignment needs matching terminal adapter and physical evidence.");
        for(String field:List.of("movementId","siteId","loadId","source","destination","zoneId","quantity"))
            if(!movement.path(field).equals(payload.path(field)))throw Problem.conflict("IMMUTABLE_SORTING_MOVEMENT","Sorting completion differs from the original receipt movement.");
        String type=intent.get("classification",String.class);int quantity=movement.path("quantity").asInt();
        UUID world=Database.uuid(proof,"worldId"),generation=Database.uuid(proof,"journalGeneration");long sequence=proof.path("executionSequence").asLong();
        String hash=JsonSupport.hash(Map.of("world",world,"generation",generation,"sequence",sequence,"payload",payload));
        var previous=sql.fetchOne("SELECT evidence_hash FROM sorting_ledger WHERE movement_id=?",id);
        if(previous!=null){if(!previous.get(0,String.class).equals(hash))throw Problem.conflict("SORTING_EVIDENCE_CONFLICT","A recorded sorting effect cannot change its physical evidence.");return;}
        sql.execute("INSERT INTO sorting_ledger(movement_id,site_id,receipt_id,classification,quantity,command_id,world_id,journal_generation,execution_sequence,evidence_hash,completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?::timestamptz)",id,site,receipt,type,quantity,id,world,generation,sequence,hash,now());
        sql.execute("UPDATE receipt_counts SET sorted=sorted+? WHERE receipt_id=? AND classification=?",quantity,receipt,type);
        sql.execute("UPDATE crate_counters SET sorted=sorted+? WHERE site_id=? AND classification=?",quantity,site,type);
        sql.execute("UPDATE return_movements SET state='COMPLETED' WHERE movement_id=?",id);
        sql.execute("UPDATE return_tasks SET state='COMPLETED',version=version+1,completed_at=?::timestamptz,lease_id=NULL,lease_until=NULL,last_error=NULL,transport_paused=false WHERE movement_id=?",now(),id);
        boolean outstanding=sql.fetchOne("SELECT EXISTS(SELECT 1 FROM return_movements WHERE receipt_id=? AND state<>'COMPLETED')",receipt).get(0,Boolean.class);
        boolean uncertain=sql.fetchOne("SELECT EXISTS(SELECT 1 FROM return_tasks t JOIN return_movements m USING(movement_id) WHERE m.receipt_id=? AND t.state='RECONCILIATION_REQUIRED')",receipt).get(0,Boolean.class);
        sql.execute("UPDATE receipts SET state=?,version=version+1,completed_at=CASE WHEN ? THEN NULL ELSE ?::timestamptz END WHERE receipt_id=?",outstanding?(uncertain?"RECONCILIATION_REQUIRED":"SORTING"):"COMPLETED",outstanding,now(),receipt);
        if(!outstanding)sql.execute("UPDATE admission SET active_requests=active_requests-1 WHERE singleton");
    }
    private static JsonNode accepted(String site,UUID id){return JsonSupport.MAPPER.createObjectNode().put("id",id.toString()).put("statusUrl","/api/v1/sites/"+site+"/return-receipts/"+id);}
    public JsonNode get(String site,UUID id){return view(database,site,id);}
    static JsonNode view(DSLContext sql,String site,UUID id){return Database.json(sql,"""
        SELECT jsonb_build_object('id',r.receipt_id,'siteId',r.site_id,'externalReceiptRef',r.external_ref,
          'state',r.state,'version',r.version,'createdAt',r.created_at,'completedAt',r.completed_at,'observedAt',now(),
          'counts',(SELECT jsonb_agg(jsonb_build_object('classification',classification,'received',received,'sorted',sorted) ORDER BY classification) FROM receipt_counts WHERE receipt_id=r.receipt_id),
          'movements',(SELECT COALESCE(jsonb_agg(jsonb_build_object('movementId',m.movement_id,'classification',m.classification,'state',m.state,'movement',m.movement,'taskId',t.task_id,'taskState',t.state,'taskVersion',t.version,'transportFailures',t.transport_failures,'transportPaused',t.transport_paused,'lastError',t.last_error) ORDER BY m.classification),'[]'::jsonb) FROM return_movements m LEFT JOIN return_tasks t USING(site_id,movement_id) WHERE m.receipt_id=r.receipt_id))
        FROM receipts r WHERE r.site_id=? AND r.receipt_id=?
        """,site,id);}
    public JsonNode list(String site,UUID cursor,int limit){
        if(limit<1 || limit>100)throw Problem.invalid("Page size must be between 1 and 100.");
        var after=cursor==null?null:database.select(RECEIPTS.CREATED_AT).from(RECEIPTS).where(RECEIPTS.SITE_ID.eq(site).and(RECEIPTS.RECEIPT_ID.eq(cursor))).fetchOne();
        var continuation=cursor==null?DSL.noCondition():after==null?DSL.falseCondition():RECEIPTS.CREATED_AT.lt(after.get(RECEIPTS.CREATED_AT)).or(RECEIPTS.CREATED_AT.eq(after.get(RECEIPTS.CREATED_AT)).and(RECEIPTS.RECEIPT_ID.lt(cursor)));
        var rows=database.select(RECEIPTS.RECEIPT_ID).from(RECEIPTS).where(RECEIPTS.SITE_ID.eq(site))
            .and(continuation).orderBy(RECEIPTS.CREATED_AT.desc(),RECEIPTS.RECEIPT_ID.desc()).limit(limit).fetch();
        var result=JsonSupport.MAPPER.createObjectNode().put("observedAt",clock.instant().toString());var items=result.putArray("items");
        for(var row:rows)items.add(view(database,site,row.get(0,UUID.class)));
        if(rows.size()==limit)result.put("nextCursor",rows.getLast().get(0,UUID.class).toString());else result.putNull("nextCursor");return result;
    }
    public JsonNode counters(String site){return Database.json(database,"SELECT jsonb_build_object('siteId',?,'observedAt',now(),'classifications',COALESCE(jsonb_agg(jsonb_build_object('classification',classification,'received',received,'sorted',sorted,'outstanding',received-sorted) ORDER BY classification),'[]'::jsonb)) FROM crate_counters WHERE site_id=?",site,site);}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);}
}
