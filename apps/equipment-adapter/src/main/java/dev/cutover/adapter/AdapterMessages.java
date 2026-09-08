package dev.cutover.adapter;

import dev.cutover.platform.Events;
import dev.cutover.platform.messaging.DeliveryFailure;
import dev.cutover.platform.messaging.MessageHandler;
import java.util.Set;
import org.jooq.DSLContext;

public final class AdapterMessages implements MessageHandler {
    private final java.time.Clock clock;
    public AdapterMessages(){this(java.time.Clock.systemUTC());}
    public AdapterMessages(java.time.Clock clock){this.clock=clock;}
    private static final Set<String> OBSERVATIONS = Set.of("OrderAccepted.v1", "StockReservationRecorded.v1", "OrderProgressed.v1", "OrderCancellationRequested.v1", "OrderCancellationDenied.v1", "OrderCancelled.v1", "ReturnReceiptRegistered.v1", "ReturnReceiptProgressed.v1");
    @Override public void apply(DSLContext sql, Events.Envelope event) {
        if (OBSERVATIONS.contains(event.eventType())) return; // Complete stream subscription still advances its cursor.
        if (!event.eventType().equals("MovementRequested.v1")) throw DeliveryFailure.permanent("UNSUPPORTED_EVENT_TYPE");
        if (!"movement".equals(event.aggregateType()) || !event.aggregateId().toString().equals(event.payload().path("movementId").asString())) throw DeliveryFailure.permanent("MOVEMENT_ID_MISMATCH");
        // An inbox transaction can assign several zones. Acquire the finite route
        // set in one order before its first event takes the shared outbox budget.
        // Otherwise a later zone can wait on a command transition that already
        // owns that route and is itself waiting for this batch's budget lock.
        sql.fetch("SELECT site_id,zone_id FROM zone_routes ORDER BY site_id,zone_id FOR UPDATE");
        new Allocations(sql,clock).register(event.siteId(), event.source(), event.payload());
    }
}
