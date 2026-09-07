package dev.cutover.platform;

import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

/** Owner-local database and retained-message budgets leave room for already accepted outcomes. */
public final class StorageBudget {
    private StorageBudget() {}
    public static void requireHeadroom(DSLContext sql) {
        var usage=sql.fetchOne("SELECT retained_outbox_bytes,retained_outbox_limit,database_budget_bytes,pg_database_size(current_database()) AS database_bytes FROM admission WHERE singleton");
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
              'retainedInboxBytes',(SELECT retained_bytes FROM message_storage WHERE singleton))
            FROM admission WHERE singleton
            """);
    }
}
