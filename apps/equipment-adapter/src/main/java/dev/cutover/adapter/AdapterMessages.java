package dev.cutover.adapter;

import dev.cutover.platform.Events;
import dev.cutover.platform.messaging.DeliveryFailure;
import dev.cutover.platform.messaging.MessageHandler;
import java.util.Set;
import org.jooq.DSLContext;

public final class AdapterMessages implements MessageHandler {
    private static final Set<String> OBSERVATIONS = Set.of("OrderAccepted.v1", "StockReservationRecorded.v1", "OrderProgressed.v1", "ReturnReceiptRegistered.v1", "ReturnReceiptProgressed.v1");
    @Override public void apply(DSLContext sql, Events.Envelope event) {
        if (OBSERVATIONS.contains(event.eventType())) return; // Complete stream subscription still advances its cursor.
        if (!event.eventType().equals("MovementRequested.v1")) throw DeliveryFailure.permanent("UNSUPPORTED_EVENT_TYPE");
        if (!"movement".equals(event.aggregateType()) || !event.aggregateId().toString().equals(event.payload().path("movementId").asString())) throw DeliveryFailure.permanent("MOVEMENT_ID_MISMATCH");
        new Allocations(sql).register(event.siteId(), event.source(), event.payload());
    }
}
