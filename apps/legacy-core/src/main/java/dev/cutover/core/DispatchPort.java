package dev.cutover.core;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface DispatchPort {
    JsonNode allocate(String site,JsonNode movement);
    JsonNode command(String site,UUID movement);
    JsonNode equipment(String site);
    JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload);
    default JsonNode cancellation(String site,JsonNode request) { throw new dev.cutover.platform.ServiceHttp.Unavailable("Cancellation fencing is unavailable."); }
}
