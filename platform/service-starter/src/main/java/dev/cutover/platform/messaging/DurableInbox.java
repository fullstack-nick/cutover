package dev.cutover.platform.messaging;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Database commit transfers responsibility from RabbitMQ to this owner's durable inbox. */
public final class DurableInbox {
    public static final int MAX_MESSAGE_BYTES = 65536;
    private final DSLContext database;
    private final MessageHandler handler;
    private final Clock clock;
    private final Set<String> sources;
    private final Set<String> sites;
    public DurableInbox(DSLContext database, MessageHandler handler, Clock clock, Set<String> sources, Set<String> sites) {
        this.database = database; this.handler = handler; this.clock = clock; this.sources = Set.copyOf(sources); this.sites = Set.copyOf(sites);
    }

    public UUID receive(String exchange, String transportMessageId, byte[] body) {
        if (body.length > MAX_MESSAGE_BYTES) throw new Problem(503, "MESSAGE_TOO_LARGE", "Broker message-size enforcement must be repaired before consumption resumes.");
        Events.Envelope envelope;
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            Contracts.validate("event-envelope.v1", json);
            envelope = JsonSupport.MAPPER.readValue(json, Events.Envelope.class);
            if (!sources.contains(envelope.source()) || !("cutover." + envelope.source() + ".v1").equals(exchange)) throw DeliveryFailure.permanent("UNTRUSTED_EVENT_SOURCE");
            if (!sites.contains(envelope.siteId())) throw DeliveryFailure.permanent("EVENT_SITE_DENIED");
            if (envelope.payload().has("siteId") && !envelope.siteId().equals(envelope.payload().path("siteId").asString())) throw DeliveryFailure.permanent("EVENT_SITE_MISMATCH");
            if (transportMessageId != null && !envelope.eventId().toString().equals(transportMessageId)) throw DeliveryFailure.permanent("TRANSPORT_ID_MISMATCH");
        } catch (Exception invalid) {
            String code = invalid instanceof DeliveryFailure ? invalid.getMessage() : "INVALID_EVENT_CONTRACT";
            return database.transactionResult(configuration -> quarantineRaw(DSL.using(configuration), exchange, transportMessageId, body, code, trustedSite(exchange,body)));
        }
        var event = envelope;
        String hash = JsonSupport.hash(JsonSupport.read(json));
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            lockStorage(sql);
            var previous = sql.fetchOne("SELECT payload_hash,received_exchange FROM inbox WHERE event_id= ? FOR UPDATE", event.eventId());
            if (previous != null) {
                if (!hash.equals(previous.get("payload_hash", String.class)) || !exchange.equals(previous.get("received_exchange", String.class)))
                    return quarantineRaw(sql, exchange, transportMessageId, body, "EVENT_ID_CONFLICT", event.siteId());
                sql.execute("UPDATE inbox SET deliveries=deliveries+1 WHERE event_id= ?", event.eventId());
                return event.eventId();
            }
            reserveStorage(sql, body.length);
            sql.execute("INSERT INTO inbox(event_id,envelope,payload_hash,state,next_attempt_at,received_at,received_exchange,payload_bytes,raw_body,site_id) VALUES (?,?::jsonb,?,'RECEIVED',?::timestamptz,?::timestamptz,?,?,?,?)",
                    event.eventId(), JsonSupport.write(event), hash, now(), now(), exchange, body.length, body, event.siteId());
            attempt(sql, event);
            return event.eventId();
        });
    }

    public int retry(int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Inbox batch must be 1..32");
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return 0;
            lockStorage(sql);
            var rows = sql.fetch("SELECT envelope FROM inbox WHERE state IN ('RECEIVED','PENDING') AND next_attempt_at<= ?::timestamptz ORDER BY next_attempt_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED", now(), limit);
            for (var row : rows) attempt(sql, JsonSupport.MAPPER.readValue(row.get(0).toString(), Events.Envelope.class));
            return rows.size();
        });
    }

    private void attempt(DSLContext sql, Events.Envelope event) {
        int attempts = sql.fetchOne("UPDATE inbox SET attempts=attempts+1,version=version+1 WHERE event_id= ? RETURNING attempts", event.eventId()).get(0, Integer.class);
        try {
            // A handler failure rolls back its effects and cursor; the outer transaction still records pending/quarantine.
            sql.transaction(configuration -> applyInTransaction(DSL.using(configuration), event));
            int bytes = sql.fetchOne("UPDATE inbox SET state='APPLIED',applied_at= ?::timestamptz,last_error=NULL,version=version+1 WHERE event_id= ? RETURNING payload_bytes", now(), event.eventId()).get(0, Integer.class);
            sql.execute("UPDATE message_storage SET active_messages=active_messages-1,active_bytes=active_bytes- ? WHERE singleton", bytes);
        } catch (RuntimeException failure) {
            boolean retryable = !(failure instanceof DeliveryFailure delivery) || delivery.retryable();
            String code = failure instanceof DeliveryFailure ? failure.getMessage() : "EFFECT_TEMPORARILY_UNAVAILABLE";
            String state = retryable && attempts < RetryDelay.MAX_ATTEMPTS ? "PENDING" : "QUARANTINED";
            sql.execute("UPDATE inbox SET state= ?,last_error= ?,next_attempt_at= ?::timestamptz,version=version+1 WHERE event_id= ?", state, code, now().plus(RetryDelay.after(event.eventId(), attempts)), event.eventId());
        }
    }

    private void applyInTransaction(DSLContext sql, Events.Envelope event) {
        Database.lock(sql, "event-stream", event.source(), event.siteId(), event.aggregateType(), event.aggregateId());
        sql.execute("INSERT INTO stream_cursor(source,site_id,aggregate_type,aggregate_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", event.source(), event.siteId(), event.aggregateType(), event.aggregateId());
        long last = sql.fetchOne("SELECT last_version FROM stream_cursor WHERE source= ? AND site_id= ? AND aggregate_type= ? AND aggregate_id= ? FOR UPDATE", event.source(), event.siteId(), event.aggregateType(), event.aggregateId()).get(0, Long.class);
        String semantic = JsonSupport.hash(Map.of("eventType", event.eventType(), "schemaVersion", event.schemaVersion(), "payload", event.payload()));
        if (event.aggregateVersion() <= last) {
            var old = sql.fetchOne("SELECT semantic_hash FROM stream_entry WHERE source= ? AND site_id= ? AND aggregate_type= ? AND aggregate_id= ? AND aggregate_version= ?", event.source(), event.siteId(), event.aggregateType(), event.aggregateId(), event.aggregateVersion());
            if (old == null || !semantic.equals(old.get(0, String.class))) throw DeliveryFailure.permanent("STREAM_VERSION_CONFLICT");
            return;
        }
        if (event.aggregateVersion() != last + 1) throw DeliveryFailure.pending("SEQUENCE_GAP_EXPECTED_" + (last + 1));
        handler.apply(sql, event);
        sql.execute("INSERT INTO stream_entry(source,site_id,aggregate_type,aggregate_id,aggregate_version,semantic_hash,event_id) VALUES (?,?,?,?,?,?,?)", event.source(), event.siteId(), event.aggregateType(), event.aggregateId(), event.aggregateVersion(), semantic, event.eventId());
        sql.execute("UPDATE stream_cursor SET last_version= ? WHERE source= ? AND site_id= ? AND aggregate_type= ? AND aggregate_id= ?", event.aggregateVersion(), event.source(), event.siteId(), event.aggregateType(), event.aggregateId());
    }

    private UUID quarantineRaw(DSLContext sql, String exchange, String messageId, byte[] body, String reason, String site) {
        lockStorage(sql);
        UUID id;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(exchange.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); digest.update(body);
            var bytes = ByteBuffer.wrap(digest.digest()); id = new UUID(bytes.getLong(), bytes.getLong());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (sql.fetchExists(DSL.table("delivery_quarantine"), DSL.field("delivery_id").eq(id))) return id;
        reserveStorage(sql, body.length);
        sql.execute("INSERT INTO delivery_quarantine(delivery_id,transport_message_id,received_exchange,site_id,raw_body,payload_bytes,reason,received_at,body_hash) VALUES (?,?,?,?,?,?,?,?::timestamptz,encode(sha256(?),'hex'))",
                id, messageId, exchange, site, body, body.length, reason, now(),body);
        return id;
    }

    /** A site label is trustworthy only when it agrees with the broker-authenticated publisher. */
    String trustedSite(String exchange,byte[] body) {
        try {
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            var candidate=JsonSupport.read(json);String source=candidate.path("source").asString(),site=candidate.path("siteId").asString();
            if(sources.contains(source) && ("cutover."+source+".v1").equals(exchange) && sites.contains(site)
                    && (!candidate.path("payload").has("siteId") || site.equals(candidate.path("payload").path("siteId").asString())))return site;
        } catch(Exception invalid) { /* Unparseable bytes stay in the platform-only diagnostic pool. */ }
        return null;
    }

    private static void lockStorage(DSLContext sql) {
        if (sql.fetchOne("SELECT workers_paused FROM service_control WHERE singleton FOR SHARE").get(0, Boolean.class))
            throw new Problem(503, "WORKERS_PAUSED", "Inbox mutation is paused for an operational checkpoint.");
        sql.fetchOne("SELECT * FROM message_storage WHERE singleton FOR UPDATE");
    }
    private static void reserveStorage(DSLContext sql, int bytes) {
        if (sql.execute("UPDATE message_storage SET active_messages=active_messages+1,active_bytes=active_bytes+ ?,retained_bytes=retained_bytes+ ? WHERE singleton AND active_messages<10000 AND active_bytes+ ? <=67108864 AND retained_bytes+ ? <=268435456", bytes, bytes, bytes, bytes) != 1)
            throw new Problem(503, "INBOX_CAPACITY", "Durable inbox capacity is exhausted; broker ownership is retained.");
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
