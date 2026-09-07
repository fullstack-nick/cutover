package dev.cutover.platform.messaging;

import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Database;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class OutboxRelay {
    @FunctionalInterface public interface Publisher { void publish(String exchange, String routingKey, UUID id, String body); }
    public record Claimed(UUID eventId, UUID leaseId, String exchange, String type, String body, int attempt) {}
    private final DSLContext database;
    private final Publisher publisher;
    private final Clock clock;
    private final DeliveryHooks hooks;

    public OutboxRelay(DSLContext database, Publisher publisher, Clock clock, DeliveryHooks hooks) {
        this.database = database; this.publisher = publisher; this.clock = clock; this.hooks = hooks;
    }

    /** At most one leased event per stream; an earlier paused event also blocks overtaking. */
    public Claimed claim() {
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return null;
            var row = sql.fetchOne("""
                    SELECT o.* FROM outbox o
                    WHERE o.published_at IS NULL AND NOT o.paused AND o.next_attempt_at <= ?::timestamptz
                      AND (o.lease_until IS NULL OR o.lease_until < ?::timestamptz)
                      AND NOT EXISTS (SELECT 1 FROM outbox p WHERE p.source=o.source AND p.site_id=o.site_id
                        AND p.aggregate_type=o.aggregate_type AND p.aggregate_id=o.aggregate_id
                        AND p.aggregate_version<o.aggregate_version AND p.published_at IS NULL)
                    ORDER BY o.created_at,o.event_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                    """, now(), now());
            if (row == null) return null;
            UUID lease = UUID.randomUUID(); UUID id = row.get("event_id", UUID.class);
            int attempt = row.get("attempts", Integer.class) + 1;
            sql.execute("UPDATE outbox SET lease_id= ?,lease_until= ?::timestamptz,attempts= ?,version=version+1 WHERE event_id= ?", lease, now().plusSeconds(20), attempt, id);
            return new Claimed(id, lease, "cutover." + row.get("source", String.class) + ".v1", row.get("event_type", String.class), row.get("envelope").toString(), attempt);
        });
    }

    public int poll(int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Relay batch must be 1..32");
        int count = 0;
        while (count < limit) {
            Claimed row = claim(); if (row == null) break;
            hooks.reached("AFTER_BUSINESS_COMMIT", row.eventId());
            try {
                publisher.publish(row.exchange(), row.type(), row.eventId(), row.body());
                hooks.reached("AFTER_BROKER_CONFIRM", row.eventId());
                confirmed(row); count++;
            } catch (RuntimeException unavailable) {
                failed(row, unavailable instanceof DeliveryFailure ? unavailable.getMessage() : "BROKER_UNAVAILABLE");
                break; // One unavailable transport cannot tie up a batch or produce a hot loop.
            }
        }
        return count;
    }

    public void confirmed(Claimed row) {
        database.transaction(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return;
            sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
            var changed = sql.fetchOne("UPDATE outbox SET published_at= ?::timestamptz,lease_id=NULL,lease_until=NULL,last_error=NULL,version=version+1 WHERE event_id= ? AND lease_id= ? AND published_at IS NULL RETURNING payload_bytes", now(), row.eventId(), row.leaseId());
            if (changed != null) sql.execute("UPDATE admission SET unpublished_events=unpublished_events-1,unpublished_bytes=unpublished_bytes- ? WHERE singleton", changed.get(0, Integer.class));
        });
    }

    private void failed(Claimed row, String code) {
        database.transaction(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return;
            sql.execute("UPDATE outbox SET lease_id=NULL,lease_until=NULL,last_error= ?,next_attempt_at= ?::timestamptz,paused= ?,version=version+1 WHERE event_id= ? AND lease_id= ? AND published_at IS NULL",
                code, now().plus(RetryDelay.after(row.eventId(), row.attempt())), row.attempt() >= RetryDelay.MAX_ATTEMPTS, row.eventId(), row.leaseId());
        });
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
