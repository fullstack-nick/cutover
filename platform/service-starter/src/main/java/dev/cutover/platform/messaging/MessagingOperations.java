package dev.cutover.platform.messaging;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Idempotency;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

public final class MessagingOperations {
    private final DSLContext database;
    public MessagingOperations(DSLContext database) { this.database = database; }

    public JsonNode recover(String actor, String site, String kind, UUID id, String key, JsonNode request) {
        if (!kind.equals("inbox") && !kind.equals("outbox")) throw Problem.missing();
        try { Contracts.validate("reconciliation-request.v1", JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Provide an expected version and a reason of 8–500 characters."); }
        String reason = request.path("reason").asString().trim();
        if (reason.length() < 8) throw Problem.invalid("The recovery reason must explain the correction.");
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            return Idempotency.execute(sql, actor, site, "recover-" + kind, key, Map.of("id", id, "request", request), () -> {
                if (kind.equals("outbox")) sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
                else sql.fetchOne("SELECT * FROM message_storage WHERE singleton FOR UPDATE");
                var row = sql.fetchOne("SELECT * FROM " + kind + " WHERE event_id= ? AND site_id= ? FOR UPDATE", id, site);
                if (row == null) throw Problem.missing();
                long before = row.get("version", Long.class);
                if (before != request.path("expectedVersion").asLong()) throw Problem.conflict("VERSION_CONFLICT", "The delivery changed; inspect its latest evidence before retrying.");
                if (kind.equals("inbox")) {
                    if (!java.util.Set.of("PENDING", "QUARANTINED").contains(row.get("state", String.class))) throw Problem.conflict("DELIVERY_STATE", "Only pending or quarantined deliveries can be retried.");
                    sql.execute("UPDATE inbox SET state='PENDING',attempts=0,next_attempt_at=now(),last_error=NULL,version=version+1 WHERE event_id= ?", id);
                } else {
                    if (row.get("lease_until", java.time.OffsetDateTime.class) != null && row.get("lease_until", java.time.OffsetDateTime.class).isAfter(java.time.OffsetDateTime.now()))
                        throw Problem.conflict("DELIVERY_IN_FLIGHT", "The current publish lease must settle before replay.");
                    int bytes = row.get("payload_bytes", Integer.class);
                    if (row.get("published_at") != null && sql.execute("UPDATE admission SET unpublished_events=unpublished_events+1,unpublished_bytes=unpublished_bytes+ ? WHERE singleton AND unpublished_events<10000 AND unpublished_bytes+ ? <=67108864", bytes, bytes) != 1)
                        throw new Problem(503, "OUTBOX_CAPACITY", "Replay requires available durable event capacity.");
                    sql.execute("UPDATE outbox SET published_at=NULL,paused=false,attempts=0,next_attempt_at=now(),lease_id=NULL,lease_until=NULL,last_error=NULL,version=version+1 WHERE event_id= ?", id);
                }
                var response = JsonSupport.MAPPER.valueToTree(Map.of("eventId", id, "state", "RETRY_RECORDED", "version", before + 1));
                sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,?,?,?,?,?,'RECORDED',?::jsonb)",
                        UUID.randomUUID(), site, actor, "recover-" + kind, id.toString(), reason, before, before + 1, JsonSupport.write(response));
                return response;
            });
        });
    }
    public JsonNode status(String site) {
        return Database.json(database, """
                SELECT jsonb_build_object('observedAt',now(),
                  'unpublished',(SELECT count(*) FROM outbox WHERE site_id= ? AND published_at IS NULL),
                  'paused',(SELECT count(*) FROM outbox WHERE site_id= ? AND published_at IS NULL AND paused),
                  'pending',(SELECT count(*) FROM inbox WHERE site_id= ? AND state IN ('RECEIVED','PENDING')),
                  'quarantined',(SELECT count(*) FROM inbox WHERE site_id= ? AND state='QUARANTINED'),
                  'oldestUnpublishedAt',(SELECT min(created_at) FROM outbox WHERE site_id= ? AND published_at IS NULL),
                  'outbox',(SELECT COALESCE(jsonb_agg(jsonb_build_object('eventId',event_id,'eventType',event_type,'attempts',attempts,'version',version,'paused',paused,'lastError',last_error,'createdAt',created_at)),'[]'::jsonb) FROM (SELECT * FROM outbox WHERE site_id= ? AND published_at IS NULL ORDER BY created_at,event_id LIMIT 100) o),
                  'inbox',(SELECT COALESCE(jsonb_agg(jsonb_build_object('eventId',event_id,'state',state,'attempts',attempts,'version',version,'lastError',last_error,'receivedAt',received_at)),'[]'::jsonb) FROM (SELECT * FROM inbox WHERE site_id= ? AND state<>'APPLIED' ORDER BY received_at,event_id LIMIT 100) i))
                """, site, site, site, site, site, site, site);
    }
}
