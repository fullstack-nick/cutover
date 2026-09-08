package dev.cutover.compatibility;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.cutover.platform.Contracts;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Frozen predecessor contract consumers and current production validation, with a deliberately incompatible producer. */
class ContractEvolutionTest {
    private static String resource(String name) throws IOException {
        try(var stream=ContractEvolutionTest.class.getResourceAsStream("/contract-compatibility/"+name)) {
            if(stream==null)throw new IOException("Missing committed compatibility fixture: "+name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static void previous(String schema,String json) throws IOException {
        var registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder->builder.schemaLoader(loader->loader.fetchRemoteResources(false)));
        var errors=registry.getSchema(resource("n-minus-one/"+schema+".json"),InputFormat.JSON)
            .validate(json,InputFormat.JSON,context->context.executionConfig(config->config.formatAssertionsEnabled(true)));
        if(!errors.isEmpty())throw new IllegalArgumentException("Predecessor contract violation: "+errors.getFirst().getMessage());
    }
    private static void consume(String version,String json) throws IOException {
        JsonNode event=JsonSupport.read(json);
        if(version.equals("N-1")) {
            previous("event-envelope.v1",json);previous("movement.v1",JsonSupport.write(event.path("payload")));
        } else {
            Contracts.validate("event-envelope.v1",json);Contracts.validate("movement.v1",JsonSupport.write(event.path("payload")));
        }
        // The real envelope decoder must also tolerate the allowed optional envelope field.
        var decoded=JsonSupport.MAPPER.readValue(json,Events.Envelope.class);
        assertThat(decoded.payload().path("quantity").asInt()).isEqualTo(1);
        assertThat(decoded.aggregateId().toString()).isEqualTo(event.path("payload").path("movementId").asString());
    }
    @Test void previousAndAdditiveProducersPassBothContractConsumers() throws Exception {
        var provenance=JsonSupport.read(resource("provenance.json"));
        assertThat(provenance.path("frozenSchemaCommit").asString()).isEqualTo("1b7f9a881a4333cf59b22b76870b5a830d803dd0");
        for(var schema:provenance.path("schemas")) {
            var bytes=resource("n-minus-one/"+schema.path("name").asString()+".json").getBytes(StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(schema.path("sha256").asString());
        }
        for(String producer:List.of("producer-n-minus-one.json","producer-n-additive.json"))
            for(String consumer:List.of("N-1","N"))consume(consumer,resource(producer));
    }
    @Test void changedFieldTypeFailsBothPredeploymentContractChecks() throws Exception {
        String breaking=resource("producer-breaking.json");
        for(String consumer:List.of("N-1","N")) {
            consume(consumer,resource("producer-n-additive.json"));
            assertThatThrownBy(()->consume(consumer,breaking)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integer expected");
        }
    }
}
