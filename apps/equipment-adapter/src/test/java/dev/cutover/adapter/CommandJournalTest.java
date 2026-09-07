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
        adapterDb.reset();simulatorDb.reset();clock=new MutableClock(Instant.now().plusSeconds(60));
        simulator=new SimulatorEngine(simulatorDb.sql(),clock);port=new Port();allocations=new Allocations(adapterDb.sql());
        observations=new EquipmentObservations(adapterDb.sql(),port,clock);journal=new CommandJournal(adapterDb.sql(),port,observations,clock);observations.refresh();
    }
    class Port implements EquipmentPort {
        boolean unavailable, falseAbsence;
        JsonNode override;
        @Override public JsonNode equipment() { if(unavailable)throw new Unavailable("Disconnected");return simulator.equipment(); }
        @Override public Reply command(UUID id) {
            if(unavailable)throw new Unavailable("Disconnected");
            if (falseAbsence) return new Reply(404,simulator.absence(id));
            if (override!=null) return new Reply(200,override);
            try{return new Reply(200,simulator.observedStatus(id));}catch(Problem absent){if(absent.status()!=404)throw absent;return new Reply(404,simulator.absence(id));}
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
    @Test void delayedAcceptedObservationCannotRegressExecutingAndDuplicateProofHasOneEffect() {
        UUID id=UUID.randomUUID(); record(id,movement(id)); simulator.fault("HOLD_EXECUTING",id,1,10000); journal.work();
        simulator.fault("STALE_STATUS",id,1,3000); tick();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("EXECUTING");
        tick();
        var stale=journal.get("site-a",id);
        assertThat(stale.path("state").asString()).isEqualTo("EXECUTING");
        assertThat(stale.path("lastError").asString()).isEqualTo("STALE_OBSERVATION");
        assertThat(stale.path("evidenceVersion").asLong()).isEqualTo(2);
        simulator.fault("DUPLICATE_RESPONSE",id,2,0); tick(); tick();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM outbox WHERE event_type='CommandAccepted.v1'").get(0,Integer.class)).isEqualTo(2);
        clock.advance(Duration.ofSeconds(10)); tick();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void sameVersionWithDifferentMeaningQuarantinesAndRetainsEarlierEvidence() {
        UUID id=UUID.randomUUID(); record(id,movement(id)); journal.work();
        var conflicting=(tools.jackson.databind.node.ObjectNode)simulator.status(id).deepCopy(); conflicting.put("state","EXECUTING"); port.override=conflicting;
        clock.advance(Duration.ofSeconds(2)); journal.work();
        var command=journal.get("site-a",id);
        assertThat(command.path("state").asString()).isEqualTo("QUARANTINED");
        assertThat(command.path("lastError").asString()).isEqualTo("CONTRADICTORY_OBSERVATION");
        assertThat(command.path("evidence").path("state").asString()).isEqualTo("ACCEPTED");
        assertThat(command.path("lastObservation").path("state").asString()).isEqualTo("EXECUTING");
    }
    JsonNode recovery(UUID id) { return JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",journal.get("site-a",id).path("version").asLong(),"reason","Transport restored; investigate retained command evidence.")); }
    @Test void exhaustedInvestigationRequiresAuditedVersionedRecoveryAndUsesTheOriginalCommand() {
        UUID id=UUID.randomUUID(); record(id,movement(id)); port.unavailable=true;
        for(int i=0;i<5;i++){clock.advance(Duration.ofSeconds(17));journal.work();}
        assertThat(journal.get("site-a",id).path("failureAttempts").asInt()).isEqualTo(5);
        assertThat(journal.work()).isZero();
        var request=recovery(id);
        assertThatThrownBy(()->journal.reconcile("supervisor","site-b",id,"recovery",request)).isInstanceOf(Problem.class).satisfies(e->assertThat(((Problem)e).status()).isEqualTo(404));
        var response=journal.reconcile("supervisor","site-a",id,"recovery",request);
        assertThat(journal.reconcile("supervisor","site-a",id,"recovery",request)).isEqualTo(response);
        assertThatThrownBy(()->journal.reconcile("supervisor","site-a",id,"another-key",request)).isInstanceOf(Problem.class).hasMessageContaining("changed");
        port.unavailable=false; observations.refresh(); journal.work(); tick();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(journal.reconcile("supervisor","site-a",id,"recovery",request)).isEqualTo(response);
        assertThatThrownBy(()->journal.reconcile("supervisor","site-a",id,"terminal",recovery(id))).isInstanceOf(Problem.class).hasMessageContaining("terminal");
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM audit WHERE action='command-investigation'").get(0,Integer.class)).isEqualTo(1);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger WHERE command_id=?",id).get(0,Integer.class)).isEqualTo(1);
    }
    @Test void aTemporaryHistoryGapCanBeInvestigatedButAcceptedHistoryCannotBeInvented() {
        UUID id=UUID.randomUUID(); record(id,movement(id)); simulator.fault("HISTORY_GAP",id,1,0); journal.work();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("QUARANTINED");
        journal.reconcile("supervisor","site-a",id,"history-recovered",recovery(id)); journal.work();
        assertThat(journal.get("site-a",id).path("acceptedEver").asBoolean()).isTrue();
        port.falseAbsence=true; clock.advance(Duration.ofSeconds(2)); journal.work();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("QUARANTINED");
        journal.reconcile("supervisor","site-a",id,"cannot-override-proof",recovery(id)); journal.work();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("QUARANTINED");
        assertThat(journal.get("site-a",id).path("attempts").asInt()).isEqualTo(1);
        port.falseAbsence=false; simulator.advance(); journal.reconcile("supervisor","site-a",id,"genuine-evidence-returned",recovery(id)); journal.work();
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");
    }
    @Test void cancellationFenceStopsAWorkerThatReturnsAfterItsStatusLeaseExpired() throws Exception {
        UUID id=UUID.randomUUID();JsonNode movement=movement(id);record(id,movement);
        var queried=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        EquipmentPort delayed=new EquipmentPort() {
            public JsonNode equipment(){return port.equipment();}
            public Reply command(UUID command){
                queried.countDown();
                try { if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Status barrier did not resume"); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Unavailable("Interrupted status");}
                return port.command(command);
            }
            public Reply send(UUID command,JsonNode payload){throw new AssertionError("A fenced movement was sent after lease expiry");}
        };
        var claimed=new CommandJournal(adapterDb.sql(),delayed,observations,clock);
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future=worker.submit(()->claimed.work());
            try {
                assertThat(queried.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofSeconds(11));observations.refresh();
                var request=JsonSupport.MAPPER.valueToTree(Map.of("cancellationId",UUID.randomUUID(),"orderId",UUID.randomUUID(),"siteId","site-a","actor","supervisor","reason","Fence never-submitted work after the old query lease expired.","movements",java.util.List.of(movement)));
                new CancellationGate(adapterDb.sql(),observations,clock).fence("site-a","legacy-core",request);
            } finally { resume.countDown(); }
            future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(journal.get("site-a",id).path("state").asString()).isEqualTo("REJECTED_BEFORE_EXECUTION");
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM simulator_commands").get(0,Integer.class)).isZero();
    }
}
