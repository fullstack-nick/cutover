package dev.cutover.execution;

import dev.cutover.adapter.MigrationSessions;
import dev.cutover.core.OrderService;
import dev.cutover.platform.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Real owner effects and durable messages; service ports are controlled for bounded crash/retry tests. */
class MigrationWorkflowTest {
    private ExecutionWorkflowTest fixture;
    private MigrationSessions migrations;
    private AtomicBoolean unavailable;
    private final AtomicReference<String> crashAt = new AtomicReference<>();
    private static final class Crash extends RuntimeException {}
    @BeforeAll static void databases() { ExecutionWorkflowTest.databases(); }
    @AfterAll static void stop() { ExecutionWorkflowTest.stop(); }
    @BeforeEach void reset() {
        fixture = new ExecutionWorkflowTest(); fixture.reset(); unavailable = new AtomicBoolean(); crashAt.set(null);
        ExecutionWorkflowTest.adapter.sql().execute("UPDATE zone_routes SET owner='legacy-core',epoch=0 WHERE site_id='site-a' AND zone_id='ambient'");
        migrations = service();
    }
    private MigrationSessions service() {
        return new MigrationSessions(ExecutionWorkflowTest.adapter.sql(), (owner, site, zone, request) -> {
            if (unavailable.get()) throw new ServiceHttp.Unavailable("Owner connection is unavailable");
            return owner.equals("legacy-core") ? new dev.cutover.core.MigrationEvidence(ExecutionWorkflowTest.core.sql(), fixture.clock).read(site, zone, request)
                : new MigrationEvidence(ExecutionWorkflowTest.execution.sql(), fixture.clock).read(site, zone, request);
        }, fixture.observations, fixture.clock, (phase, id) -> { if (crashAt.compareAndSet(phase, null)) throw new Crash(); });
    }
    private JsonNode request(String owner) {
        long version = ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT version FROM zone_routes WHERE site_id='site-a' AND zone_id='ambient'").get(0, Long.class);
        return JsonSupport.MAPPER.createObjectNode().put("expectedVersion", version).put("targetOwner", owner).put("reason", "Verify the complete zone drain and retained ownership evidence.");
    }
    private UUID start(String owner) { return Database.uuid(migrations.start("supervisor-a", "site-a", "ambient", UUID.randomUUID().toString(), request(owner)), "sessionId"); }
    private String phase(UUID id) {
        var view = migrations.get("site-a", id);
        Contracts.validate("migration-view.v1", JsonSupport.write(view));
        return view.path("phase").asString();
    }
    private void tick() { fixture.tick(); migrations.poll(); }
    private void reach(UUID id, String expected) {
        for (int n = 0; n < 60 && !phase(id).equals(expected); n++) tick();
        assertThat(phase(id)).describedAs(migrations.get("site-a", id).toString()).isEqualTo(expected);
    }
    private void sample(String prefix) { for (int n = 0; n < 10; n++) fixture.accept(prefix + n, new OrderService.Line("SKU-017", 1)); }

    @Test void allocatedTasksBlockTheDrainWhileTheOtherZoneContinuesAndPendingIntentsWait() {
        fixture.simulator.blockLane("site-a", "ambient-a", true); fixture.simulator.blockLane("site-a", "ambient-b", true);
        UUID old = fixture.accept("old-allocated", new OrderService.Line("SKU-019", 1)); fixture.messages();
        UUID session = start("execution-service");
        UUID pending = fixture.accept("during-drain", new OrderService.Line("SKU-021", 1));
        UUID other = fixture.accept("other-zone", new OrderService.Line("SKU-022", 1));
        for (int n = 0; n < 8; n++) tick();
        assertThat(phase(session)).isEqualTo("DRAINING");
        assertThat(migrations.get("site-a", session).path("blockers").path("count").asInt()).isEqualTo(1);
        assertThat(fixture.orders.get("site-a", other).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(ExecutionWorkflowTest.core.sql().fetchOne("SELECT count(*) FROM legacy_tasks WHERE order_id=?", pending).get(0, Integer.class)).isZero();
        assertThat(fixture.allocations.get("site-a", fixture.movement(pending)).path("state").asString()).isEqualTo("PENDING");
        fixture.simulator.blockLane("site-a", "ambient-a", false); reach(session, "OBSERVING");
        sample("forward-sample"); reach(session, "COMPLETED");
        assertThat(fixture.orders.get("site-a", old).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(fixture.allocations.get("site-a", fixture.movement(pending)).path("owner").asString()).isEqualTo("execution-service");
        assertThat(migrations.get("site-a", session).path("observation").path("sampleCount").asInt()).isEqualTo(10);
        var fractional = migrations.get("site-a", session).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) fractional.path("observation")).put("dispatchP99Millis", 592.983);
        Contracts.validate("migration-view.v1", JsonSupport.write(fractional));
        assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isEqualTo(fixture.count(ExecutionWorkflowTest.core, "inventory_ledger"));
    }

