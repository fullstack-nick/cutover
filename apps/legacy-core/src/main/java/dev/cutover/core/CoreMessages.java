package dev.cutover.core;

import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.messaging.DeliveryFailure;
import dev.cutover.platform.messaging.MessageHandler;
import java.time.Clock;
import java.util.Set;
import org.jooq.DSLContext;

public final class CoreMessages implements MessageHandler {
    private static final Set<String> TYPES = Set.of("MovementAssigned.v1", "CommandAccepted.v1", "MovementCompleted.v1", "CommandOutcomeUnknown.v1", "CommandRejected.v1", "ZoneOwnershipChanged.v1");
    private final Clock clock;
    public CoreMessages(Clock clock) { this.clock = clock; }
    @Override public void apply(DSLContext sql, Events.Envelope event) {
        if (!TYPES.contains(event.eventType())) throw DeliveryFailure.permanent("UNSUPPORTED_EVENT_TYPE");
        if (event.eventType().equals("ZoneOwnershipChanged.v1")) return;
        var allocation = event.payload();
        if (!"movement".equals(event.aggregateType()) || !event.aggregateId().toString().equals(allocation.path("movementId").asString())) throw DeliveryFailure.permanent("MOVEMENT_ID_MISMATCH");
        if (!"fulfilment".equals(allocation.path("movement").path("product").asString())) return;
        if (event.eventType().equals("MovementCompleted.v1")) {
            new OrderService(sql, clock).complete(event.siteId(), event.aggregateId(), allocation.required("command"));
        } else if (event.eventType().equals("MovementAssigned.v1") && "legacy-core".equals(allocation.path("owner").asString())) {
            sql.execute("UPDATE legacy_tasks SET allocation_id=?,epoch=? WHERE site_id=? AND movement_id=? AND allocation_id IS NULL", Database.uuid(allocation, "allocationId"), allocation.path("epoch").asLong(), event.siteId(), event.aggregateId());
            sql.execute("UPDATE movement_intents SET state='ASSIGNED' WHERE site_id=? AND movement_id=? AND state='REQUESTED'", event.siteId(), event.aggregateId());
        }
    }
}
