package dev.cutover.adapter;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Synthetic equipment boundary; a transport failure never proves non-execution. */
public interface EquipmentPort {
    record Reply(int status,JsonNode body) {}
    JsonNode equipment();
    Reply command(UUID commandId);
    Reply send(UUID commandId,JsonNode immutableCommand);
    final class Unavailable extends RuntimeException {
        public Unavailable(String message,Throwable cause) { super(message,cause); }
        public Unavailable(String message) { super(message); }
    }
}
