package dev.cutover.compatibility;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import dev.cutover.platform.Contracts;
import dev.cutover.platform.JsonSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.ToolProvider;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.codegen.GenerationTool;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import static org.assertj.core.api.Assertions.*;

class RuntimeCompatibilityTest {
    @TempDir Path temporary;

    @Test void postgresMigrationsGeneratedTypesTransactionsAndBootJsonWorkTogether() throws Exception {
        try (var postgres = new PostgreSQLContainer(System.getProperty("cutover.postgres.image"))
                .withLabel("dev.cutover.purpose", "compatibility")) {
            postgres.start();
            var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/compatibility").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var sql = DSL.using(connection, SQLDialect.POSTGRES);
                sql.transaction(configuration -> DSL.using(configuration).execute("UPDATE compatibility_stock SET reserved=4"));
                assertThatThrownBy(() -> sql.execute("UPDATE compatibility_stock SET reserved=11")).isInstanceOf(org.jooq.exception.DataAccessException.class);
                assertThat(sql.fetchOne("SELECT reserved FROM compatibility_stock").get(0,Integer.class)).isEqualTo(4);
            }
            Path generated = temporary.resolve("generated");
            GenerationTool.generate(new org.jooq.meta.jaxb.Configuration()
                    .withJdbc(new org.jooq.meta.jaxb.Jdbc().withDriver("org.postgresql.Driver").withUrl(postgres.getJdbcUrl())
                            .withUser(postgres.getUsername()).withPassword(postgres.getPassword()))
                    .withGenerator(new org.jooq.meta.jaxb.Generator()
                            .withDatabase(new org.jooq.meta.jaxb.Database().withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema("public").withIncludes("compatibility_stock"))
                            .withTarget(new org.jooq.meta.jaxb.Target().withPackageName("dev.cutover.generated.smoke").withDirectory(generated.toString()))));
            try (var paths = Files.walk(generated)) {
                var sourceFiles = paths.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
                assertThat(sourceFiles).isNotEmpty();
                var arguments = new java.util.ArrayList<>(java.util.List.of("-classpath", System.getProperty("java.class.path"), "-d", temporary.toString()));
                arguments.addAll(sourceFiles);
                assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new))).isZero();
            }
            try (var context = new SpringApplicationBuilder(SmokeApplication.class).properties(Map.of(
                    "server.port", "0", "server.address", "127.0.0.1",
                    "spring.datasource.url", postgres.getJdbcUrl(), "spring.datasource.username", postgres.getUsername(),
                    "spring.datasource.password", postgres.getPassword(), "spring.flyway.locations", "classpath:db/compatibility",
                    "spring.main.banner-mode", "off", "logging.level.root", "WARN")).run()) {
                int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/compatibility")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var payload = JsonSupport.read(response.body());
                assertThat(payload.get("observedAt").asString()).isEqualTo("2026-09-07T16:00:00Z");
                assertThat(payload.get("state").asString()).isEqualTo("COMPLETED");
                assertThat(payload.get("id").asString()).isEqualTo("00000000-0000-4000-8000-000000000001");
            }
        }
    }

    @Test void schemaFormatsAndCanonicalIdempotencyHashesAreEnforced() {
        String envelope = """
                {"eventId":"00000000-0000-4000-8000-000000000001","eventType":"MovementRequested.v1","schemaVersion":1,
                "occurredAt":"2026-09-07T16:00:00Z","siteId":"site-a","source":"legacy-core","aggregateType":"movement",
                "aggregateId":"00000000-0000-4000-8000-000000000002","aggregateVersion":1,
                "correlationId":"00000000-0000-4000-8000-000000000003","causationId":null,"traceparent":null,"payload":{}}
                """;
        Contracts.validate("event-envelope.v1", envelope);
        assertThatThrownBy(() -> Contracts.validate("event-envelope.v1", envelope.replace("2026-09-07T16:00:00Z", "yesterday"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Contracts.validate("event-envelope.v1", envelope.replace("00000000-0000-4000-8000-000000000001", "invalid"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(JsonSupport.hash(JsonSupport.read("{\"b\":2,\"a\":{\"y\":2,\"x\":1}}")))
                .isEqualTo(JsonSupport.hash(JsonSupport.read("{\"a\":{\"x\":1,\"y\":2},\"b\":2}")));
    }

    @Test void brokerConfirmDoesNotHideMandatoryReturnOrCapacityRejection() throws Exception {
        try (var broker = new RabbitMQContainer(System.getProperty("cutover.rabbitmq.image"))
                .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2").withLabel("dev.cutover.purpose", "compatibility")) {
            broker.start();
            var factory = new ConnectionFactory();
            factory.setUri(broker.getAmqpUrl());
            try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
                channel.confirmSelect();
                var returned = new AtomicBoolean();
                channel.addReturnListener(message -> returned.set(true));
                var properties = new AMQP.BasicProperties.Builder().deliveryMode(2).build();
                channel.basicPublish("", "missing-queue", true, properties, new byte[]{1});
                assertThat(channel.waitForConfirms(5000)).isTrue();
                assertThat(returned).isTrue();
                channel.queueDeclare("cutover-capacity", true, false, false, Map.of("x-queue-type", "quorum", "x-max-length", 1, "x-overflow", "reject-publish"));
                channel.basicPublish("", "cutover-capacity", true, properties, new byte[]{2});
                assertThat(channel.waitForConfirms(5000)).isTrue();
                // Quorum flow control can accept in-flight messages while propagating the limit.
                // Require bounded rejection and retention of every confirmed message, not an exact cutoff.
                var confirmed = new java.util.ArrayList<Byte>();
                confirmed.add((byte) 2);
                boolean rejected = false;
                for (byte value = 3; value < 24 && !rejected; value++) {
                    channel.basicPublish("", "cutover-capacity", true, properties, new byte[]{value});
                    if (channel.waitForConfirms(5000)) confirmed.add(value); else rejected = true;
                }
                assertThat(rejected).as("bounded quorum queue capacity rejection").isTrue();
                for (byte value : confirmed) {
                    var delivery = channel.basicGet("cutover-capacity", false);
                    assertThat(delivery).isNotNull();
                    assertThat(delivery.getBody()).containsExactly(value);
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                }
                assertThat(channel.basicGet("cutover-capacity", false)).isNull();
            }
        }
    }

    enum State { COMPLETED }
    record Sample(UUID id, Instant observedAt, State state) {}
    @SpringBootConfiguration @EnableAutoConfiguration
    static class SmokeApplication {
        @Bean SecurityFilterChain testOnlySecurity(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.requestMatchers("/compatibility").permitAll().anyRequest().denyAll()).build();
        }
        @Bean SmokeController controller() { return new SmokeController(); }
    }
    @RestController static class SmokeController {
        @GetMapping("/compatibility") Sample sample() {
            return new Sample(UUID.fromString("00000000-0000-4000-8000-000000000001"), Instant.parse("2026-09-07T16:00:00Z"), State.COMPLETED);
        }
    }
}
