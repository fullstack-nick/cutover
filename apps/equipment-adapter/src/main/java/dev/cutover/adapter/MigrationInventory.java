package dev.cutover.adapter;

import dev.cutover.platform.*;
import java.util.*;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

/** Route-locked inventory queries. Callers hold the same route row used by command intake. */
final class MigrationInventory {
    private MigrationInventory() {}

    static JsonNode capture(DSLContext sql, String site, String zone) {
        return capture(sql, site, zone, null);
    }

    static JsonNode capture(DSLContext sql, String site, String zone, List<UUID> identities) {
        var bindings = new ArrayList<Object>(List.of(site, zone));
        String filter = "";
        if (identities != null) {
            if (identities.isEmpty() || identities.size() > 64) throw Problem.invalid("A proof page needs 1–64 movement identities.");
            filter = " AND a.movement_id IN (" + String.join(",", Collections.nCopies(identities.size(), "?")) + ")";
            bindings.addAll(identities);
        }
        var result = Database.json(sql, """
            SELECT COALESCE(jsonb_agg(item ORDER BY movement_id),'[]'::jsonb) FROM (
              SELECT a.movement_id,jsonb_build_object('movementId',a.movement_id,'allocationId',a.allocation_id,
                'siteId',a.site_id,'zoneId',a.zone_id,'owner',a.owner,'epoch',a.epoch,'state',a.state,
                'movement',a.movement,'payloadHash',a.payload_hash,
                'command',CASE WHEN c.command_id IS NULL THEN NULL ELSE jsonb_build_object(
                  'commandId',c.command_id,'allocationId',c.allocation_id,'owner',c.owner,'epoch',c.epoch,
                  'state',c.state,'payload',c.payload,'evidence',c.evidence,'attempts',c.attempts,'acceptedEver',c.accepted_ever) END,
                'cancellation',CASE WHEN a.state='CANCELLED' THEN (SELECT x.certificate
                  FROM cancellation_certificates x WHERE x.site_id=a.site_id
                    AND x.certificate->'movements' @> jsonb_build_array(jsonb_build_object('movementId',a.movement_id))
                  LIMIT 1) ELSE NULL END) AS item
              FROM movement_allocations a LEFT JOIN command_journal c ON c.site_id=a.site_id AND c.movement_id=a.movement_id
              WHERE a.site_id=? AND a.zone_id=? AND a.owner IS NOT NULL
            """ + filter + " ORDER BY a.movement_id LIMIT 20001) inventory", bindings.toArray());
        if (result.size() > 20000) throw Problem.conflict("MIGRATION_INVENTORY_LIMIT", "The retained zone inventory exceeds the declared 20,000-item migration limit.");
        return result;
    }

    static JsonNode blockers(DSLContext sql, String site, String zone) {
        var rows = sql.fetch("""
            SELECT a.movement_id,a.allocation_id,a.owner,a.epoch,a.state AS allocation_state,c.state AS command_state,
              count(*) OVER() AS blocker_count
            FROM movement_allocations a LEFT JOIN command_journal c ON c.site_id=a.site_id AND c.movement_id=a.movement_id
            WHERE a.site_id=? AND a.zone_id=? AND (a.state='ASSIGNED'
              OR (c.command_id IS NOT NULL AND c.state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION')))
            ORDER BY a.movement_id LIMIT 100
            """, site, zone);
        var result = JsonSupport.MAPPER.createObjectNode().put("count", rows.isEmpty() ? 0 : rows.getFirst().get("blocker_count", Long.class));
        var items = result.putArray("items");
        for (var row : rows) {
            String command = row.get("command_state", String.class);
            String reason = command == null ? "ALLOCATED_TASK_NOT_FINISHED"
                : Set.of("OUTCOME_UNKNOWN", "QUARANTINED").contains(command) ? command : "ALLOCATED_COMMAND_NOT_FINISHED";
            items.add(JsonSupport.MAPPER.valueToTree(Map.of("movementId", row.get("movement_id"), "allocationId", row.get("allocation_id"),
                "owner", row.get("owner") == null ? "unassigned" : row.get("owner"), "allocationState", row.get("allocation_state"),
                "commandState", command == null ? "NOT_RECORDED" : command, "reason", reason)));
        }
        return result;
    }
}
