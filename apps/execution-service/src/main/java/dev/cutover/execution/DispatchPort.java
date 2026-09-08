package dev.cutover.execution;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Assignment is consumed from the adapter stream. This port cannot originate allocations. */
public interface DispatchPort {
    JsonNode context(String site,String zone,List<UUID> movements);
    JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload);
}
