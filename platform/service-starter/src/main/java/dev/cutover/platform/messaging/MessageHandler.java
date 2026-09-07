package dev.cutover.platform.messaging;

import dev.cutover.platform.Events;
import org.jooq.DSLContext;

/** Each owner supplies its own business behavior. The supplied transaction owns inbox and effects. */
public interface MessageHandler {
    void apply(DSLContext transaction, Events.Envelope event);
}
