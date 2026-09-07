package dev.cutover.platform.messaging;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Idempotency;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Repair always runs the original bytes through current authentication/contract/inbox rules. */
public final class QuarantineOperations {
    private final DSLContext database;
    private final MessageHandler handler;
    private final MessageSubscription subscription;
    private final Clock clock;
    public QuarantineOperations(DSLContext database,MessageHandler handler,MessageSubscription subscription,Clock clock) {
        this.database=database;this.handler=handler;this.subscription=subscription;this.clock=clock;
    }
    public JsonNode reprocess(String actor,String site,UUID id,String key,JsonNode request) {
        if(!subscription.sites().contains(site))throw Problem.missing();
        try {Contracts.validate("reconciliation-request.v1",JsonSupport.write(request));}
        catch(IllegalArgumentException invalid){throw Problem.invalid("Provide the quarantine version and a reason of 8–500 characters.");}
        String reason=request.path("reason").asString().trim();
        if(reason.length()<8)throw Problem.invalid("Explain the consumer or dependency correction before reprocessing.");
        return database.transactionResult(configuration->{
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"reprocess-quarantine",key,Map.of("deliveryId",id,"request",request),()->{
                if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Quarantine recovery is paused for the checkpoint.");
                sql.fetchOne("SELECT singleton FROM message_storage WHERE singleton FOR UPDATE");
                var row=sql.fetchOne("SELECT * FROM delivery_quarantine WHERE delivery_id=? AND (site_id=? OR site_id IS NULL) FOR UPDATE",id,site);
                if(row==null)throw Problem.missing();
                var receiver=new DurableInbox(sql,handler,clock,subscription.sources(),Set.of(site));
                byte[] bytes=row.get("raw_body",byte[].class);
                if(bytes==null || !site.equals(receiver.trustedSite(row.get("received_exchange",String.class),bytes)))throw Problem.missing();
                long before=row.get("version",Long.class);
                if(before!=request.path("expectedVersion").asLong())throw Problem.conflict("VERSION_CONFLICT","The quarantine entry changed; inspect its latest state.");
                if(!"QUARANTINED".equals(row.get("state",String.class)))throw Problem.conflict("DELIVERY_STATE","The original delivery has already transferred to the durable inbox.");
                UUID received=receiver.receive(row.get("received_exchange",String.class),row.get("transport_message_id",String.class),bytes);
                boolean transferred=sql.fetchExists(sql.selectOne().from("inbox").where("event_id=? AND site_id=?",received,site));
                String outcome=transferred?"TRANSFERRED":"QUARANTINED";
                sql.execute("UPDATE delivery_quarantine SET site_id=?,state=?,version=version+1,reprocess_attempts=reprocess_attempts+1,transferred_event_id=?,transferred_at=CASE WHEN ? THEN now() ELSE NULL END WHERE delivery_id=?",site,outcome,transferred?received:null,transferred,id);
                if(transferred)sql.execute("UPDATE message_storage SET active_messages=active_messages-1,active_bytes=active_bytes-? WHERE singleton",row.get("payload_bytes"));
                var response=JsonSupport.MAPPER.valueToTree(Map.of("deliveryId",id,"state",outcome,"version",before+1));
                sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,'reprocess-quarantine',?,?,?,?,?,?::jsonb)",UUID.randomUUID(),site,actor,id.toString(),reason,before,before+1,outcome,JsonSupport.write(response));
                return response;
            });
        });
    }
    public JsonNode status(String site) {
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('deliveryId',delivery_id,'reason',reason,'state',state,'version',version,'attempts',reprocess_attempts,'payloadBytes',payload_bytes,'bodySha256',body_hash,'receivedAt',received_at,'transferredEventId',transferred_event_id)),'[]'::jsonb) FROM (SELECT * FROM delivery_quarantine WHERE site_id=? ORDER BY received_at,delivery_id LIMIT 100) q",site);
    }
    public JsonNode untrustedDiagnostics() {
        // No raw payload, source, transport ID or claimed business identity is exposed across site scopes.
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('deliveryId',delivery_id,'reason',reason,'payloadBytes',payload_bytes,'bodySha256',body_hash,'receivedAt',received_at)),'[]'::jsonb) FROM (SELECT * FROM delivery_quarantine WHERE site_id IS NULL ORDER BY received_at,delivery_id LIMIT 100) q");
    }
}
