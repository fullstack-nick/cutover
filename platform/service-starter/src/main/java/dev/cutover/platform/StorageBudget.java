package dev.cutover.platform;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import tools.jackson.databind.JsonNode;

/** Owner-local database and retained-message budgets leave room for already accepted outcomes. */
public final class StorageBudget {
    private StorageBudget() {}
    public static void requireHeadroom(DSLContext sql) {
        org.jooq.Record usage;
        try {
            usage=sql.fetchOne("SELECT retained_outbox_bytes,retained_outbox_limit,database_budget_bytes,pg_database_size(current_database()) AS database_bytes,cutover_ops.volume_status() AS volume FROM admission WHERE singleton");
        } catch (DataAccessException unavailable) {
            if (java.util.Set.of("42883","3F000").contains(unavailable.sqlState()))
                throw new Problem(503,"STORAGE_OBSERVATION","The required local database-volume observation is unavailable. Intake and dispatch remain held.");
            throw unavailable;
        }
        var volume=JsonSupport.read(usage.get("volume").toString());
        if (!"HEALTHY".equals(volume.path("state").asString()))
            throw new Problem(503,"STORAGE_OBSERVATION","Fresh database-volume headroom is not established. Accepted records and recovery reads remain available.");
        if(usage.get("retained_outbox_bytes",Long.class)>=usage.get("retained_outbox_limit",Long.class)*4/5
                || usage.get("database_bytes",Long.class)>=usage.get("database_budget_bytes",Long.class)*4/5)
            throw new Problem(503,"STORAGE_HEADROOM","New intake and dispatch are paused at the owner storage high-water mark. Accepted records and diagnostics are retained.");
    }
    public static JsonNode status(DSLContext sql) {
        return Database.json(sql,"""
            SELECT jsonb_build_object('observedAt',now(),'databaseBytes',pg_database_size(current_database()),
              'databaseBudgetBytes',database_budget_bytes,'retainedOutboxBytes',retained_outbox_bytes,
              'retainedOutboxLimit',retained_outbox_limit,'intakeHighWaterFraction',0.8,'replayHorizonDays',7,
              'unpublishedEvents',unpublished_events,'unpublishedBytes',unpublished_bytes,
              'retainedInboxBytes',(SELECT retained_bytes FROM message_storage WHERE singleton),'volume',cutover_ops.volume_status())
            FROM admission WHERE singleton
            """);
    }
}
