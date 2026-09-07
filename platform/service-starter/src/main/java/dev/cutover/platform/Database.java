package dev.cutover.platform;

import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

public final class Database {
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
    public static void requireDurability(DSLContext sql, boolean intake) {
        var control = sql.fetchOne("SELECT * FROM service_control WHERE singleton FOR SHARE");
        if (control == null || control.get("critical_storage", Boolean.class)
                || control.get("workers_paused", Boolean.class)
                || control.get(intake ? "intake_paused" : "dispatch_paused", Boolean.class)) {
            throw new Problem(503, "DURABILITY_PAUSED", "Durable processing is paused; retry after recovery.");
        }
    }
}
