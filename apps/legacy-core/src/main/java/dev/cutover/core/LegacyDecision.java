package dev.cutover.core;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

/** SQL proposal over exactly the supplied snapshot; live reads are captured before this call. */
public final class LegacyDecision {
    private LegacyDecision() {}
    public static JsonNode propose(DSLContext sql,JsonNode snapshot) {
        Contracts.validate("scheduling-snapshot.v1",JsonSupport.write(snapshot));
        return Database.json(sql,"SELECT legacy_schedule_snapshot(?::jsonb)",JsonSupport.write(snapshot));
    }
}
