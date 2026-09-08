package dev.cutover.core;

import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.Contracts;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.DeliveryFailure;
import dev.cutover.platform.messaging.MessageHandler;
import java.time.Clock;
import java.util.Set;
import org.jooq.DSLContext;

public final class CoreMessages implements MessageHandler {
    private static final Set<String> TYPES = Set.of("MovementAssigned.v1", "MovementCancelled.v1", "CommandAccepted.v1", "MovementCompleted.v1", "CommandOutcomeUnknown.v1", "CommandRejected.v1", "ZoneOwnershipChanged.v1");
    private final Clock clock;
    public CoreMessages(Clock clock) { this.clock = clock; }
    @Override public void apply(DSLContext sql, Events.Envelope event) {
        if (!TYPES.contains(event.eventType())) throw DeliveryFailure.permanent("UNSUPPORTED_EVENT_TYPE");
        if (event.eventType().equals("ZoneOwnershipChanged.v1")) return;
        var allocation = event.payload();
        if (!"movement".equals(event.aggregateType()) || !event.aggregateId().toString().equals(allocation.path("movementId").asString())) throw DeliveryFailure.permanent("MOVEMENT_ID_MISMATCH");
        if (!"fulfilment".equals(allocation.path("movement").path("product").asString())) return;
        var movement=allocation.required("movement");
        try { Contracts.validate("movement.v1",JsonSupport.write(movement)); }
        catch(IllegalArgumentException invalid) { throw DeliveryFailure.permanent("MOVEMENT_CONTRACT"); }
        if(!event.siteId().equals(movement.path("siteId").asString()) || !event.siteId().equals(allocation.path("siteId").asString()) || !event.aggregateId().toString().equals(movement.path("movementId").asString()))throw DeliveryFailure.permanent("MOVEMENT_ID_MISMATCH");
        var intent=sql.fetchOne("SELECT m.*,o.priority,o.created_at AS eligible_at FROM movement_intents m JOIN orders o ON o.order_id=m.order_id WHERE m.site_id=? AND m.movement_id=?",event.siteId(),event.aggregateId());
        if(intent==null)throw DeliveryFailure.pending("MOVEMENT_CONTEXT_MISSING");
        if(!JsonSupport.hash(JsonSupport.read(intent.get("movement").toString())).equals(JsonSupport.hash(movement)))throw DeliveryFailure.permanent("IMMUTABLE_MOVEMENT_INTENT");
        if (event.eventType().equals("MovementCompleted.v1")) {
            new OrderService(sql, clock).complete(event.siteId(), event.aggregateId(), allocation.required("command"));
        } else if (event.eventType().equals("MovementAssigned.v1")) {
            var current=sql.fetchOne("SELECT state FROM movement_intents WHERE site_id=? AND movement_id=? FOR UPDATE",event.siteId(),event.aggregateId());
            if(Set.of("COMPLETED","CANCELLED").contains(current.get("state",String.class)))return;
            if(!allocation.path("state").asString().equals("ASSIGNED") || !Set.of("legacy-core","execution-service").contains(allocation.path("owner").asString())
                    || !allocation.path("epoch").isIntegralNumber() || allocation.path("epoch").asLong()<0)throw DeliveryFailure.permanent("INVALID_TASK_ASSIGNMENT");
            sql.execute("UPDATE movement_intents SET state='ASSIGNED' WHERE site_id=? AND movement_id=? AND state='REQUESTED'", event.siteId(), event.aggregateId());
            if(!allocation.path("owner").asString().equals("legacy-core"))return;
            var allocationId=Database.uuid(allocation,"allocationId");long epoch=allocation.path("epoch").asLong();
            var task=sql.fetchOne("SELECT * FROM legacy_tasks WHERE site_id=? AND movement_id=? FOR UPDATE",event.siteId(),event.aggregateId());
            if(task!=null){
                if((task.get("allocation_id")!=null && !task.get("allocation_id").equals(allocationId)) || (task.get("epoch")!=null && task.get("epoch",Long.class)!=epoch))throw DeliveryFailure.permanent("IMMUTABLE_TASK_ASSIGNMENT");
                sql.execute("UPDATE legacy_tasks SET allocation_id=?,epoch=? WHERE site_id=? AND movement_id=? AND (allocation_id IS NULL OR epoch IS NULL)",allocationId,epoch,event.siteId(),event.aggregateId());
            }else{
                sql.execute("INSERT INTO legacy_tasks(site_id,movement_id,order_id,zone_id,priority,eligible_at,next_attempt_at,allocation_id,epoch) VALUES (?,?,?,?,legacy_priority(?),?::timestamptz,?::timestamptz,?,?)",event.siteId(),event.aggregateId(),intent.get("order_id"),movement.path("zoneId").asString(),intent.get("priority"),intent.get("eligible_at"),intent.get("eligible_at"),allocationId,epoch);
            }
        }
    }
}
