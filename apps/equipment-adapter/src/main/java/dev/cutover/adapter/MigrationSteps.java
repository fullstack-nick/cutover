package dev.cutover.adapter;

import dev.cutover.platform.*;
import dev.cutover.platform.messaging.RetryDelay;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** One bounded page or phase per lease. No remote call runs inside the adapter transaction. */
final class MigrationSteps {
    private static final class StaleLease extends RuntimeException {}
    private final DSLContext database;
    private final OwnerEvidencePort owners;
    private final EquipmentObservations equipment;
    private final Clock clock;
    private final MigrationSessions.CheckpointHook hooks;

    MigrationSteps(DSLContext database, OwnerEvidencePort owners, EquipmentObservations equipment,
                   Clock clock, MigrationSessions.CheckpointHook hooks) {
        this.database = database; this.owners = owners; this.equipment = equipment; this.clock = clock; this.hooks = hooks;
    }

    int poll() {
        if (!database.fetchOne("SELECT EXISTS(SELECT 1 FROM migration_sessions WHERE phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING') AND NOT transport_paused AND last_error IS DISTINCT FROM 'OBSERVATION_LATENCY_TARGET_MISSED' AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz))", now(), now()).get(0, Boolean.class)) return 0;
        var sessions = database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return sql.fetch("SELECT * FROM migration_sessions WHERE false");
            var rows = sql.fetch("""
                SELECT * FROM migration_sessions
                WHERE phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING') AND NOT transport_paused
                  AND last_error IS DISTINCT FROM 'OBSERVATION_LATENCY_TARGET_MISSED'
                  AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz)
                ORDER BY created_at,session_id LIMIT 2 FOR UPDATE SKIP LOCKED
                """, now(), now());
            for (var row : rows) {
                UUID lease = UUID.randomUUID(); row.set(DSL.field("lease_id", UUID.class), lease);
                sql.execute("UPDATE migration_sessions SET lease_id=?,lease_until=?::timestamptz WHERE session_id=?", lease, now().plusSeconds(60), row.get("session_id"));
            }
            return rows;
        });
        for (var session : sessions) {
            try {
                switch (session.get("phase", String.class)) {
                    case "DRAINING" -> drain(session);
                    case "RECONCILING" -> reconcile(session);
                    case "READY_TO_SWITCH" -> switchOwner(session);
                    case "OBSERVING" -> observe(session);
                    default -> throw new StaleLease();
                }
            } catch (StaleLease ignored) {
                // A concurrent supervisor or a newer worker owns the current state.
            } catch (MigrationProofs.Pending pending) {
                defer(session, pending.code, pending.movementId, false);
            } catch (ServiceHttp.Unavailable unavailable) {
                defer(session, "OWNER_EVIDENCE_UNAVAILABLE", null, true);
            } catch (Problem problem) {
                defer(session, problem.code(), null, problem.status() == 503);
            }
        }
        return sessions.size();
    }

    private void drain(Record leased) {
        boolean advanced = database.transactionResult(configuration -> {
            var sql = DSL.using(configuration); var session = lock(sql, leased);
            var blockers = MigrationInventory.blockers(sql, site(session), zone(session));
            if (blockers.path("count").asLong() > 0) {
                postpone(sql, session, blockers, "DRAIN_BLOCKED", false); return false;
            }
            var inventory = MigrationInventory.capture(sql, site(session), zone(session));
            MigrationSessions.reserveEvidence(sql, inventory);
            sql.execute("""
                UPDATE migration_sessions SET phase='RECONCILING',inventory=?::jsonb,inventory_hash=?,inventory_count=?,
                  verified_count=0,blockers='{"count":0,"items":[]}',last_error=NULL,attempts=0,version=version+1,
                  next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL WHERE session_id=?
                """, JsonSupport.write(inventory), JsonSupport.hash(inventory), inventory.size(), now(), id(session));
            MigrationSessions.audit(sql, session, "migration-inventory-captured", "RECONCILING", version(session),
                JsonSupport.MAPPER.createObjectNode().put("inventoryHash", JsonSupport.hash(inventory)).put("count", inventory.size()));
            return true;
        });
        if (advanced) hooks.reached("RECONCILING", id(leased));
    }

    private void reconcile(Record leased) {
        JsonNode inventory = json(leased, "inventory");
        int offset = leased.get("verified_count", Integer.class);
        if (offset == inventory.size()) { checkpoint(leased); return; }
        var page = JsonSupport.MAPPER.createArrayNode();
        for (int index = offset; index < Math.min(offset + 64, inventory.size()); index++) page.add(inventory.get(index));
        var proof = ownerProof(leased, page);
        database.transaction(configuration -> {
            var sql = DSL.using(configuration); var session = lock(sql, leased);
            if (session.get("verified_count", Integer.class) != offset) throw new StaleLease();
            var current = MigrationInventory.capture(sql, site(session), zone(session), identities(page));
            if (!JsonSupport.hash(current).equals(JsonSupport.hash(page))) throw new MigrationProofs.Pending("MIGRATION_INVENTORY_CHANGED", page.get(0).path("movementId").asString());
            MigrationSessions.reserveEvidence(sql, proof);
            sql.execute("INSERT INTO migration_proof_chunks(session_id,first_index,item_count,proof_hash,proof) VALUES (?,?,?,?,?::jsonb)", id(session), offset, page.size(), JsonSupport.hash(proof), JsonSupport.write(proof));
            sql.execute("""
                UPDATE migration_sessions SET verified_count=?,blockers='{"count":0,"items":[]}',last_error=NULL,
                  attempts=0,version=version+1,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL WHERE session_id=?
                """, offset + page.size(), now(), id(session));
        });
    }

    private void checkpoint(Record leased) {
        var request=JsonSupport.MAPPER.createObjectNode();request.putArray("movementIds");
        var capability=owners.read("legacy-core",site(leased),zone(leased),request);
        if(!capability.path("service").asString().equals("legacy-core") || !capability.path("siteId").asString().equals(site(leased))
            || !capability.path("zoneId").asString().equals(zone(leased)) || !capability.path("assignmentBoundary").asBoolean())
            throw new MigrationProofs.Pending("TASK_CREATION_BOUNDARY_REQUIRED", "");
        database.transaction(configuration -> {
            var sql = DSL.using(configuration); var session = lock(sql, leased);
            unchangedInventory(sql, session);
            var world = world(sql);
            var hashes = JsonSupport.MAPPER.createArrayNode(); int count = 0;
            for (var chunk : sql.fetch("SELECT first_index,item_count,proof_hash FROM migration_proof_chunks WHERE session_id=? ORDER BY first_index", id(session))) {
                if (chunk.get("first_index", Integer.class) != count) throw new MigrationProofs.Pending("MIGRATION_PROOF_GAP", "");
                count += chunk.get("item_count", Integer.class); hashes.add(chunk.get("proof_hash", String.class));
            }
            if (count != session.get("inventory_count", Integer.class) || count != session.get("verified_count", Integer.class))
                throw new MigrationProofs.Pending("MIGRATION_PROOF_GAP", "");
            var checkpoint = JsonSupport.MAPPER.createObjectNode().put("inventoryHash", session.get("inventory_hash", String.class))
                .put("count", count).put("worldId", world.path("worldId").asString())
                .put("journalGeneration", world.path("journalGeneration").asString()).put("journalHighWater", world.path("journalHighWater").asLong());
            checkpoint.set("proofChunkHashes", hashes);
            MigrationSessions.reserveEvidence(sql, checkpoint);
            sql.execute("""
                UPDATE migration_sessions SET phase='READY_TO_SWITCH',checkpoint=?::jsonb,checkpoint_hash=?,
                  last_error=NULL,attempts=0,version=version+1,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL
                WHERE session_id=?
                """, JsonSupport.write(checkpoint), JsonSupport.hash(checkpoint), now(), id(session));
            MigrationSessions.audit(sql, session, "migration-checkpoint-verified", "READY_TO_SWITCH", version(session), checkpoint);
        });
        hooks.reached("READY_TO_SWITCH", id(leased));
    }

    private void switchOwner(Record leased) {
        database.transaction(configuration -> {
            var sql = DSL.using(configuration); var session = lock(sql, leased);
            unchangedInventory(sql, session);
            var world = world(sql); var checkpoint = json(session, "checkpoint");
            if (!checkpoint.path("worldId").equals(world.path("worldId"))
                || !checkpoint.path("journalGeneration").equals(world.path("journalGeneration"))
                || world.path("journalHighWater").asLong() < checkpoint.path("journalHighWater").asLong())
                throw new MigrationProofs.Pending("MIGRATION_WORLD_CHANGED", "");
            long epoch = session.get("source_epoch", Long.class) + 1;
            sql.execute("UPDATE zone_routes SET owner=?,epoch=?,state='ACTIVE',version=version+1 WHERE site_id=? AND zone_id=?", session.get("target_owner"), epoch, site(session), zone(session));
            sql.execute("""
                UPDATE migration_sessions SET phase='OBSERVING',target_epoch=?,switched_at=?::timestamptz,
                  last_error=NULL,attempts=0,version=version+1,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL
                WHERE session_id=?
                """, epoch, now(), now(), id(session));
            var route = Database.json(sql, "SELECT jsonb_build_object('siteId',site_id,'zoneId',zone_id,'owner',owner,'epoch',epoch,'state',state,'version',version,'sessionId',migration_session_id) FROM zone_routes WHERE site_id=? AND zone_id=?", site(session), zone(session));
            UUID stream = UUID.nameUUIDFromBytes(("cutover-zone-ownership/" + site(session) + "/" + zone(session)).getBytes(StandardCharsets.UTF_8));
            Events.append(sql, site(session), "equipment-adapter", "zone-ownership", stream, epoch, "ZoneOwnershipChanged.v1", id(session), route);
            MigrationSessions.audit(sql, session, "migration-owner-switched", "OBSERVING", version(session), route);
        });
        hooks.reached("OBSERVING", id(leased));
    }

    private void observe(Record leased) {
        var sample = database.transactionResult(configuration -> {
            var sql = DSL.using(configuration); var session = lock(sql, leased);
            long pending = sql.fetchOne("SELECT count(*) FROM movement_allocations WHERE site_id=? AND zone_id=? AND state='PENDING'", site(session), zone(session)).get(0, Long.class);
            if (pending > 0) throw new MigrationProofs.Pending("PENDING_ASSIGNMENTS", "");
            var rows = sql.fetch("""
                SELECT movement_id,state FROM movement_allocations WHERE site_id=? AND zone_id=? AND owner=? AND epoch=?
                  AND state<>'CANCELLED' ORDER BY assigned_at,movement_id LIMIT 10
                """, site(session), zone(session), session.get("target_owner"), session.get("target_epoch"));
            if (rows.size() < 10) throw new MigrationProofs.Pending("WAITING_FOR_TEN_SAMPLE_MOVEMENTS", "");
            for (var row : rows) if (!row.get("state", String.class).equals("COMPLETED"))
                throw new MigrationProofs.Pending("OBSERVATION_MOVEMENT_NOT_FINISHED", row.get("movement_id").toString());
            return MigrationInventory.capture(sql, site(session), zone(session), rows.getValues("movement_id", UUID.class));
        });
        var proof = ownerProof(leased, sample);
        database.transaction(configuration -> {
            var sql = DSL.using(configuration); var session = lock(sql, leased);
            var current = MigrationInventory.capture(sql, site(session), zone(session), identities(sample));
            if (!JsonSupport.hash(current).equals(JsonSupport.hash(sample))) throw new MigrationProofs.Pending("OBSERVATION_CHANGED", "");
            var latencies = JsonSupport.MAPPER.createArrayNode(); double maximum = 0;
            for (UUID movement : identities(sample)) {
                var row = sql.fetchOne("""
                    SELECT c.created_at AS dispatch_at,GREATEST(a.assigned_at,(a.movement->>'eligibleAt')::timestamptz) AS eligible_at
                    FROM movement_allocations a JOIN command_journal c ON c.allocation_id=a.allocation_id WHERE a.site_id=? AND a.movement_id=?
                    """, site(session), movement);
                double millis = Duration.between(row.get("eligible_at", OffsetDateTime.class), row.get("dispatch_at", OffsetDateTime.class)).toNanos() / 1_000_000.0;
                if (millis < 0) throw new MigrationProofs.Pending("OBSERVATION_CLOCK_ORDER", movement.toString());
                maximum = Math.max(maximum, millis); latencies.addObject().put("movementId", movement.toString()).put("dispatchMillis", millis);
            }
            var observation = JsonSupport.MAPPER.createObjectNode().put("sampleCount", 10).put("dispatchP99Millis", maximum)
                .put("withinTwoSeconds", maximum <= 2000).put("proofHash", JsonSupport.hash(proof));
            observation.set("movements", latencies); observation.set("proof", proof);
            if (session.get("observation") == null || !JsonSupport.hash(json(session, "observation")).equals(JsonSupport.hash(observation)))
                MigrationSessions.reserveEvidence(sql, observation);
            if (maximum > 2000) {
                sql.execute("UPDATE migration_sessions SET observation=?::jsonb WHERE session_id=?", JsonSupport.write(observation), id(session));
                postpone(sql, session, blocker("OBSERVATION_LATENCY_TARGET_MISSED", null), "OBSERVATION_LATENCY_TARGET_MISSED", false);
                return;
            }
            sql.execute("""
                UPDATE migration_sessions SET phase='COMPLETED',observation=?::jsonb,blockers='{"count":0,"items":[]}',
                  last_error=NULL,attempts=0,version=version+1,finished_at=?::timestamptz,lease_id=NULL,lease_until=NULL WHERE session_id=?
                """, JsonSupport.write(observation), now(), id(session));
            sql.execute("UPDATE zone_routes SET migration_session_id=NULL,version=version+1 WHERE site_id=? AND zone_id=?", site(session), zone(session));
            UUID parent = session.get("reverses_session_id", UUID.class);
            settleLineage(sql, session, parent);
            MigrationSessions.audit(sql, session, "migration-observation-verified", "COMPLETED", version(session),
                JsonSupport.MAPPER.createObjectNode().put("sampleCount", 10).put("dispatchP99Millis", maximum).put("proofHash", JsonSupport.hash(proof)));
        });
        if (database.fetchOne("SELECT phase FROM migration_sessions WHERE session_id=?", id(leased)).get(0, String.class).equals("COMPLETED")) hooks.reached("COMPLETED", id(leased));
    }

    private void settleLineage(DSLContext sql, Record completed, UUID parent) {
        boolean immediate = true;
        // Each session can only reference an older, already persisted session.
        while (parent != null) {
            var ancestor = sql.fetchOne("SELECT * FROM migration_sessions WHERE session_id=? FOR UPDATE", parent);
            if (ancestor == null || !ancestor.get("phase", String.class).equals("REVERSING"))
                throw Problem.conflict("MIGRATION_LINEAGE_CHANGED", "The retained reversal lineage needs investigation.");
            String outcome = immediate ? "REVERSED" : "SUPERSEDED";
            sql.execute("UPDATE migration_sessions SET phase=?,version=version+1,finished_at=?::timestamptz WHERE session_id=?", outcome, now(), parent);
            MigrationSessions.audit(sql, ancestor, "migration-lineage-settled", outcome, version(ancestor),
                JsonSupport.MAPPER.createObjectNode().put("settledBySessionId", id(completed).toString())
                    .put("settledEpoch", completed.get("target_epoch", Long.class)));
            parent = ancestor.get("reverses_session_id", UUID.class);
            immediate = false;
        }
    }

    private JsonNode ownerProof(Record session, JsonNode page) {
        var request = JsonSupport.MAPPER.createObjectNode(); var all = request.putArray("movementIds");
        var executionRequest = JsonSupport.MAPPER.createObjectNode(); var extracted = executionRequest.putArray("movementIds");
        for (var item : page) { all.add(item.path("movementId")); if (item.path("owner").asString().equals("execution-service")) extracted.add(item.path("movementId")); }
        var core = owners.read("legacy-core", site(session), zone(session), request);
        var execution = extracted.isEmpty() ? null : owners.read("execution-service", site(session), zone(session), executionRequest);
        var world = database.transactionResult(configuration -> world(DSL.using(configuration)));
        return MigrationProofs.compare(page, core, execution, world);
    }

    private Record lock(DSLContext sql, Record leased) {
        Database.requireDurability(sql, false);
        var route = sql.fetchOne("SELECT * FROM zone_routes WHERE site_id=? AND zone_id=? FOR UPDATE", site(leased), zone(leased));
        var session = sql.fetchOne("SELECT * FROM migration_sessions WHERE session_id=? FOR UPDATE", id(leased));
        if (session == null || !Objects.equals(session.get("lease_id"), leased.get("lease_id"))
            || !session.get("phase").equals(leased.get("phase")) || session.get("lease_until", OffsetDateTime.class).isBefore(now())) throw new StaleLease();
        boolean switched = session.get("phase", String.class).equals("OBSERVING");
        if (!id(session).equals(route.get("migration_session_id")) || !route.get("owner").equals(session.get(switched ? "target_owner" : "source_owner"))
            || !route.get("epoch").equals(session.get(switched ? "target_epoch" : "source_epoch"))
            || !route.get("state", String.class).equals(switched ? "ACTIVE" : "DRAINING"))
            throw Problem.conflict("MIGRATION_ROUTE_CHANGED", "The current route no longer matches this migration phase.");
        return session;
    }

    private void unchangedInventory(DSLContext sql, Record session) {
        if (MigrationInventory.blockers(sql, site(session), zone(session)).path("count").asLong() != 0)
            throw new MigrationProofs.Pending("DRAIN_BLOCKED", "");
        if (!JsonSupport.hash(MigrationInventory.capture(sql, site(session), zone(session))).equals(session.get("inventory_hash", String.class)))
            throw new MigrationProofs.Pending("MIGRATION_INVENTORY_CHANGED", "");
    }

    private JsonNode world(DSLContext sql) {
        var world = equipment.current(sql);
        if (!world.path("completeHistory").asBoolean()) throw new MigrationProofs.Pending("PHYSICAL_HISTORY_INCOMPLETE", "");
        return world;
    }

    private void defer(Record leased, String error, String movement, boolean transport) {
        database.transaction(configuration -> {
            var sql = DSL.using(configuration); if (!Database.workersMayWrite(sql)) return;
            var session = sql.fetchOne("SELECT * FROM migration_sessions WHERE session_id=? AND lease_id=? AND phase=? FOR UPDATE", id(leased), leased.get("lease_id"), leased.get("phase"));
            if (session != null) postpone(sql, session, blocker(error, movement), error, transport);
        });
    }

    private void postpone(DSLContext sql, Record session, JsonNode blockers, String error, boolean transport) {
        int attempts = transport ? session.get("attempts", Integer.class) + 1 : 0;
        var next = transport ? now().plus(RetryDelay.after(id(session), attempts)) : now().plusSeconds(1);
        sql.execute("""
            UPDATE migration_sessions SET version=version+CASE WHEN blockers <> ?::jsonb OR last_error IS DISTINCT FROM ? OR attempts <> ? THEN 1 ELSE 0 END,
              blockers=?::jsonb,last_error=?,attempts=?,transport_paused=?,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL
            WHERE session_id=?
            """, JsonSupport.write(blockers), error, attempts, JsonSupport.write(blockers), error, attempts, attempts >= RetryDelay.MAX_ATTEMPTS, next, id(session));
    }

    private static JsonNode blocker(String reason, String movement) {
        var result = JsonSupport.MAPPER.createObjectNode().put("count", 1); var item = result.putArray("items").addObject().put("reason", reason);
        if (movement != null && !movement.isBlank()) item.put("movementId", movement);
        return result;
    }
    private static List<UUID> identities(JsonNode items) { var ids = new ArrayList<UUID>(); for (var item : items) ids.add(Database.uuid(item, "movementId")); return ids; }
    private static JsonNode json(Record row, String field) { return JsonSupport.read(row.get(field).toString()); }
    private static UUID id(Record row) { return row.get("session_id", UUID.class); }
    private static String site(Record row) { return row.get("site_id", String.class); }
    private static String zone(Record row) { return row.get("zone_id", String.class); }
    private static long version(Record row) { return row.get("version", Long.class); }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
