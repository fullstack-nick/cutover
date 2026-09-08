package dev.cutover.simulator;

import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.testing.DatabaseFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class SimulatorEngineTest {
    static DatabaseFixture fixture;
    SimulatorEngine engine;
    static final Clock NOW=Clock.fixed(Instant.parse("2026-09-07T16:00:00Z"),ZoneOffset.UTC);
    @BeforeAll static void start() { fixture=new DatabaseFixture("equipment-simulator"); }
    @AfterAll static void stop() { fixture.close(); }
    @BeforeEach void reset() { fixture.reset(); engine=new SimulatorEngine(fixture.sql(),NOW); }

    JsonNode command(UUID id, String lane) {
        var world=engine.equipment();
        return JsonSupport.MAPPER.valueToTree(new SimulatorEngine.Command(id,UUID.randomUUID(),id,"site-a",UUID.randomUUID(),
                UUID.fromString(world.get("worldId").asString()),UUID.fromString(world.get("journalGeneration").asString()),0,
                "bin-SKU-001","outbound-staging","ambient",lane,3));
    }
    void advance() { new SimulatorEngine(fixture.sql(),Clock.offset(NOW,java.time.Duration.ofSeconds(1))).advance(); }

    @Test void repeatedStableCommandAndEngineRestartProduceOnePhysicalEffect() {
        UUID id=UUID.randomUUID(); var command=command(id,"ambient-a");
        assertThat(engine.accept(id,command).result().get("state").asString()).isEqualTo("ACCEPTED");
        engine.accept(id,command); advance();
        engine=new SimulatorEngine(fixture.sql(),NOW); engine.accept(id,command); advance();
        assertThat(engine.status(id).get("state").asString()).isEqualTo("COMPLETED");
        assertThat(fixture.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
        assertThat(fixture.sql().fetchOne("SELECT version FROM loads").get(0,Long.class)).isEqualTo(1);
    }
    @Test void changedPayloadIsRejectedBeforeASecondEffect() {
        UUID id=UUID.randomUUID(); var command=command(id,"ambient-a"); engine.accept(id,command);
        var changed=(tools.jackson.databind.node.ObjectNode)command.deepCopy(); changed.put("quantity",4);
        assertThatThrownBy(()->engine.accept(id,changed)).isInstanceOf(Problem.class).hasMessageContaining("cannot change");
        advance(); assertThat(fixture.sql().fetchOne("SELECT sum(quantity) FROM execution_ledger").get(0,Integer.class)).isEqualTo(3);
    }
    @Test void blockedLaneDoesNotPreventOtherLaneProgress() {
        UUID first=UUID.randomUUID(), second=UUID.randomUUID(); engine.blockLane("site-a","ambient-a",true);
        engine.accept(first,command(first,"ambient-a")); engine.accept(second,command(second,"ambient-b")); advance();
        assertThat(engine.status(first).get("state").asString()).isEqualTo("ACCEPTED");
        assertThat(engine.status(second).get("state").asString()).isEqualTo("COMPLETED");
        engine.blockLane("site-a","ambient-a",false); advance();
        assertThat(fixture.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void lostResponseHasDurableCompletionAndBeforeAcceptanceHasNoCommand() {
        UUID lost=UUID.randomUUID(); engine.fault("LOST_RESPONSE",lost,1,5000);
        var accepted=engine.accept(lost,command(lost,"ambient-a"));
        assertThat(accepted.responseDelayMillis()).isEqualTo(5000);
        assertThat(engine.status(lost).get("state").asString()).isEqualTo("COMPLETED");
        UUID missing=UUID.randomUUID(); var request=command(missing,"ambient-a"); engine.fault("BEFORE_ACCEPT",missing,1,0);
        assertThatThrownBy(()->engine.accept(missing,request)).isInstanceOf(SimulatorEngine.DisconnectBeforeAcceptance.class);
        assertThatThrownBy(()->engine.status(missing)).isInstanceOf(Problem.class);
        engine.accept(missing,request); advance();
        assertThat(fixture.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void changedWorldAndUnknownHistoryBlockAcceptance() {
        UUID id=UUID.randomUUID(); var command=(tools.jackson.databind.node.ObjectNode)command(id,"ambient-a");
        command.put("worldId",UUID.randomUUID().toString());
        assertThatThrownBy(()->engine.accept(id,command)).isInstanceOf(Problem.class).hasMessageContaining("history");
        var correct=command(id,"ambient-a"); fixture.sql().execute("UPDATE simulation_world SET complete_history=false");
        assertThatThrownBy(()->engine.accept(id,correct)).isInstanceOf(Problem.class);
        assertThat(fixture.sql().fetchOne("SELECT count(*) FROM simulator_commands").get(0,Integer.class)).isZero();
    }
    @Test void recoveryInventoryIncludesUnfinishedCommandsAndPagesWithoutChangingPhysicalState() {
        UUID first=UUID.fromString("10000000-0000-0000-0000-000000000001"),second=UUID.fromString("90000000-0000-0000-0000-000000000002");
        engine.blockLane("site-a","ambient-a",true);
        var blocked=command(first,"ambient-a");engine.accept(first,blocked);engine.accept(second,command(second,"ambient-b"));advance();
        var page=engine.recoveryInventory(null,1);
        assertThat(page.path("totalCommands").asInt()).isEqualTo(2);assertThat(page.path("journalHighWater").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("command_id").asString()).isEqualTo(first.toString());
        assertThat(page.path("items").get(0).path("state").asString()).isEqualTo("ACCEPTED");
        assertThat(JsonSupport.hash(page.path("items").get(0).path("payload"))).isEqualTo(JsonSupport.hash(blocked));
        var next=engine.recoveryInventory(UUID.fromString(page.path("nextCursor").asString()),1);
        assertThat(next.path("items").get(0).path("command_id").asString()).isEqualTo(second.toString());
        assertThat(next.path("items").get(0).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(next.path("items").get(0).path("execution_sequence").asInt()).isEqualTo(1);
        assertThat(engine.recoveryInventory(second,32).path("items").size()).isZero();
        assertThatThrownBy(()->engine.recoveryInventory(null,33)).isInstanceOf(Problem.class);
        assertThat(engine.status(first).path("state").asString()).isEqualTo("ACCEPTED");
        assertThat(fixture.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void executionHoldSurvivesEngineRestartAndRetainsAtomicLoadLedger() {
        UUID id=UUID.randomUUID(); engine.fault("HOLD_EXECUTING",id,1,10000); engine.accept(id,command(id,"ambient-a")); advance();
        assertThat(engine.status(id).path("state").asString()).isEqualTo("EXECUTING");
        assertThat(fixture.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isZero();
        assertThat(fixture.sql().fetchOne("SELECT version FROM loads").get(0,Long.class)).isZero();
        new SimulatorEngine(fixture.sql(),Clock.offset(NOW,java.time.Duration.ofSeconds(5))).advance();
        assertThat(engine.status(id).path("state").asString()).isEqualTo("EXECUTING");
        new SimulatorEngine(fixture.sql(),Clock.offset(NOW,java.time.Duration.ofSeconds(12))).advance();
        assertThat(engine.status(id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(fixture.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
        assertThat(fixture.sql().fetchOne("SELECT version FROM loads").get(0,Long.class)).isEqualTo(1);
    }
    @Test void faultsAreObservableAndClearableWithoutChangingTheWorldOrCommandHistory() {
        UUID id=UUID.randomUUID(); var world=engine.equipment();
        UUID fault=engine.fault("WORLD_MISMATCH",id,2,0);
        assertThat(engine.absence(id).path("worldId")).isNotEqualTo(world.path("worldId"));
        assertThat(engine.faults().get(0).path("remaining").asInt()).isEqualTo(1);
        engine=new SimulatorEngine(fixture.sql(),NOW); engine.clearFault(fault);
        assertThat(engine.absence(id).path("worldId")).isEqualTo(world.path("worldId"));
        assertThat(engine.faults().get(0).path("clearedAt").isNull()).isFalse();
        engine.fault("DELAY_RESPONSE",id,1,5000);
        assertThat(engine.accept(id,command(id,"ambient-a")).result().path("state").asString()).isEqualTo("ACCEPTED");
        advance(); assertThat(engine.status(id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(engine.equipment().path("worldId")).isEqualTo(world.path("worldId"));
    }
}
