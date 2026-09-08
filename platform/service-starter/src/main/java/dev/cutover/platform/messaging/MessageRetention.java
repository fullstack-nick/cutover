package dev.cutover.platform.messaging;

import dev.cutover.platform.Database;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Only acknowledged/applied payloads older than the supported replay horizon may be compacted. */
public final class MessageRetention {
    private final DSLContext database;
    private final Clock clock;
    private final boolean restorationHeld;
    public MessageRetention(DSLContext database,Clock clock) { this(database,clock,false); }
    public MessageRetention(DSLContext database,Clock clock,boolean restorationHeld) { this.database=database;this.clock=clock;this.restorationHeld=restorationHeld; }
    public int compact() {
        if(restorationHeld)return 0; // Preserve checkpoint replay bytes until the explicit recovery profile is released.
        OffsetDateTime cutoff=OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).minusDays(7);
        // Keep these transactions separate: the inbox owns its storage lock before it invokes a handler.
        int published=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return 0;
            sql.fetchOne("SELECT singleton FROM admission WHERE singleton FOR UPDATE");
            var rows=sql.fetch("DELETE FROM outbox WHERE event_id IN (SELECT event_id FROM outbox WHERE published_at<?::timestamptz AND created_at<?::timestamptz AND lease_until IS NULL ORDER BY published_at,event_id LIMIT 100 FOR UPDATE SKIP LOCKED) RETURNING payload_bytes",cutoff,cutoff);
            long bytes=rows.stream().mapToLong(row->row.get(0,Integer.class)).sum();
            if(bytes>0)sql.execute("UPDATE admission SET retained_outbox_bytes=retained_outbox_bytes-? WHERE singleton",bytes);
            return rows.size();
        });
        int received=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return 0;
            sql.fetchOne("SELECT singleton FROM message_storage WHERE singleton FOR UPDATE");
            var rows=sql.fetch("SELECT event_id,payload_bytes FROM inbox WHERE state='APPLIED' AND compacted_at IS NULL AND applied_at<?::timestamptz AND received_at<?::timestamptz ORDER BY applied_at,event_id LIMIT 100 FOR UPDATE SKIP LOCKED",cutoff,cutoff);
            long bytes=0;
            for(var row:rows){bytes+=row.get("payload_bytes",Integer.class);sql.execute("UPDATE inbox SET raw_body=NULL,envelope=NULL,payload_bytes=0,compacted_at=?::timestamptz WHERE event_id=?",now(),row.get("event_id"));}
            var raw=sql.fetch("SELECT delivery_id,payload_bytes FROM delivery_quarantine WHERE state='TRANSFERRED' AND compacted_at IS NULL AND transferred_at<?::timestamptz ORDER BY transferred_at,delivery_id LIMIT 100 FOR UPDATE SKIP LOCKED",cutoff);
            for(var row:raw){bytes+=row.get("payload_bytes",Integer.class);sql.execute("UPDATE delivery_quarantine SET raw_body=NULL,payload_bytes=0,compacted_at=?::timestamptz WHERE delivery_id=?",now(),row.get("delivery_id"));}
            if(bytes>0)sql.execute("UPDATE message_storage SET retained_bytes=retained_bytes-? WHERE singleton",bytes);
            return rows.size()+raw.size();
        });
        return published+received;
    }
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
