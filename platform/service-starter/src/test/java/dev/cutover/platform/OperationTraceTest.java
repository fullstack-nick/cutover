package dev.cutover.platform;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

class OperationTraceTest {
    InMemorySpanExporter exporter;
    SdkTracerProvider provider;
    @BeforeEach void setup() {
        GlobalOpenTelemetry.resetForTest();
        exporter=InMemorySpanExporter.create();
        provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetrySdk.builder().setTracerProvider(provider).buildAndRegisterGlobal();
    }
    @AfterEach void cleanup() { provider.close(); GlobalOpenTelemetry.resetForTest(); MDC.clear(); }

    @Test void retainedEnvelopeResumesOriginalTraceAndCauseOutsideProducerScope() {
        UUID movement=UUID.randomUUID(), correlation=UUID.randomUUID(), eventId=UUID.randomUUID();
        String header, traceId, spanId;
        try(var operation=OperationTrace.start("cutover.order.accept","site-a",correlation)) {
            header=OperationTrace.traceparent();traceId=Span.current().getSpanContext().getTraceId();spanId=Span.current().getSpanContext().getSpanId();
        }
        assertThat(OperationTrace.traceparent()).isNull();
        var event=new Events.Envelope(eventId,"MovementRequested.v1",1,Instant.now(),"site-a","legacy-core","movement",movement,1,correlation,null,header,JsonSupport.read("{}"));
        try(var consume=OperationTrace.event("cutover.event.apply",event)) {
            assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(traceId);
            assertThat(OperationTrace.causationId()).isEqualTo(eventId);
            assertThat(OperationTrace.correlationId(UUID.randomUUID())).isEqualTo(correlation);
            try(var child=OperationTrace.start("cutover.movement.allocate","site-a",movement)) {
                assertThat(OperationTrace.causationId()).isEqualTo(eventId);
            }
        }
        assertThat(OperationTrace.causationId()).isNull();
        var applied=exporter.getFinishedSpanItems().stream().filter(item->item.getName().equals("cutover.event.apply")).findFirst().orElseThrow();
        assertThat(applied.getParentSpanId()).isEqualTo(spanId);
        assertThat(applied.getAttributes().asMap().toString()).contains(eventId.toString(),correlation.toString(),"site-a");
    }

    @Test void invalidOrMissingParentCannotAttachToUnrelatedScheduledWork() {
        for(String invalid:new String[]{null,"invalid","00-"+"0".repeat(32)+"-"+"0".repeat(16)+"-01","a".repeat(6000)}) {
            try(var unrelated=OperationTrace.start("cutover.unrelated","site-b",null)) {
                String other=Span.current().getSpanContext().getTraceId();
                try(var resumed=OperationTrace.resume("cutover.resume","site-a",null,invalid)) {
                    assertThat(Span.current().getSpanContext().getTraceId()).isNotEqualTo(other);
                    assertThat(OperationTrace.causationId()).isNull();
                }
                assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(other);
            }
        }
    }

    @Test void structuredContextRestoresAndFailureExportsNoExceptionMessageOrPayload() {
        MDC.put("caller-field","preserved");
        assertThatThrownBy(()->OperationTrace.call("cutover.order.accept","site-a",UUID.randomUUID(),()->{
            assertThat(MDC.get("trace_id")).hasSize(32);
            throw new IllegalStateException("private-fixture-value-not-telemetry");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("caller-field");
        var span=exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).isEmpty();
        assertThat(span.getAttributes().asMap().toString()).doesNotContain("private-fixture-value-not-telemetry");
    }
}
