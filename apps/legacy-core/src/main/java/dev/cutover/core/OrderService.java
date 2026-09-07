package dev.cutover.core;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.Idempotency;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import static dev.cutover.generated.legacy_core.Tables.*;

public final class OrderService {
    private final DSLContext database;
    private final java.time.Clock clock;
    public OrderService(DSLContext database,java.time.Clock clock) { this.database=database;this.clock=clock; }
    public record Line(String sku,int quantity) {}
    public record Request(String sourceSystem,String externalOrderRef,String storeId,int priority,List<Line> lines) {}

    public JsonNode accept(String caller,String site,String key,JsonNode body) {
        try { Contracts.validate("order-request.v1",JsonSupport.write(body)); }
        catch(IllegalArgumentException invalid) { throw Problem.invalid(invalid.getMessage()); }
        Request request=JsonSupport.MAPPER.treeToValue(body,Request.class);
        if(new HashSet<>(request.lines().stream().map(Line::sku).toList()).size()!=request.lines().size()) throw Problem.invalid("An order cannot contain duplicate SKU lines.");
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,caller,site,"create-order",key,request,()-> {
                Database.lock(sql,"external-order",site,request.sourceSystem(),request.externalOrderRef());
                var old=sql.fetchOne("SELECT order_id,payload_hash FROM orders WHERE site_id= ? AND source_system= ? AND external_ref= ?",site,request.sourceSystem(),request.externalOrderRef());
                if(old!=null) {
                    if(!JsonSupport.hash(request).equals(old.get("payload_hash",String.class))) throw Problem.conflict("ORDER_REFERENCE_CONFLICT","This external order reference already has different contents.");
                    return accepted(site,old.get("order_id",UUID.class));
                }
                Database.requireDurability(sql,true);
                var quota=sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
                if(quota.get("active_requests",Integer.class)>=800 || quota.get("unpublished_events",Integer.class)>=8000 || quota.get("unpublished_bytes",Long.class)>=53687091)
                    throw new Problem(503,"ADMISSION_CAPACITY","Accepted work is near the configured capacity; retry after backlog recovery.");
                if(!sql.fetchExists(STORES, STORES.SITE_ID.eq(site).and(STORES.STORE_ID.eq(request.storeId())))) throw Problem.invalid("Unknown store for this site.");
                for(Line line:request.lines()) if(!sql.fetchExists(PRODUCTS, PRODUCTS.SITE_ID.eq(site).and(PRODUCTS.SKU.eq(line.sku())))) throw Problem.invalid("Unknown SKU for this site.");
                UUID id=UUID.randomUUID();
                sql.execute("UPDATE admission SET active_requests=active_requests+1 WHERE singleton");
                sql.execute("INSERT INTO orders(order_id,site_id,source_system,external_ref,payload_hash,store_id,priority,state,created_at) VALUES (?,?,?,?,?,?,?,'ACCEPTED',?::timestamptz)",id,site,request.sourceSystem(),request.externalOrderRef(),JsonSupport.hash(request),request.storeId(),request.priority(),java.time.OffsetDateTime.ofInstant(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),java.time.ZoneOffset.UTC));
                for(Line line:request.lines()) sql.execute("INSERT INTO order_lines(order_id,site_id,sku,requested) VALUES (?,?,?,?)",id,site,line.sku(),line.quantity());
                Events.append(sql,site,"legacy-core","order",id,1,"OrderAccepted.v1",id,accepted(site,id));
                sql.fetch("SELECT reserve_order(?)",id);
                Events.append(sql,site,"legacy-core","order",id,2,"StockReservationRecorded.v1",id,view(sql,site,id));
                for(var row:sql.fetch("SELECT movement_id,movement FROM movement_intents WHERE order_id= ?",id))
                    Events.append(sql,site,"legacy-core","movement",row.get("movement_id",UUID.class),1,"MovementRequested.v1",id,JsonSupport.read(row.get("movement").toString()));
                if(sql.fetchOne("SELECT state FROM orders WHERE order_id= ?",id).get(0,String.class).equals("SHORTAGE")) sql.execute("UPDATE admission SET active_requests=active_requests-1 WHERE singleton");
                return accepted(site,id);
            });
        });
    }

    /** Only a verified adapter completion reaches this method, through the worker or authorized event consumer. */
    public void complete(String site,UUID movementId,JsonNode command) {
        if(!"COMPLETED".equals(command.path("state").asString()) || !movementId.toString().equals(command.path("commandId").asString())
                || !site.equals(command.path("siteId").asString()) || command.path("evidence").path("executionSequence").asLong(0)<1)
            throw Problem.conflict("UNVERIFIED_COMPLETION","A movement requires verified adapter and simulator completion.");
        database.transaction(configuration -> {
            var sql=DSL.using(configuration);
            if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Inventory mutation is paused for the checkpoint.");
            sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
            var reservation=sql.fetchOne("SELECT * FROM reservations WHERE site_id= ? AND reservation_id= ? FOR UPDATE",site,movementId);
            if(reservation==null) throw Problem.missing();
            if("CONSUMED".equals(reservation.get("state",String.class))) return;
            if(!"RESERVED".equals(reservation.get("state",String.class))) throw Problem.conflict("RESERVATION_STATE","A released reservation cannot be consumed.");
            int quantity=reservation.get("quantity",Integer.class);
            if(command.path("payload").path("quantity").asInt()!=quantity) throw Problem.conflict("MOVEMENT_QUANTITY","Completion quantity differs from the reserved movement.");
            UUID order=reservation.get("order_id",UUID.class);
            sql.execute("UPDATE stock SET on_hand=on_hand-?,reserved=reserved-?,version=version+1 WHERE site_id= ? AND sku= ?",quantity,quantity,site,reservation.get("sku"));
            sql.execute("INSERT INTO inventory_ledger(movement_id,site_id,reservation_id,sku,quantity,command_id,simulator_world_id,execution_sequence) VALUES (?,?,?,?,?,?,?,?)",
                    movementId,site,movementId,reservation.get("sku"),quantity,Database.uuid(command,"commandId"),Database.uuid(command.path("evidence"),"worldId"),command.path("evidence").path("executionSequence").asLong());
            sql.execute("UPDATE reservations SET state='CONSUMED' WHERE reservation_id= ?",movementId);
            sql.execute("UPDATE movement_intents SET state='COMPLETED' WHERE movement_id= ? AND site_id= ?",movementId,site);
            sql.execute("UPDATE legacy_tasks SET state='COMPLETED',version=version+1,lease_until=NULL,last_error=NULL WHERE movement_id= ? AND site_id= ? AND state<>'COMPLETED'",movementId,site);
            boolean outstanding=sql.fetchOne("SELECT EXISTS(SELECT 1 FROM reservations WHERE order_id= ? AND state='RESERVED')",order).get(0,Boolean.class);
            boolean shortage=sql.fetchOne("SELECT EXISTS(SELECT 1 FROM order_lines WHERE order_id= ? AND shortage>0)",order).get(0,Boolean.class);
            String state=outstanding?"IN_PROGRESS":shortage?"COMPLETED_WITH_SHORTAGE":"COMPLETED";
            long version=sql.fetchOne("UPDATE orders SET state= ?,version=version+1,completed_at=CASE WHEN ? THEN completed_at ELSE now() END WHERE order_id= ? RETURNING version",state,outstanding,order).get(0,Long.class);
            if(!outstanding) sql.execute("UPDATE admission SET active_requests=active_requests-1 WHERE singleton");
            Events.append(sql,site,"legacy-core","order",order,version,"OrderProgressed.v1",order,view(sql,site,order));
        });
    }
    private JsonNode accepted(String site,UUID id) { return JsonSupport.MAPPER.valueToTree(Map.of("id",id,"statusUrl","/api/v1/sites/"+site+"/orders/"+id)); }
    public JsonNode get(String site,UUID id) {
        var result=(tools.jackson.databind.node.ObjectNode)view(database,site,id);
        result.set("cancellation",Database.json(database,"SELECT COALESCE((SELECT jsonb_build_object('cancellationId',cancellation_id,'state',state,'version',version,'attempts',attempts,'lastError',last_error,'createdAt',created_at,'finishedAt',finished_at) FROM order_cancellations WHERE site_id=? AND order_id=? ORDER BY created_at DESC,cancellation_id LIMIT 1),'null'::jsonb)",site,id));
        return result;
    }
    static JsonNode view(DSLContext sql,String site,UUID id) {
        return Database.json(sql,"SELECT jsonb_build_object('id',o.order_id,'siteId',o.site_id,'externalOrderRef',o.external_ref,'storeId',o.store_id,'priority',o.priority,'state',o.state,'version',o.version,'createdAt',o.created_at,'completedAt',o.completed_at,'observedAt',now(),'lines',(SELECT COALESCE(jsonb_agg(jsonb_build_object('sku',l.sku,'requested',l.requested,'reserved',l.reserved_quantity,'shortage',l.shortage) ORDER BY l.sku),'[]'::jsonb) FROM order_lines l WHERE l.order_id=o.order_id),'movements',(SELECT COALESCE(jsonb_agg(jsonb_build_object('movementId',m.movement_id,'state',m.state,'movement',m.movement) ORDER BY m.movement_id),'[]'::jsonb) FROM movement_intents m WHERE m.order_id=o.order_id)) FROM orders o WHERE o.site_id= ? AND o.order_id= ?",site,id);
    }
    public JsonNode list(String site,UUID cursor,int limit) {
        if(limit<1 || limit>100) throw Problem.invalid("Page size must be between 1 and 100.");
        var rows=database.select(ORDERS.ORDER_ID).from(ORDERS).where(ORDERS.SITE_ID.eq(site))
                .and(cursor == null ? DSL.noCondition() : ORDERS.ORDER_ID.gt(cursor)).orderBy(ORDERS.ORDER_ID).limit(limit).fetch();
        var items=JsonSupport.MAPPER.createArrayNode();for(UUID id:rows.getValues("order_id",UUID.class)) items.add(get(site,id));
        var page=JsonSupport.MAPPER.createObjectNode();page.set("items",items);
        if(rows.size()==limit)page.put("nextCursor",rows.getLast().get("order_id").toString());else page.putNull("nextCursor");
        page.put("observedAt",java.time.Instant.now().toString());return page;
    }
}
