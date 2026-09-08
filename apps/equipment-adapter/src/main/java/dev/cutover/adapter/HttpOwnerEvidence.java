package dev.cutover.adapter;

import dev.cutover.platform.Problem;
import dev.cutover.platform.ServiceHttp;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class HttpOwnerEvidence implements OwnerEvidencePort {
    private final Map<String, ServiceHttp> owners;

    public HttpOwnerEvidence(ServiceHttp core, ServiceHttp execution) {
        owners = Map.of("legacy-core", core, "execution-service", execution);
    }

    @Override public JsonNode read(String service, String site, String zone, JsonNode request) {
        var client = owners.get(service);
        if (client == null) throw Problem.invalid("The requested migration owner is not supported.");
        var reply = client.request("POST", "/internal/v1/sites/" + site + "/zones/" + zone + "/migration-evidence", request);
        if (reply.status() == 200) return reply.body();
        throw new ServiceHttp.Unavailable("The migration owner has not returned usable evidence.");
    }
}
