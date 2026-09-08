package dev.cutover.returns;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The product receives assignments through its inbox and observes only its returns zone. */
public interface DispatchPort {
    JsonNode context(String site,List<UUID> movements);
    JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode intent);
}
