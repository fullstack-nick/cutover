package dev.cutover.core;

import dev.cutover.platform.*;
import java.time.Clock;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Read-only evidence from the inventory owner; no adapter or execution database access. */
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
            var result = JsonSupport.MAPPER.createObjectNode().put("service", "legacy-core")
                .put("siteId", site).put("zoneId", zone).put("observedAt", clock.instant().toString());
            result.put("assignmentBoundary",sql.fetchOne("SELECT to_regclass('legacy_task_boundary') IS NOT NULL AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='reservations'::regclass AND tgname='reservation_creates_movement_intent') AND NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='reservations'::regclass AND tgname='reservation_creates_legacy_task')").get(0,Boolean.class));
            var items = result.putArray("items");
            for (var identity : request.path("movementIds")) {
                UUID id = UUID.fromString(identity.asString());
                var row = sql.fetchOne("""
                    SELECT jsonb_build_object('movementId',m.movement_id,'movement',m.movement,'state',m.state,
                      'reservation',jsonb_build_object('state',r.state,'quantity',r.quantity),
                      'inventoryEffect',(SELECT jsonb_build_object('movementId',l.movement_id,'quantity',l.quantity,
                        'commandId',l.command_id,'worldId',l.simulator_world_id,'executionSequence',l.execution_sequence)
                        FROM inventory_ledger l WHERE l.site_id=m.site_id AND l.movement_id=m.movement_id),
                      'task',(SELECT jsonb_build_object('taskId',t.task_id,'movementId',t.movement_id,'state',t.state,
                        'allocationId',t.allocation_id,'owner',t.owner,'epoch',t.epoch,'version',t.version)
                        FROM legacy_tasks t WHERE t.site_id=m.site_id AND t.movement_id=m.movement_id))
                    FROM movement_intents m JOIN reservations r ON r.site_id=m.site_id AND r.reservation_id=m.reservation_id
                    WHERE m.site_id=? AND m.movement_id=? AND m.movement->>'zoneId'=?
                    """, site, id, zone);
                if (row == null) items.addObject().put("movementId", id.toString()).put("missing", true).putNull("task");
                else items.add(JsonSupport.read(row.get(0).toString()));
            }
            return result;
        });
    }
}
