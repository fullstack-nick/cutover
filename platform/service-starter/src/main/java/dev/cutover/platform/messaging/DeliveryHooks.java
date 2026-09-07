package dev.cutover.platform.messaging;

import java.util.UUID;

/** Fault checkpoints are outside database transactions at deliberately named durability boundaries. */
@FunctionalInterface
public interface DeliveryHooks {
    DeliveryHooks NONE = (checkpoint, eventId) -> {};
    void reached(String checkpoint, UUID eventId);
}
