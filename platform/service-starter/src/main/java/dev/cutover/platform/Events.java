package dev.cutover.platform;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

public final class Events {
    private Events() {}
    public record Envelope(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                           String siteId, String source, String aggregateType, UUID aggregateId,
                           long aggregateVersion, UUID correlationId, UUID causationId, String traceparent, JsonNode payload) {}

    public static void append(DSLContext sql, String site, String source, String type, UUID aggregateId,
                              long version, String eventType, UUID correlationId, JsonNode payload) {
        var event = new Envelope(UUID.randomUUID(), eventType, 1, Instant.now(), site, source, type, aggregateId,
                version, correlationId, null, null, payload);
        String json = JsonSupport.write(event);
        Contracts.validate("event-envelope.v1", json);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (sql.execute("UPDATE admission SET unpublished_events=unpublished_events+1, unpublished_bytes=unpublished_bytes+ ? WHERE singleton AND unpublished_events<10000 AND unpublished_bytes+ ? <=67108864", bytes, bytes) != 1)
            throw new Problem(503, "OUTBOX_CAPACITY", "Durable event capacity is exhausted.");
        sql.execute("INSERT INTO outbox(event_id,site_id,source,aggregate_type,aggregate_id,aggregate_version,event_type,envelope,payload_bytes) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
                event.eventId(), site, source, type, aggregateId, version, eventType, json, bytes);
    }
}
