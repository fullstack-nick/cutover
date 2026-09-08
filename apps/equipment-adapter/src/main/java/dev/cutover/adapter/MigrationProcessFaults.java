package dev.cutover.adapter;

import dev.cutover.platform.*;
import java.time.*;
import java.util.*;
import java.util.function.IntConsumer;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Explicit local test control. Each fault is consumed durably before the fixed process exit. */
public final class MigrationProcessFaults implements MigrationSessions.CheckpointHook {
    private final DSLContext database;
    private final Clock clock;
    private final IntConsumer terminate;
    public MigrationProcessFaults(DSLContext database, Clock clock) { this(database, clock, code -> Runtime.getRuntime().halt(code)); }
    public MigrationProcessFaults(DSLContext database, Clock clock, IntConsumer terminate) {
        this.database = database; this.clock = clock; this.terminate = terminate;
    }

    public JsonNode arm(String actor, String site, String key, JsonNode request) {
        if (!site.equals("site-a")) throw Problem.missing();
        try { Contracts.validate("migration-process-fault-request.v1", JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Use a bounded phase, expected control version and reason."); }
        if (request.path("reason").asString().strip().length() < 8) throw Problem.invalid("Provide a specific phase fault reason.");
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            return Idempotency.execute(sql, actor, site, "arm-migration-fault", key, request, () -> {
                long before = sql.fetchOne("SELECT version FROM service_control WHERE singleton FOR UPDATE").get(0, Long.class);
                if (before != request.path("expectedVersion").asLong()) throw Problem.conflict("VERSION_CONFLICT", "Refresh the process controls before arming a phase fault.");
                if (sql.fetchOne("SELECT EXISTS(SELECT 1 FROM migration_process_faults WHERE site_id=? AND phase=? AND remaining=1)", site, request.path("phase").asString()).get(0, Boolean.class))
                    throw Problem.conflict("FAULT_ALREADY_ARMED", "Clear the existing fault for this phase first.");
                UUID id = UUID.randomUUID();
                sql.execute("INSERT INTO migration_process_faults(fault_id,site_id,phase,session_selector,actor,reason,armed_at) VALUES (?,?,?,?,?,?,?::timestamptz)", id, site, request.path("phase").asString(), request.hasNonNull("sessionId") ? Database.uuid(request, "sessionId") : null, actor, request.path("reason").asString(), now());
                sql.execute("UPDATE service_control SET version=version+1 WHERE singleton");
                sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,'migration-process-fault-armed',?,?,?,?, 'ARMED',?::jsonb)", UUID.randomUUID(), site, actor, id.toString(), request.path("reason").asString(), before, before + 1, JsonSupport.write(request));
                return JsonSupport.MAPPER.createObjectNode().put("faultId", id.toString()).put("state", "ARMED").put("version", 1).put("controlVersion", before + 1);
            });
        });
    }

    @Override public void reached(String phase, UUID session) {
        boolean fire = database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return false;
            var fault = sql.fetchOne("""
                SELECT f.* FROM migration_process_faults f JOIN migration_sessions s ON s.site_id=f.site_id
                WHERE s.session_id=? AND s.phase=? AND f.phase=? AND f.remaining=1
                  AND (f.session_selector=s.session_id OR (f.session_selector IS NULL AND s.created_at>=f.armed_at))
                FOR UPDATE OF f SKIP LOCKED
                """, session, phase, phase);
            if (fault == null) return false;
            sql.execute("UPDATE migration_process_faults SET remaining=0,fired_at=?::timestamptz,fired_session_id=?,version=version+1 WHERE fault_id=?", now(), session, fault.get("fault_id"));
            sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,'migration-process-fault-fired',?,?,?,?, 'FIRED',?::jsonb)", UUID.randomUUID(), fault.get("site_id"), fault.get("actor"), fault.get("fault_id").toString(), fault.get("reason"), fault.get("version"), fault.get("version", Long.class) + 1, JsonSupport.write(Map.of("phase", phase, "sessionId", session, "exitCode", 73)));
            return true;
        });
        if (fire) terminate.accept(73);
    }

    public JsonNode list(String site) {
        if (!site.equals("site-a")) throw Problem.missing();
        return Database.json(database, "SELECT COALESCE(jsonb_agg(jsonb_build_object('faultId',fault_id,'phase',phase,'sessionId',session_selector,'remaining',remaining,'version',version,'armedAt',armed_at,'firedAt',fired_at,'firedSessionId',fired_session_id,'clearedAt',cleared_at) ORDER BY armed_at DESC),'[]'::jsonb) FROM (SELECT * FROM migration_process_faults WHERE site_id=? ORDER BY armed_at DESC LIMIT 100) f", site);
    }

    public JsonNode clear(String actor, String site, UUID id, String key, JsonNode request) {
        if (!site.equals("site-a")) throw Problem.missing();
        try { Contracts.validate("reconciliation-request.v1", JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Use the observed fault version and a bounded reason."); }
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            return Idempotency.execute(sql, actor, site, "clear-migration-fault:" + id, key, request, () -> {
                var fault = sql.fetchOne("SELECT * FROM migration_process_faults WHERE site_id=? AND fault_id=? FOR UPDATE", site, id);
                if (fault == null) throw Problem.missing();
                long before = fault.get("version", Long.class);
                if (before != request.path("expectedVersion").asLong()) throw Problem.conflict("VERSION_CONFLICT", "Refresh the current phase fault.");
                sql.execute("UPDATE migration_process_faults SET remaining=0,cleared_at=?::timestamptz,version=version+1 WHERE fault_id=?", now(), id);
                sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome) VALUES (?,?,?,'migration-process-fault-cleared',?,?,?,?, 'CLEARED')", UUID.randomUUID(), site, actor, id.toString(), request.path("reason").asString(), before, before + 1);
                return JsonSupport.MAPPER.createObjectNode().put("faultId", id.toString()).put("version", before + 1).put("state", "CLEARED");
            });
        });
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
