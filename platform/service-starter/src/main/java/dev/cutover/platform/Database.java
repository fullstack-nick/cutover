package dev.cutover.platform;

import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

public final class Database {
    // Reserved two-int advisory key, separate from the bigint business/idempotency key space.
    private static final int CONTROL_LOCK_CLASS=0x4355544f;
    private Database() {}
    public static void lock(DSLContext sql, Object... identity) {
        sql.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", JsonSupport.write(identity));
    }
    public static JsonNode json(DSLContext sql, String query, Object... bindings) {
        var record = sql.fetchOne(query, bindings);
        if (record == null || record.get(0) == null) throw Problem.missing();
        return JsonSupport.read(record.get(0).toString());
    }
    public static UUID uuid(JsonNode node, String key) {
        try { return UUID.fromString(node.required(key).asString()); }
        catch (RuntimeException invalid) { throw Problem.invalid("Invalid " + key); }
    }
    public static boolean workersMayWrite(DSLContext sql) {
        controlReadLock(sql);
        return !sql.fetchOne("SELECT workers_paused FROM service_control WHERE singleton").get(0, Boolean.class);
    }
    /** Caller transaction holds the barrier through commit; an ordinary check creates no row-lock WAL. */
    public static void controlReadLock(DSLContext sql) {
        if(sql.fetch("SELECT pg_advisory_xact_lock_shared(?,1) WHERE current_setting('transaction_isolation')='read committed'",CONTROL_LOCK_CLASS).isEmpty())
            throw new IllegalStateException("The control barrier requires READ COMMITTED transactions.");
    }
    /** Acquire before any service_control row mutation/lock. Recreate deployments keep one writer version per owner. */
    public static void controlWriteLock(DSLContext sql) {
        if(sql.fetch("SELECT pg_advisory_xact_lock(?,1) WHERE current_setting('transaction_isolation')='read committed'",CONTROL_LOCK_CLASS).isEmpty())
            throw new IllegalStateException("The control barrier requires READ COMMITTED transactions.");
    }
    public static void requireDurability(DSLContext sql, boolean intake) {
        controlReadLock(sql);
        var control = sql.fetchOne("SELECT * FROM service_control WHERE singleton");
        // The adapter adds this column when restoration support is installed; older/other owner schemas remain valid.
        if (control != null && control.field("restoration_required") != null && Boolean.TRUE.equals(control.get("restoration_required", Boolean.class)))
            throw new Problem(503, "RESTORATION_REQUIRED", "Dispatch remains held until the restored application checkpoint is reconciled with current physical history.");
        if (control == null || control.get("critical_storage", Boolean.class)
                || control.get("workers_paused", Boolean.class)
                || control.get(intake ? "intake_paused" : "dispatch_paused", Boolean.class)) {
            throw new Problem(503, "DURABILITY_PAUSED", "Durable processing is paused; retry after recovery.");
        }
        StorageBudget.requireHeadroom(sql);
    }
}
