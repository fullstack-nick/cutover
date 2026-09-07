package dev.cutover.adapter;

import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.simulator.SimulatorEngine;
import dev.cutover.testing.DatabaseFixture;
import dev.cutover.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class CommandJournalTest {
    static DatabaseFixture adapterDb,simulatorDb;
    MutableClock clock; SimulatorEngine simulator; Port port; Allocations allocations; EquipmentObservations observations; CommandJournal journal;
    @BeforeAll static void databases() { adapterDb=new DatabaseFixture("equipment-adapter");simulatorDb=new DatabaseFixture("equipment-simulator"); }
    @AfterAll static void stop() { adapterDb.close();simulatorDb.close(); }
    @BeforeEach void reset() {
        adapterDb.reset();simulatorDb.reset();clock=new MutableClock(Instant.now().plusSeconds(1));
        simulator=new SimulatorEngine(simulatorDb.sql(),clock);port=new Port();allocations=new Allocations(adapterDb.sql());
        observations=new EquipmentObservations(adapterDb.sql(),port,clock);journal=new CommandJournal(adapterDb.sql(),port,observations,clock);observations.refresh();
    }
    class Port implements EquipmentPort {
        boolean unavailable;
        @Override public JsonNode equipment() { if(unavailable)throw new Unavailable("Disconnected");return simulator.equipment(); }
        @Override public Reply command(UUID id) {
            if(unavailable)throw new Unavailable("Disconnected");
            try{return new Reply(200,simulator.status(id));}catch(Problem absent){if(absent.status()!=404)throw absent;return new Reply(404,simulator.equipment());}
        }
        @Override public Reply send(UUID id,JsonNode payload) {
            try {
                var accepted=simulator.accept(id,payload);
                if(accepted.responseDelayMillis()>0)throw new Unavailable("Response lost after execution");
                return new Reply(200,accepted.result());
            }catch(SimulatorEngine.DisconnectBeforeAcceptance fault){throw new Unavailable("Disconnect before acceptance");}
        }
    }
    JsonNode movement(UUID id) {
        var map=new java.util.LinkedHashMap<String,Object>();
        map.putAll(Map.of("movementId",id,"reservationId",id,"siteId","site-a","product","fulfilment","zoneId","ambient","loadId",UUID.randomUUID(),"source","bin-SKU-001","destination","outbound-staging","quantity",3));
        map.put("priority",5);map.put("eligibleAt",clock.instant());return JsonSupport.MAPPER.valueToTree(map);
    }
    JsonNode record(UUID id,JsonNode movement) {
        var allocation=allocations.register("site-a","legacy-core",movement);
        return journal.record("site-a","legacy-core",id,Database.uuid(allocation,"allocationId"),0,"ambient-a",movement);
    }
    void tick() { clock.advance(Duration.ofSeconds(2));simulator.advance();observations.refresh();journal.work(); }

    @Test void acceptedAndCompletedAreSeparateAndTerminalStateIsRetained() {
        UUID id=UUID.randomUUID();var movement=movement(id);record(id,movement);journal.work();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("ACCEPTED_BY_SIMULATOR");
        tick();record(id,movement);
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM outbox WHERE event_type='MovementCompleted.v1'").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void lostResponseIsUnknownThenReconciledWithoutAnotherPhysicalEffect() {
        UUID id=UUID.randomUUID();record(id,movement(id));simulator.fault("LOST_RESPONSE",id,1,5000);journal.work();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        journal=new CommandJournal(adapterDb.sql(),port,observations,clock);tick();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(journal.get("site-a",id).path("attempts").asInt()).isEqualTo(1);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void ProvenAbsenceAllowsSameIdResendAfterDisconnectBeforeAcceptance() {
        UUID id=UUID.randomUUID();record(id,movement(id));simulator.fault("BEFORE_ACCEPT",id,1,0);journal.work();tick();tick();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(journal.get("site-a",id).path("attempts").asInt()).isEqualTo(2);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void shadowStaleOwnerAndChangedPayloadCannotDispatch() {
        UUID id=UUID.randomUUID();var movement=movement(id);var allocation=allocations.register("site-a","legacy-core",movement);UUID assigned=Database.uuid(allocation,"allocationId");
        assertThatThrownBy(()->journal.record("site-a","shadow-scheduler",id,assigned,0,"ambient-a",movement)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->journal.record("site-a","execution-service",id,assigned,0,"ambient-a",movement)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->journal.record("site-a","legacy-core",id,assigned,1,"ambient-a",movement)).isInstanceOf(Problem.class);
        var changed=(tools.jackson.databind.node.ObjectNode)movement.deepCopy();changed.put("quantity",99);
        assertThatThrownBy(()->journal.record("site-a","legacy-core",id,assigned,0,"ambient-a",changed)).isInstanceOf(Problem.class);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal").get(0,Integer.class)).isZero();
    }
    @Test void WorldIdentityChangeQuarantinesRatherThanResending() {
        UUID id=UUID.randomUUID();record(id,movement(id));simulator.fault("BEFORE_ACCEPT",id,1,0);journal.work();
        simulatorDb.sql().execute("UPDATE simulation_world SET world_id=gen_random_uuid()");tick();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("QUARANTINED");
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM simulator_commands").get(0,Integer.class)).isZero();
    }
}
