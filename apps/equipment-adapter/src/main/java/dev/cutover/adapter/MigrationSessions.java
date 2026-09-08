package dev.cutover.adapter;

import dev.cutover.platform.*;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Persistent supervisor intent. Physical ownership changes only in the verified adapter step. */
public final class MigrationSessions {
    @FunctionalInterface public interface CheckpointHook { void reached(String phase, UUID session); }
    static final Set<String> ACTIVE = Set.of("DRAINING", "RECONCILING", "READY_TO_SWITCH", "OBSERVING");
    private final DSLContext database;
    private final Clock clock;
    private final CheckpointHook hooks;
    private final MigrationSteps steps;

    public MigrationSessions(DSLContext database, OwnerEvidencePort owners, EquipmentObservations equipment, Clock clock) {
        this(database, owners, equipment, clock, (phase, id) -> {});
    }

    public MigrationSessions(DSLContext database, OwnerEvidencePort owners, EquipmentObservations equipment,
                             Clock clock, CheckpointHook hooks) {
        this.database = database;
        this.clock = clock;
        this.hooks = hooks;
        this.steps = new MigrationSteps(database, owners, equipment, clock, hooks);
    }

    public JsonNode start(String actor, String site, String zone, String key, JsonNode request) {
        validate("migration-request.v1", request);
        if (!Set.of("ambient", "chilled").contains(zone)) throw Problem.missing();
        JsonNode result = database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            return Idempotency.execute(sql, actor, site, "start-migration:" + zone, key, request, () -> {
                Database.requireDurability(sql, false);
                var route = sql.fetchOne("SELECT * FROM zone_routes WHERE site_id=? AND zone_id=? FOR UPDATE", site, zone);
                if (route == null) throw Problem.missing();
                long before = route.get("version", Long.class);
                String target = request.path("targetOwner").asString();
                if (before != request.path("expectedVersion").asLong() || !route.get("state", String.class).equals("ACTIVE")
                    || target.equals(route.get("owner", String.class)))
                    throw Problem.conflict("MIGRATION_ROUTE_CONFLICT", "Refresh the active route and select a different owner.");
                UUID previous = route.get("migration_session_id", UUID.class);
                if (previous != null) {
                    var parent = sql.fetchOne("SELECT * FROM migration_sessions WHERE session_id=? FOR UPDATE", previous);
                    if (!parent.get("phase", String.class).equals("OBSERVING") || !target.equals(parent.get("source_owner")))
                        throw Problem.conflict("MIGRATION_IN_PROGRESS", "The current session must finish or enter an explicit ownership reversal.");
                    sql.execute("UPDATE migration_sessions SET phase='REVERSING',version=version+1,lease_id=NULL,lease_until=NULL WHERE session_id=?", previous);
                    audit(sql, parent, "migration-reversal-requested", "REVERSING", parent.get("version", Long.class), JsonSupport.MAPPER.createObjectNode().put("requestedBy", actor));
                }
                if (sql.execute("UPDATE migration_storage SET retained_sessions=retained_sessions+1 WHERE singleton AND retained_sessions<128") != 1)
                    throw new Problem(503, "MIGRATION_STORAGE_LIMIT", "The retained migration session limit has been reached.");
                UUID id = UUID.randomUUID();
                sql.execute("""
                    INSERT INTO migration_sessions(session_id,site_id,zone_id,source_owner,target_owner,source_epoch,
                      phase,actor,reason,reverses_session_id,created_at,next_attempt_at)
                    VALUES (?,?,?,?,?,?,'DRAINING',?,?,?,?::timestamptz,?::timestamptz)
                    """, id, site, zone, route.get("owner"), target, route.get("epoch"), actor, request.path("reason").asString(), previous, now(), now());
                sql.execute("UPDATE zone_routes SET state='DRAINING',migration_session_id=?,version=version+1 WHERE site_id=? AND zone_id=?", id, site, zone);
                var session = sql.fetchOne("SELECT * FROM migration_sessions WHERE session_id=?", id);
                audit(sql, session, "migration-started", "DRAINING", 0, JsonSupport.MAPPER.createObjectNode().put("routeBeforeVersion", before).put("routeAfterVersion", before + 1));
                return view(sql, site, id);
            });
        });
        hooks.reached("DRAINING", Database.uuid(result, "sessionId"));
        return result;
    }

    public JsonNode resume(String actor, String site, UUID id, String key, JsonNode request) {
        validate("reconciliation-request.v1", request);
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            return Idempotency.execute(sql, actor, site, "resume-migration:" + id, key, request, () -> {
                Database.requireDurability(sql, false);
                var session = sql.fetchOne("SELECT * FROM migration_sessions WHERE site_id=? AND session_id=? FOR UPDATE", site, id);
                if (session == null) throw Problem.missing();
                long before = session.get("version", Long.class);
                if (before != request.path("expectedVersion").asLong() || !session.get("transport_paused", Boolean.class)
                    || !ACTIVE.contains(session.get("phase", String.class)))
                    throw Problem.conflict("MIGRATION_RECOVERY_CONFLICT", "Refresh the exhausted migration before resuming its original evidence collection.");
                sql.execute("UPDATE migration_sessions SET transport_paused=false,attempts=0,last_error=NULL,version=version+1,lease_id=NULL,lease_until=NULL,next_attempt_at=?::timestamptz WHERE session_id=?", now(), id);
                sql.execute("""
                    INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome)
                    VALUES (?,?,?,'migration-recovery',?,?,?,?, 'EVIDENCE_COLLECTION_RESUMED')
                    """, UUID.randomUUID(), site, actor, id.toString(), request.path("reason").asString(), before, before + 1);
                return view(sql, site, id);
            });
        });
    }

    public JsonNode cancel(String actor, String site, UUID id, String key, JsonNode request) {
        validate("reconciliation-request.v1", request);
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            return Idempotency.execute(sql, actor, site, "cancel-migration:" + id, key, request, () -> {
                Database.requireDurability(sql, false);
                var summary = sql.fetchOne("SELECT zone_id FROM migration_sessions WHERE site_id=? AND session_id=?", site, id);
                if (summary == null) throw Problem.missing();
                var route = sql.fetchOne("SELECT * FROM zone_routes WHERE site_id=? AND zone_id=? FOR UPDATE", site, summary.get("zone_id"));
                var session = sql.fetchOne("SELECT * FROM migration_sessions WHERE site_id=? AND session_id=? FOR UPDATE", site, id);
                if (session.get("version", Long.class) != request.path("expectedVersion").asLong()
                    || !Set.of("DRAINING", "RECONCILING", "READY_TO_SWITCH").contains(session.get("phase", String.class))
                    || !route.get("state",String.class).equals("DRAINING")
                    || !id.equals(route.get("migration_session_id")) || !route.get("owner").equals(session.get("source_owner"))
                    || !route.get("epoch").equals(session.get("source_epoch")))
                    throw Problem.conflict("MIGRATION_CANCEL_CONFLICT", "Only an unchanged, unswitched session can be cancelled; reverse ownership after a switch.");
                UUID parent = session.get("reverses_session_id", UUID.class);
                if (parent != null) sql.execute("UPDATE migration_sessions SET phase='OBSERVING',version=version+1,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL WHERE session_id=? AND phase='REVERSING'", now(), parent);
                sql.execute("UPDATE zone_routes SET state='ACTIVE',migration_session_id=?,version=version+1 WHERE site_id=? AND zone_id=?", parent, site, summary.get("zone_id"));
                sql.execute("UPDATE migration_sessions SET phase='CANCELLED',version=version+1,finished_at=?::timestamptz,lease_id=NULL,lease_until=NULL WHERE session_id=?", now(), id);
                sql.execute("""
                    INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome)
                    VALUES (?,?,?,'migration-cancelled',?,?,?,?, 'OWNER_UNCHANGED')
                    """, UUID.randomUUID(), site, actor, id.toString(), request.path("reason").asString(), session.get("version"), session.get("version", Long.class) + 1);
                return view(sql, site, id);
            });
        });
    }

    public int poll() { return steps.poll(); }
    public JsonNode get(String site, UUID id) { return view(database, site, id); }
    public JsonNode list(String site) {
        var result = JsonSupport.MAPPER.createObjectNode().put("observedAt", clock.instant().toString());
        var items = result.putArray("items");
        for (var row : database.fetch("SELECT session_id FROM migration_sessions WHERE site_id=? ORDER BY created_at DESC,session_id LIMIT 50", site))
            items.add(view(database, site, row.get(0, UUID.class)));
        return result;
    }

    static JsonNode view(DSLContext sql, String site, UUID id) {
        return Database.json(sql, """
            SELECT jsonb_build_object('sessionId',session_id,'siteId',site_id,'zoneId',zone_id,
              'sourceOwner',source_owner,'targetOwner',target_owner,'sourceEpoch',source_epoch,'targetEpoch',target_epoch,
              'phase',phase,'version',version,'actor',actor,'reason',reason,'reversesSessionId',reverses_session_id,
              'inventoryCount',inventory_count,'verifiedCount',verified_count,'inventoryHash',inventory_hash,
              'checkpointHash',checkpoint_hash,'checkpoint',checkpoint,'blockers',blockers,'observation',observation,
              'transportAttempts',attempts,'transportPaused',transport_paused,'lastError',last_error,
              'createdAt',created_at,'switchedAt',switched_at,'finishedAt',finished_at,'observedAt',now())
            FROM migration_sessions WHERE site_id=? AND session_id=?
            """, site, id);
    }

    static void reserveEvidence(DSLContext sql, Object evidence) {
        int bytes = JsonSupport.write(evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (sql.execute("UPDATE migration_storage SET retained_bytes=retained_bytes+? WHERE singleton AND retained_bytes + ? <= 134217728", bytes, bytes) != 1)
            throw new Problem(503, "MIGRATION_STORAGE_LIMIT", "The retained migration evidence budget has been reached.");
    }

    static void audit(DSLContext sql, Record session, String action, String outcome, long before, JsonNode detail) {
        sql.execute("""
            INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)
            """, UUID.randomUUID(), session.get("site_id"), session.get("actor"), action, session.get("session_id").toString(),
            session.get("reason"), before, before + 1, outcome, JsonSupport.write(detail));
    }

    private static void validate(String schema, JsonNode request) {
        try { Contracts.validate(schema, JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Use the expected version, a bounded reason and the documented migration fields."); }
        if (request.path("reason").asString().strip().length() < 8) throw Problem.invalid("Provide a specific reason of at least eight characters.");
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
