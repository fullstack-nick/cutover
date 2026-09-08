package dev.cutover.execution;

import dev.cutover.platform.*;
import java.time.Clock;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Read-only proof from this task owner. Inventory remains exclusively in the core. */
public final class MigrationEvidence {
    private final DSLContext database;
    private final Clock clock;

    public MigrationEvidence(DSLContext database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public JsonNode read(String site, String zone, JsonNode request) {
        try { Contracts.validate("migration-evidence-request.v1", JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Request at most 64 unique movement identities."); }
        if (!Set.of("ambient", "chilled").contains(zone)) throw Problem.missing();
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            var result = JsonSupport.MAPPER.createObjectNode().put("service", "execution-service")
                .put("siteId", site).put("zoneId", zone).put("observedAt", clock.instant().toString());
            var items = result.putArray("items");
            for (var identity : request.path("movementIds")) {
                UUID id = UUID.fromString(identity.asString());
                var row = sql.fetchOne("""
                    SELECT jsonb_build_object('taskId',task_id,'movementId',movement_id,'state',state,
                      'allocationId',allocation_id,'owner','execution-service','epoch',epoch,'version',version,
                      'payloadHash',payload_hash,'movement',movement)
                    FROM execution_tasks WHERE site_id=? AND zone_id=? AND movement_id=?
                    """, site, zone, id);
                var item = items.addObject().put("movementId", id.toString());
                if (row == null) item.put("missing", true).putNull("task");
                else item.set("task", JsonSupport.read(row.get(0).toString()));
            }
            return result;
        });
    }
}
