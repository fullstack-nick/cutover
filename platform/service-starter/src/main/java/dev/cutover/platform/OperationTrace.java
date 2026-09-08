package dev.cutover.platform;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Optional observations only. No trace metadata grants authority or changes a business identity. */
public final class OperationTrace implements AutoCloseable {
    private static final ContextKey<UUID> CAUSE = ContextKey.named("cutover-event-cause");
    private static final ContextKey<UUID> CORRELATION = ContextKey.named("cutover-correlation");
    private static final TextMapGetter<String> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(String value) { return List.of("traceparent"); }
        public String get(String value, String key) { return "traceparent".equals(key) ? value : null; }
    };
    private final Span span;
    private final Scope scope;
    private final Map<String,String> previousMdc;
    private final String operation;

    private OperationTrace(String operation, Context parent, String site, UUID identity) {
        this.operation = operation;
        span = GlobalOpenTelemetry.getTracer("dev.cutover.domain", "0.1.0").spanBuilder(operation).setParent(parent).startSpan();
        scope = parent.with(span).makeCurrent();
        previousMdc = MDC.getCopyOfContextMap();
        field("cutover.site_id", site);
        field("cutover.resource_id", identity == null ? null : identity.toString());
        field("cutover.correlation_id", correlationId(null) == null ? null : correlationId(null).toString());
        field("cutover.causation_id", causationId() == null ? null : causationId().toString());
        if (span.getSpanContext().isValid()) {
            MDC.put("trace_id", span.getSpanContext().getTraceId());
            MDC.put("span_id", span.getSpanContext().getSpanId());
        }
    }

    public static OperationTrace start(String operation, String site, UUID identity) {
        return new OperationTrace(operation, Context.current(), site, identity);
    }
    public static OperationTrace resume(String operation, String site, UUID identity, String traceparent) {
        // Missing/invalid metadata starts a new trace; never inherit an unrelated scheduled poll.
        return new OperationTrace(operation, parent(traceparent), site, identity);
    }
    public static OperationTrace event(String operation, Events.Envelope event) {
        Context parent = parent(event.traceparent()).with(CAUSE, event.eventId()).with(CORRELATION, event.correlationId());
        var trace = new OperationTrace(operation, parent, event.siteId(), event.aggregateId());
        trace.field("cutover.event_id", event.eventId().toString());
        trace.field("cutover.event_type", event.eventType());
        return trace;
    }
    public static <T> T call(String operation, String site, UUID identity, Supplier<T> work) {
        try (var trace = start(operation, site, identity)) {
            try { return work.get(); }
            catch (RuntimeException failure) { trace.failed(failure); throw failure; }
        }
    }

    /** Reads only this owner's retained envelopes using existing stream/aggregate indexes. */
    public static OperationTrace movement(DSLContext database, String owner, String site, UUID movement, String operation) {
        var row = database.fetchOne("""
                SELECT envelope FROM outbox WHERE source=? AND site_id=? AND aggregate_type='movement'
                  AND aggregate_id=? AND aggregate_version=1
                UNION ALL
                SELECT i.envelope FROM stream_entry s JOIN inbox i ON i.event_id=s.event_id
                  WHERE s.source IN ('equipment-adapter','legacy-core','returns-service') AND s.site_id=? AND s.aggregate_type='movement'
                    AND s.aggregate_id=? AND s.aggregate_version=1 AND i.envelope IS NOT NULL
                LIMIT 1
                """, owner, site, movement, site, movement);
        if (row == null) return resume(operation, site, movement, null);
        return event(operation, JsonSupport.MAPPER.readValue(row.get(0).toString(), Events.Envelope.class));
    }

    private static Context parent(String value) {
        // Accept only a bounded W3C v00 header. Baggage and tracestate are not persisted.
        if (value == null || !value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) return Context.root();
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), value, GETTER);
    }
    public static String traceparent() {
        var context = Span.current().getSpanContext();
        return context.isValid() ? "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex() : null;
    }
    public static UUID causationId() { return Context.current().get(CAUSE); }
    public static UUID correlationId(UUID fallback) {
        UUID value = Context.current().get(CORRELATION); return value == null ? fallback : value;
    }
    public OperationTrace field(String key, String value) {
        annotate(key, value);
        return this;
    }
    public static void annotate(String key, String value) {
        if (value != null) { Span.current().setAttribute(key, value); MDC.put(key, value); }
    }
    public void failed(Throwable failure) {
        // Exception messages, bodies and credentials are deliberately not exported.
        span.setStatus(StatusCode.ERROR); field("error.type", failure.getClass().getSimpleName());
    }
    @Override public void close() {
        try {
            if (!operation.startsWith("cutover.event."))
                LoggerFactory.getLogger(OperationTrace.class).atInfo().addKeyValue("operation", operation).log("Domain operation ended");
        } finally {
            scope.close(); span.end();
            if (previousMdc == null) MDC.clear(); else MDC.setContextMap(previousMdc);
        }
    }
}