    @Test void everyRecordedPhaseSurvivesAWorkerCrashWithoutChangingAuthorityTwice() {
        fixture.accept("initial-proof", new OrderService.Line("SKU-023", 1)); for (int n = 0; n < 8; n++) fixture.tick();
        crashAt.set("DRAINING"); var startBody = request("execution-service");
        assertThatThrownBy(() -> migrations.start("supervisor-a", "site-a", "ambient", "crash-start", startBody)).isInstanceOf(Crash.class);
        UUID id = Database.uuid(service().start("supervisor-a", "site-a", "ambient", "crash-start", startBody), "sessionId");
        for (String target : List.of("RECONCILING", "READY_TO_SWITCH", "OBSERVING", "COMPLETED")) {
            if (target.equals("COMPLETED")) sample("crash-sample");
            crashAt.set(target); boolean fired = false;
            for (int n = 0; n < 60 && !fired; n++) {
                try { tick(); } catch (Crash crash) { fired = true; migrations = service(); }
            }
            assertThat(fired).describedAs(target).isTrue(); assertThat(phase(id)).isEqualTo(target);
        }
        assertThat(ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT epoch,owner FROM zone_routes WHERE site_id='site-a' AND zone_id='ambient'").intoArray()).containsExactly(1L, "execution-service");
        assertThat(ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT count(*) FROM outbox WHERE event_type='ZoneOwnershipChanged.v1'").get(0, Integer.class)).isEqualTo(1);
    }

    @Test void twoSupervisorsCannotStartTwoSessionsForTheSameRouteVersion() throws Exception {
        var body = request("execution-service"); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = new ArrayList<Future<Boolean>>();
            for (String actor : List.of("first-supervisor", "second-supervisor")) outcomes.add(executor.submit(() -> {
                start.await(); try { migrations.start(actor, "site-a", "ambient", actor, body); return true; }
                catch (Problem conflict) { assertThat(conflict.status()).isEqualTo(409); return false; }
            }));
            start.countDown(); int successes = 0; for (var outcome : outcomes) if (outcome.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(fixture.count(ExecutionWorkflowTest.adapter, "migration_sessions")).isEqualTo(1);
    }

    @Test void unknownOutcomeBlocksAnExplicitReversalWithoutAnyTimeoutForce() {
        UUID forward = start("execution-service"); reach(forward, "OBSERVING");
        UUID order = fixture.accept("reverse-unknown", new OrderService.Line("SKU-025", 1)); fixture.messages(); fixture.scheduler.poll();
        fixture.loseEquipmentReply.set(true); fixture.journal.work();
        UUID movement = fixture.movement(order); assertThat(fixture.journal.get("site-a", movement).path("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        UUID reverse = start("legacy-core");
        fixture.clock.advance(Duration.ofHours(2)); fixture.observations.refresh(); migrations.poll();
        assertThat(phase(forward)).isEqualTo("REVERSING"); assertThat(phase(reverse)).isEqualTo("DRAINING");
        assertThat(migrations.get("site-a", reverse).path("blockers").path("items").get(0).path("reason").asString()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(fixture.allocations.get("site-a", movement).path("epoch").asLong()).isEqualTo(1);
        reach(reverse, "OBSERVING"); sample("reverse-sample"); reach(reverse, "COMPLETED");
        assertThat(phase(forward)).isEqualTo("REVERSED");
        assertThat(ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT epoch,owner FROM zone_routes WHERE site_id='site-a' AND zone_id='ambient'").intoArray()).containsExactly(2L, "legacy-core");
        assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isEqualTo(11);
    }

    @Test void exhaustedEvidenceCollectionResumesWithItsOriginalSessionAndCheckpointInventory() {
        UUID order = fixture.accept("evidence-outage", new OrderService.Line("SKU-027", 1)); fixture.finish(order);
        UUID id = start("execution-service"); reach(id, "RECONCILING"); unavailable.set(true);
        for (int n = 0; n < 6; n++) { fixture.clock.advance(Duration.ofSeconds(20)); fixture.observations.refresh(); migrations.poll(); }
        var paused = migrations.get("site-a", id); assertThat(paused.path("transportPaused").asBoolean()).isTrue();
        assertThat(service().poll()).isZero();
        var body = JsonSupport.MAPPER.createObjectNode().put("expectedVersion", paused.path("version").asLong()).put("reason", "The owner connection is restored; resume this retained inventory.");
        var first = migrations.resume("new-supervisor", "site-a", id, "resume", body);
        assertThat(migrations.resume("new-supervisor", "site-a", id, "resume", body)).isEqualTo(first);
        unavailable.set(false); reach(id, "OBSERVING"); sample("recovered-sample"); reach(id, "COMPLETED");
        assertThat(migrations.get("site-a", id).path("inventoryHash")).isEqualTo(paused.path("inventoryHash"));
    }

    @Test void cancellingAnUnswitchedDrainReleasesUnassignedWorkToTheUnchangedOwner() {
        UUID id = start("execution-service"); UUID order = fixture.accept("cancel-drain", new OrderService.Line("SKU-029", 1)); fixture.messages();
        assertThat(fixture.allocations.get("site-a", fixture.movement(order)).path("state").asString()).isEqualTo("PENDING");
        var session = migrations.get("site-a", id); var body = JsonSupport.MAPPER.createObjectNode().put("expectedVersion", session.path("version").asLong()).put("reason", "Cancel this unswitched maintenance request and retain the current owner.");
        migrations.cancel("supervisor-a", "site-a", id, "cancel", body); fixture.finish(order);
        assertThat(phase(id)).isEqualTo("CANCELLED"); assertThat(fixture.allocations.get("site-a", fixture.movement(order)).path("owner").asString()).isEqualTo("legacy-core");
        assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isEqualTo(1);
    }

    @Test void cancellationDuringDrainCannotCreateANewOldOwnerAllocation() {
        UUID session = start("execution-service");
        UUID order = fixture.accept("cancel-unassigned", new OrderService.Line("SKU-031", 1));
        var gate = new dev.cutover.adapter.CancellationGate(ExecutionWorkflowTest.adapter.sql(), fixture.observations, fixture.clock);
        var cancellations = new dev.cutover.core.OrderCancellations(ExecutionWorkflowTest.core.sql(), new dev.cutover.core.DispatchPort() {
            public JsonNode allocate(String site, JsonNode movement) { throw new AssertionError(); }
            public JsonNode command(String site, UUID movement) { throw new AssertionError(); }
            public JsonNode equipment(String site) { throw new AssertionError(); }
            public JsonNode dispatch(String site, UUID movement, UUID allocation, long epoch, String lane, JsonNode payload) { throw new AssertionError(); }
            public JsonNode cancellation(String site, JsonNode request) { return gate.fence(site, "legacy-core", request); }
        }, fixture.clock);
        var body = JsonSupport.MAPPER.createObjectNode().put("expectedVersion", fixture.orders.get("site-a", order).path("version").asLong())
            .put("reason", "Cancel the unassigned movement without introducing any old-owner work.");
        cancellations.cancel("supervisor-a", "site-a", order, "cancel-unassigned", body);
        var cancelled = fixture.allocations.get("site-a", fixture.movement(order));
        assertThat(cancelled.path("state").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("owner").isNull()).isTrue(); assertThat(cancelled.path("epoch").isNull()).isTrue();
        fixture.messages(); reach(session, "OBSERVING");
        assertThat(migrations.get("site-a", session).path("inventoryCount").asInt()).isZero();
        assertThat(fixture.count(ExecutionWorkflowTest.core, "legacy_tasks")).isZero();
        assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isZero();
    }

    @Test void delayedOldOwnerRequestsReturnOnlyTheirPriorTerminalResultAndCannotDispatchNewWork() {
        UUID oldOrder = fixture.accept("old-terminal", new OrderService.Line("SKU-033", 1)); fixture.finish(oldOrder);
        UUID old = fixture.movement(oldOrder); var original = fixture.allocations.get("site-a", old); var command = fixture.journal.get("site-a", old);
        UUID session = start("execution-service"); reach(session, "OBSERVING");
        var repeated = fixture.journal.record("site-a", "legacy-core", old, Database.uuid(original, "allocationId"), 0,
            command.path("payload").path("laneId").asString(), original.path("movement"));
        assertThat(repeated.path("state").asString()).isEqualTo("COMPLETED");
        UUID nextOrder = fixture.accept("new-owner-only", new OrderService.Line("SKU-035", 1)); fixture.messages();
        UUID next = fixture.movement(nextOrder); var allocation = fixture.allocations.get("site-a", next);
        assertThatThrownBy(() -> fixture.journal.record("site-a", "legacy-core", next, Database.uuid(allocation, "allocationId"), 0,
            "ambient-a", allocation.path("movement"))).isInstanceOf(Problem.class)
            .satisfies(error -> assertThat(((Problem) error).code()).isEqualTo("STALE_OWNER"));
        assertThat(fixture.count(ExecutionWorkflowTest.adapter, "command_journal")).isEqualTo(1);
        fixture.finish(nextOrder); assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isEqualTo(2);
    }

    @Test void originalTriggerBehaviorCannotPassTheOwnershipCheckpoint() {
        ExecutionWorkflowTest.core.sql().execute("DROP TRIGGER reservation_creates_movement_intent ON reservations");
        ExecutionWorkflowTest.core.sql().execute("CREATE TRIGGER reservation_creates_legacy_task AFTER INSERT ON reservations FOR EACH ROW EXECUTE FUNCTION create_legacy_task()");
        UUID session = start("execution-service");
        for (int n = 0; n < 8; n++) tick();
        assertThat(phase(session)).isEqualTo("RECONCILING");
        assertThat(migrations.get("site-a", session).path("lastError").asString()).isEqualTo("TASK_CREATION_BOUNDARY_REQUIRED");
        assertThat(ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT epoch FROM zone_routes WHERE site_id='site-a' AND zone_id='ambient'").get(0, Long.class)).isZero();
    }

    @Test void successfulReversalOfAFailedReversalSettlesTheWholeLineageWithoutReplacingFailedSamples() {
        UUID first = start("execution-service"); reach(first, "OBSERVING");
        sample("first-slow"); fixture.messages(); fixture.clock.advance(Duration.ofSeconds(3));
        for (int n = 0; n < 12; n++) tick();
        var firstFailure = migrations.get("site-a", first).path("observation").deepCopy();
        assertThat(firstFailure.path("withinTwoSeconds").asBoolean()).isFalse();
        assertThat(migrations.get("site-a", first).path("lastError").asString()).isEqualTo("OBSERVATION_LATENCY_TARGET_MISSED");
        assertThat(migrations.poll()).isZero();
        UUID reverse = start("legacy-core"); reach(reverse, "OBSERVING");
        sample("reverse-slow"); fixture.messages(); fixture.clock.advance(Duration.ofSeconds(3));
        for (int n = 0; n < 12; n++) tick();
        var reverseFailure = migrations.get("site-a", reverse).path("observation").deepCopy();
        assertThat(reverseFailure.path("withinTwoSeconds").asBoolean()).isFalse();
        UUID finalSession = start("execution-service"); reach(finalSession, "OBSERVING");
        sample("final-good"); reach(finalSession, "COMPLETED");
        assertThat(phase(first)).isEqualTo("SUPERSEDED"); assertThat(phase(reverse)).isEqualTo("REVERSED");
        assertThat(migrations.get("site-a", first).path("observation")).isEqualTo(firstFailure);
        assertThat(migrations.get("site-a", reverse).path("observation")).isEqualTo(reverseFailure);
        assertThat(ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT count(*) FROM migration_sessions WHERE phase='REVERSING'").get(0,Integer.class)).isZero();
        assertThat(ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT count(*) FROM audit WHERE action='migration-lineage-settled'").get(0,Integer.class)).isEqualTo(2);
    }

    @Test void aJournalTransactionThatCommitsLateCannotPassTheMigrationTimingGate() {
        UUID session = start("execution-service"); reach(session, "OBSERVING");
        sample("late-journal-commit"); fixture.messages();
        ExecutionWorkflowTest.adapter.sql().transaction(configuration -> {
            var sql = org.jooq.impl.DSL.using(configuration);
            var journal = new dev.cutover.adapter.CommandJournal(sql, null, fixture.observations, fixture.clock);
            for (var row : sql.fetch("SELECT movement_id,allocation_id,epoch,movement FROM movement_allocations WHERE site_id='site-a' AND zone_id='ambient' AND owner='execution-service'")) {
                journal.record("site-a", "execution-service", row.get("movement_id", UUID.class),
                    row.get("allocation_id", UUID.class), row.get("epoch", Long.class), "ambient-a",
                    JsonSupport.read(row.get("movement").toString()));
            }
            assertThat(fixture.count(ExecutionWorkflowTest.adapter, "command_journal"))
                .as("A separate connection cannot see these uncommitted journal rows").isZero();
            assertThat(fixture.count(ExecutionWorkflowTest.physical, "simulator_commands")).isZero();
            fixture.clock.advance(Duration.ofSeconds(3));
        });
        for (int n = 0; n < 12; n++) tick();
        assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isEqualTo(10);
        assertThat(fixture.count(ExecutionWorkflowTest.core, "inventory_ledger")).isEqualTo(10);
        assertThat(migrations.get("site-a", session).path("lastError").asString())
            .as("Early row creation must not qualify a journal commit that happened after the deadline")
            .isEqualTo("OBSERVATION_LATENCY_TARGET_MISSED");
        assertThat(phase(session)).isEqualTo("OBSERVING");
        assertThat(migrations.get("site-a", session).path("observation").path("dispatchP99Millis").asDouble())
            .isGreaterThanOrEqualTo(3000);
    }

    @Test void missingOrContradictoryPhysicalTimingCannotQualifyTheRetainedSample() {
        UUID session = start("execution-service"); reach(session, "OBSERVING");
        sample("physical-timing-proof");
        for (int n = 0; n < 12; n++) fixture.tick();
        var row = ExecutionWorkflowTest.adapter.sql().fetchOne("SELECT command_id,evidence FROM command_journal ORDER BY command_id LIMIT 1");
        var original = JsonSupport.read(row.get("evidence").toString());
        var invalidTimes = Arrays.asList(null, "not-a-time", fixture.clock.instant().minusSeconds(86400).toString(),
            fixture.clock.instant().plusSeconds(86400).toString());
        for (String invalidTime : invalidTimes) {
            var evidence = (tools.jackson.databind.node.ObjectNode) original.deepCopy();
            if (invalidTime == null) evidence.remove("acceptedAt"); else evidence.put("acceptedAt", invalidTime);
            ExecutionWorkflowTest.adapter.sql().execute("UPDATE command_journal SET evidence=?::jsonb WHERE command_id=?",
                JsonSupport.write(evidence), row.get("command_id"));
            fixture.clock.advance(Duration.ofSeconds(2)); fixture.observations.refresh(); migrations.poll();
            assertThat(phase(session)).isEqualTo("OBSERVING");
            assertThat(migrations.get("site-a", session).path("lastError").asString())
                .isIn("OBSERVATION_TIMING_PROOF_MISSING", "OBSERVATION_CLOCK_ORDER");
        }
        ExecutionWorkflowTest.adapter.sql().execute("UPDATE command_journal SET evidence=?::jsonb WHERE command_id=?",
            JsonSupport.write(original), row.get("command_id"));
        fixture.clock.advance(Duration.ofSeconds(2)); fixture.observations.refresh(); migrations.poll();
        assertThat(phase(session)).isEqualTo("COMPLETED");
        assertThat(migrations.get("site-a", session).path("observation").path("timingBasis").asString())
            .isEqualTo("SIMULATOR_ACCEPTANCE_UPPER_BOUND");
        assertThat(fixture.count(ExecutionWorkflowTest.physical, "execution_ledger")).isEqualTo(10);
        assertThat(fixture.count(ExecutionWorkflowTest.core, "inventory_ledger")).isEqualTo(10);
    }
}
