package dev.cutover.platform;

import java.util.function.Supplier;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

/** Call only inside the owning business transaction. */
public final class Idempotency {
    private Idempotency() {}
    public static JsonNode execute(DSLContext sql, String caller, String site, String operation,
                                   String key, Object payload, Supplier<JsonNode> action) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw Problem.invalid("A valid Idempotency-Key is required.");
        Database.lock(sql, "idempotency", caller, site, operation, key);
        String hash = JsonSupport.hash(payload);
        var previous = sql.fetchOne("SELECT payload_hash, response FROM idempotency WHERE caller= ? AND site_id= ? AND operation= ? AND request_key= ?",
                caller, site, operation, key);
        if (previous != null) {
            if (!hash.equals(previous.get("payload_hash", String.class))) throw Problem.conflict("IDEMPOTENCY_CONFLICT", "This key was used with a different payload.");
            return JsonSupport.read(previous.get("response").toString());
        }
        // Return the same JSON number representation on first use and after storage round-trip.
        JsonNode response = JsonSupport.read(JsonSupport.write(action.get()));
        sql.execute("INSERT INTO idempotency(caller,site_id,operation,request_key,payload_hash,response) VALUES (?,?,?,?,?,?::jsonb)",
                caller, site, operation, key, hash, JsonSupport.write(response));
        return response;
    }
}
