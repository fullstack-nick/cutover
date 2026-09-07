package dev.cutover.adapter;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Fences the entire order atomically before the inventory owner may release a reservation. */
public final class CancellationGate {
    private final DSLContext database;
    private final EquipmentObservations observations;
    private final Clock clock;
    public CancellationGate(DSLContext database,EquipmentObservations observations,Clock clock) {
        this.database=database;this.observations=observations;this.clock=clock;
    }
    public JsonNode fence(String site,String client,JsonNode request) {
        if (!"legacy-core".equals(client)) throw new Problem(403,"CANCELLATION_IDENTITY","Only the inventory owner can request an outbound cancellation fence.");
        try { Contracts.validate("cancellation-fence-request.v1",JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Invalid cancellation fence request."); }
        if (!site.equals(request.path("siteId").asString())) throw Problem.missing();
        UUID cancellation=Database.uuid(request,"cancellationId"),order=Database.uuid(request,"orderId");
        var movements=new ArrayList<JsonNode>();var ids=new HashSet<UUID>();
        for (JsonNode movement:request.path("movements")) {
            try { Contracts.validate("movement.v1",JsonSupport.write(movement)); }
            catch (IllegalArgumentException invalid) { throw Problem.invalid("Invalid movement in the cancellation inventory."); }
            if (!site.equals(movement.path("siteId").asString())) throw Problem.missing();
            if (!"fulfilment".equals(movement.path("product").asString()) || !Set.of("ambient","chilled").contains(movement.path("zoneId").asString())
                    || !ids.add(Database.uuid(movement,"movementId"))) throw Problem.invalid("Cancellation requires unique outbound movements from the same site.");
            movements.add(movement);
        }
        movements.sort(Comparator.comparing(item->item.path("movementId").asString()));
        String hash=JsonSupport.hash(request);
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            if (!Database.workersMayWrite(sql) || sql.fetchOne("SELECT critical_storage FROM service_control WHERE singleton").get(0,Boolean.class))
                throw new Problem(503,"DURABILITY_PAUSED","Cancellation cannot change a frozen or critically full journal.");
            Database.lock(sql,"order-cancellation",site,order);
            var previous=sql.fetchOne("SELECT * FROM cancellation_certificates WHERE site_id=? AND (order_id=? OR cancellation_id=?)",site,order,cancellation);
            if (previous!=null) {
                if (!hash.equals(previous.get("request_hash",String.class))) throw Problem.conflict("CANCELLATION_CONFLICT","The existing cancellation fence cannot change its inventory or request identity.");
                return JsonSupport.read(previous.get("certificate").toString());
            }
            // Registration, command intake, cancellation and ownership changes lock routes first.
            var routes=new TreeMap<String,Record>();
            for (JsonNode movement:movements) routes.put(movement.path("zoneId").asString(),null);
            for (String zone:routes.keySet()) {
                var route=sql.fetchOne("SELECT * FROM zone_routes WHERE site_id=? AND zone_id=? FOR UPDATE",site,zone);
                if (route==null) throw Problem.missing();
                if ("RECONCILIATION_REQUIRED".equals(route.get("state",String.class))) throw Problem.conflict("ROUTE_UNCERTAIN","The route must be reconciled before stock can be released.");
                routes.put(zone,route);
            }
            JsonNode world=observations.current(sql);
            // Preflight the whole inventory before creating any cancellation tombstone.
            for (JsonNode movement:movements) {
                UUID id=Database.uuid(movement,"movementId");Database.lock(sql,"movement",site,id);
                var allocation=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id=? AND movement_id=? FOR UPDATE",site,id);
                if (allocation!=null && (!JsonSupport.hash(movement).equals(allocation.get("payload_hash",String.class))
                        || !"legacy-core".equals(allocation.get("source",String.class)))) throw Problem.conflict("IMMUTABLE_MOVEMENT","Cancellation must match the retained movement inventory.");
                if (allocation!=null && Set.of("COMPLETED","CANCELLED").contains(allocation.get("state",String.class)))
                    throw Problem.conflict("MOVEMENT_STARTED","An already terminal movement cannot join another cancellation.");
                var command=sql.fetchOne("SELECT * FROM command_journal WHERE site_id=? AND movement_id=? FOR UPDATE",site,id);
                if (command!=null) {
                    OffsetDateTime lease=command.get("lease_until",OffsetDateTime.class);
                    if (command.get("attempts",Integer.class)>0 || command.get("accepted_ever",Boolean.class)
                            || command.get("evidence")!=null || !Set.of("RECORDED","SEND_PENDING").contains(command.get("state",String.class))
                            || (lease!=null && lease.isAfter(now())))
                        throw Problem.conflict("MOVEMENT_STARTED_OR_UNKNOWN","A submitted, investigating or uncertain command prevents cancellation of the entire order.");
                }
            }
            var proof=JsonSupport.MAPPER.createArrayNode();
            for (JsonNode movement:movements) {
                UUID id=Database.uuid(movement,"movementId");String zone=movement.path("zoneId").asString();
                Record route=routes.get(zone);
                sql.execute("INSERT INTO movement_allocations(allocation_id,movement_id,site_id,zone_id,source,movement,payload_hash,owner,epoch,state,version,completed_at) VALUES (?,?,?,?,'legacy-core',?::jsonb,?,?,?,'CANCELLED',1,?::timestamptz) ON CONFLICT(site_id,movement_id) DO UPDATE SET state='CANCELLED',version=movement_allocations.version+1,completed_at=EXCLUDED.completed_at",
                        UUID.randomUUID(),id,site,zone,JsonSupport.write(movement),JsonSupport.hash(movement),route.get("owner"),route.get("epoch"),now());
                sql.execute("UPDATE command_journal SET state='REJECTED_BEFORE_EXECUTION',version=version+1,lease_until=NULL,last_error='CANCELLED_BEFORE_SUBMISSION' WHERE site_id=? AND movement_id=?",site,id);
                JsonNode cancelled=Allocations.view(sql,site,id);
                Events.append(sql,site,"equipment-adapter","movement",id,cancelled.path("version").asLong(),"MovementCancelled.v1",cancellation,cancelled);
                proof.add(JsonSupport.MAPPER.valueToTree(Map.of("movementId",id,"allocationId",cancelled.path("allocationId").asString(),"allocationVersion",cancelled.path("version").asLong(),"proof","NEVER_SUBMITTED")));
            }
            var certificate=JsonSupport.MAPPER.createObjectNode();
            certificate.put("cancellationId",cancellation.toString());certificate.put("orderId",order.toString());certificate.put("siteId",site);certificate.put("state","FENCED");
            certificate.set("worldId",world.required("worldId"));certificate.set("journalGeneration",world.required("journalGeneration"));
            certificate.set("movements",proof);certificate.put("proofHash",JsonSupport.hash(proof));certificate.put("issuedAt",now().toString());
            sql.execute("INSERT INTO cancellation_certificates(cancellation_id,site_id,order_id,request_hash,request,certificate) VALUES (?,?,?,?,?::jsonb,?::jsonb)",cancellation,site,order,hash,JsonSupport.write(request),JsonSupport.write(certificate));
            sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,'cancellation-fence',?,?,0,1,'FENCED',?::jsonb)",
                    UUID.randomUUID(),site,client,cancellation.toString(),request.path("reason").asString(),JsonSupport.write(certificate));
            return JsonSupport.read(JsonSupport.write(certificate));
        });
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
}
