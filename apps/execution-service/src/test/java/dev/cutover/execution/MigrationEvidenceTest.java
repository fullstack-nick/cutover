package dev.cutover.execution;

import dev.cutover.core.OrderService;
import dev.cutover.platform.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class MigrationEvidenceTest {
    private ExecutionWorkflowTest fixture;
    @BeforeAll static void databases() { ExecutionWorkflowTest.databases(); }
    @AfterAll static void stop() { ExecutionWorkflowTest.stop(); }
    @BeforeEach void reset() { fixture = new ExecutionWorkflowTest(); fixture.reset(); }

    private JsonNode request(UUID... identities) {
        return JsonSupport.MAPPER.valueToTree(Map.of("movementIds", List.of(identities)));
    }

    @Test void settledEvidenceKeepsInventoryAndTaskOwnersDistinct() {
        UUID order = fixture.accept("migration-evidence", new OrderService.Line("SKU-013", 2), new OrderService.Line("SKU-014", 1));
        fixture.finish(order);
        var core = new dev.cutover.core.MigrationEvidence(ExecutionWorkflowTest.core.sql(), fixture.clock);
        var execution = new MigrationEvidence(ExecutionWorkflowTest.execution.sql(), fixture.clock);
        for (var movement : fixture.orders.get("site-a", order).path("movements")) {
            UUID id = Database.uuid(movement, "movementId");
            String zone = movement.path("movement").path("zoneId").asString();
            var inventory = core.read("site-a", zone, request(id)).path("items").get(0);
            assertThat(inventory.path("state").asString()).isEqualTo("COMPLETED");
            assertThat(inventory.path("reservation").path("state").asString()).isEqualTo("CONSUMED");
            assertThat(inventory.path("inventoryEffect").path("executionSequence").asLong()).isPositive();
            if (zone.equals("ambient")) {
                assertThat(inventory.path("task").isNull()).isTrue();
                var task = execution.read("site-a", zone, request(id)).path("items").get(0).path("task");
                assertThat(task.path("state").asString()).isEqualTo("COMPLETED");
                assertThat(task.path("payloadHash").asString()).isEqualTo(JsonSupport.hash(inventory.path("movement")));
            } else assertThat(inventory.path("task").path("owner").asString()).isEqualTo("legacy-core");
        }
        assertThat(fixture.count(ExecutionWorkflowTest.core, "inventory_ledger")).isEqualTo(2);
        assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isEqualTo(2);
    }

    @Test void missingOrWrongScopeEvidenceIsExplicitAndRequestsAreBounded() {
        UUID order = fixture.accept("pending-evidence", new OrderService.Line("SKU-015", 1));
        UUID id = fixture.movement(order);
        var core = new dev.cutover.core.MigrationEvidence(ExecutionWorkflowTest.core.sql(), fixture.clock);
        var execution = new MigrationEvidence(ExecutionWorkflowTest.execution.sql(), fixture.clock);
        var pending = core.read("site-a", "ambient", request(id)).path("items").get(0);
        assertThat(pending.path("state").asString()).isEqualTo("REQUESTED");
        assertThat(pending.path("inventoryEffect").isNull()).isTrue();
        assertThat(core.read("site-b", "ambient", request(id)).path("items").get(0).path("missing").asBoolean()).isTrue();
        assertThat(core.read("site-a", "chilled", request(id)).path("items").get(0).path("missing").asBoolean()).isTrue();
        assertThat(execution.read("site-a", "ambient", request(id)).path("items").get(0).path("missing").asBoolean()).isTrue();
        assertThatThrownBy(() -> core.read("site-a", "ambient", request(id, id))).isInstanceOf(Problem.class);
        var oversized = java.util.stream.IntStream.range(0, 65).mapToObj(index -> UUID.randomUUID()).toArray(UUID[]::new);
        assertThatThrownBy(() -> execution.read("site-a", "ambient", request(oversized))).isInstanceOf(Problem.class);
    }
}
