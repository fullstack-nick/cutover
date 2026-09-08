package dev.cutover.adapter;

import dev.cutover.platform.*;
import dev.cutover.testing.*;
import dev.cutover.simulator.SimulatorEngine;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class RestoreReconciliationTest {
    static DatabaseFixture adapterDb,simulatorDb;
    MutableClock clock;SimulatorEngine simulator;EquipmentPort port;CommandJournal journal;Allocations allocations;RestoreReconciliation recovery;
    @BeforeAll static void start(){adapterDb=new DatabaseFixture("equipment-adapter");simulatorDb=new DatabaseFixture("equipment-simulator");}
    @AfterAll static void stop(){adapterDb.close();simulatorDb.close();}
    @BeforeEach void reset(){
        adapterDb.reset();simulatorDb.reset();clock=new MutableClock(Instant.now().plusSeconds(2));simulator=new SimulatorEngine(simulatorDb.sql(),clock);
        port=new EquipmentPort(){
            public JsonNode equipment(){return observed(simulator.equipment());}
            public Reply command(UUID id){try{return new Reply(200,simulator.status(id));}catch(Problem absent){if(absent.status()!=404)throw absent;return new Reply(404,observed(simulator.absence(id)));}}
            public Reply send(UUID id,JsonNode body){return new Reply(200,simulator.accept(id,body).result());}
        };
        var observations=new EquipmentObservations(adapterDb.sql(),port,clock);observations.refresh();allocations=new Allocations(adapterDb.sql(),clock);
        journal=new CommandJournal(adapterDb.sql(),port,observations,clock);recovery=new RestoreReconciliation(adapterDb.sql(),port,simulator::history,this::inventory,clock);
    }
    JsonNode observed(JsonNode value){((ObjectNode)value).put("observedAt",clock.instant().toString());return value;}
    JsonNode inventory(UUID after,int limit){return observed(simulator.recoveryInventory(after,limit));}
    JsonNode movement(UUID id){
        var node=JsonSupport.MAPPER.createObjectNode().put("movementId",id.toString()).put("reservationId",id.toString()).put("siteId","site-a").put("product","fulfilment")
            .put("zoneId","ambient").put("loadId",UUID.randomUUID().toString()).put("source","bin-SKU-001").put("destination","outbound-staging").put("quantity",2).put("priority",5).put("eligibleAt",clock.instant().toString());return node;
    }
    UUID accepted(){UUID id=UUID.randomUUID();var movement=movement(id);var allocation=allocations.register("site-a","legacy-core",movement);journal.record("site-a","legacy-core",id,Database.uuid(allocation,"allocationId"),0,"ambient-a",movement);journal.work();return id;}
    RestoreReconciliation.Start request(){var world=simulator.equipment();return new RestoreReconciliation.Start(UUID.randomUUID(),"recovery-fixture","a".repeat(64),Database.uuid(world,"worldId"),Database.uuid(world,"journalGeneration"),clock.instant().minusSeconds(1),world.path("journalHighWater").asLong(),"platform-recovery","Compare this application checkpoint with retained physical history.");}
    UUID begin(){
        adapterDb.sql().execute("UPDATE service_control SET workers_paused=true,dispatch_paused=true,intake_paused=true");
        var request=request();recovery.begin(request);assertThat(recovery.begin(request).path("restoreId").asString()).isEqualTo(request.restoreId().toString());
        adapterDb.sql().execute("UPDATE service_control SET workers_paused=false");return request.restoreId();
    }
    void completePhysical(){clock.advance(Duration.ofSeconds(2));simulator.advance();journal.work();}
    UUID externalPhysical(){
        UUID id=UUID.randomUUID();var world=simulator.equipment();var body=(ObjectNode)movement(id);
        body.remove("product");body.remove("reservationId");body.remove("priority");body.remove("eligibleAt");
        body.put("commandId",id.toString()).put("allocationId",UUID.randomUUID().toString()).put("laneId","ambient-a").put("expectedLoadVersion",0);
        body.set("worldId",world.path("worldId"));body.set("journalGeneration",world.path("journalGeneration"));
        simulator.accept(id,body);clock.advance(Duration.ofSeconds(2));simulator.advance();return id;
    }
    @Test void knownCompletionAfterFreezeIsMatchedWithoutChangingBusinessOrReopeningDispatch(){
        UUID command=accepted(),restore=begin();completePhysical();
        recovery=new RestoreReconciliation(adapterDb.sql(),port,simulator::history,this::inventory,clock);
        var result=recovery.scan(restore);
        assertThat(result.path("scannedCount").asInt()).isEqualTo(1);assertThat(result.path("unresolvedCount").asInt()).isZero();
        assertThat(result.path("lastError").asString()).isEqualTo("COMMAND_STATUS_PROOFS_REQUIRED");
        assertThat(adapterDb.sql().fetchOne("SELECT command_id,state FROM restore_physical_findings").intoArray()).containsExactly(command,"MATCHED");
        assertThat(recovery.scan(restore).path("scannedCount").asInt()).isEqualTo(1);
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required FROM service_control").get(0,Boolean.class)).isTrue();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal").get(0,Integer.class)).isEqualTo(1);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void postCheckpointPhysicalWorkWithoutIntentIsDurablyQuarantined(){
        UUID restore=begin(),unknown=externalPhysical();var result=recovery.scan(restore);
        assertThat(result.path("state").asString()).isEqualTo("QUARANTINED");assertThat(result.path("unresolvedCount").asInt()).isEqualTo(1);
        assertThat(adapterDb.sql().fetchOne("SELECT command_id,state FROM restore_physical_findings").intoArray()).containsExactly(unknown,"MISSING_BUSINESS_CONTEXT");
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM movement_allocations").get(0,Integer.class)).isZero();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal").get(0,Integer.class)).isZero();
        assertThat(recovery.scan(restore)).isEqualTo(result);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM audit WHERE action='restore-quarantined'").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void changedWorldCannotBeMadeConsistentByRescanning(){
        UUID restore=begin();simulatorDb.sql().execute("UPDATE simulation_world SET world_id=gen_random_uuid()");
        var result=recovery.scan(restore);assertThat(result.path("lastError").asString()).isEqualTo("PHYSICAL_WORLD_CHANGED");
        assertThat(result.path("state").asString()).isEqualTo("QUARANTINED");
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM restore_physical_findings").get(0,Integer.class)).isZero();
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required FROM service_control").get(0,Boolean.class)).isTrue();
    }
    @Test void incompleteHistoryPageCannotBeTreatedAsAnEmptyPhysicalWorld(){
        accepted();UUID restore=begin();completePhysical();
        var missing=new RestoreReconciliation(adapterDb.sql(),port,(after,limit)->JsonSupport.MAPPER.createArrayNode(),this::inventory,clock);
        assertThat(missing.scan(restore).path("lastError").asString()).isEqualTo("PHYSICAL_HISTORY_GAP");
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal WHERE state='COMPLETED'").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void selfConsistentButChangedCommandPayloadConflictsWithThePhysicalEffect(){
        UUID command=accepted(),restore=begin();completePhysical();
        var changed=(ObjectNode)journal.get("site-a",command).path("payload").deepCopy();changed.put("quantity",3);
        adapterDb.sql().execute("UPDATE command_journal SET payload=?::jsonb,payload_hash=? WHERE command_id=?",JsonSupport.write(changed),JsonSupport.hash(changed),command);
        assertThat(recovery.scan(restore).path("state").asString()).isEqualTo("QUARANTINED");
        assertThat(adapterDb.sql().fetchOne("SELECT state FROM restore_physical_findings").get(0,String.class)).isEqualTo("INTENT_CONFLICT");
        assertThat(simulatorDb.sql().fetchOne("SELECT quantity FROM execution_ledger").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void completeHistoryInventoryAndStatusProofsReleaseOnlyTheDedicatedHold(){
        accepted();completePhysical();UUID restore=begin();recovery.scan(restore);recovery.scanInventory(restore);recovery.proveAbsence(restore);
        var verified=recovery.verify(restore);assertThat(verified.path("state").asString()).isEqualTo("VERIFIED");
        long version=verified.path("version").asLong();String reason="All checkpoint commands match the independent physical journal.";
        var released=recovery.release(restore,version,"platform-recovery",reason);
        assertThat(released.path("state").asString()).isEqualTo("RELEASED");assertThat(recovery.release(restore,version,"platform-recovery",reason)).isEqualTo(released);
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required,dispatch_paused,intake_paused FROM service_control").intoArray()).containsExactly(false,true,true);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM audit WHERE action='restore-released'").get(0,Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->recovery.release(restore,version,"platform-recovery","Changed reason after the first release.")).isInstanceOf(Problem.class);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void acceptedCommandWithoutCheckpointIntentIsDetectedBeforeItExecutes(){
        UUID restore=begin();simulator.blockLane("site-a","ambient-a",true);UUID unknown=externalPhysical();
        assertThat(simulator.history(0,100).size()).isZero();recovery.scan(restore);
        var result=recovery.scanInventory(restore);assertThat(result.path("state").asString()).isEqualTo("QUARANTINED");
        assertThat(result.path("inventoryUnresolved").asInt()).isEqualTo(1);
        assertThat(adapterDb.sql().fetchOne("SELECT command_id,state,simulator_state FROM restore_inventory_findings").intoArray()).containsExactly(unknown,"MISSING_BUSINESS_CONTEXT","ACCEPTED");
        assertThatThrownBy(()->recovery.release(restore,result.path("version").asLong(),"platform-recovery","Attempt to ignore the missing physical command.")).isInstanceOf(Problem.class);
    }
    @Test void neverSentStableCommandRequiresAnAbsenceProofBeforeVerification(){
        UUID command=UUID.randomUUID();var movement=movement(command);var allocation=allocations.register("site-a","legacy-core",movement);
        journal.record("site-a","legacy-core",command,Database.uuid(allocation,"allocationId"),0,"ambient-a",movement);
        UUID restore=begin();recovery.scan(restore);recovery.scanInventory(restore);
        assertThatThrownBy(()->recovery.verify(restore)).isInstanceOf(Problem.class).hasMessageContaining("original status");
        assertThat(recovery.proveAbsence(restore).path("absenceCount").asInt()).isEqualTo(1);
        var verified=recovery.verify(restore);assertThat(verified.path("state").asString()).isEqualTo("VERIFIED");
        assertThat(adapterDb.sql().fetchOne("SELECT evidence->>'commandId',evidence->>'status' FROM restore_absence_proofs").intoArray()).containsExactly(command.toString(),"404");
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM simulator_commands").get(0,Integer.class)).isZero();
    }
    @Test void anAcceptedCommandMissingFromCompleteHistoryCannotBecomeAResend(){
        accepted();UUID restore=begin();
        // Model a corrupt physical journal only in this disposable simulator fixture.
        simulatorDb.sql().execute("DELETE FROM simulator_commands");recovery.scan(restore);recovery.scanInventory(restore);
        assertThat(recovery.proveAbsence(restore).path("lastError").asString()).isEqualTo("ACCEPTED_COMMAND_MISSING");
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required FROM service_control").get(0,Boolean.class)).isTrue();
    }
    @Test void newAcceptedCommandBetweenVerificationAndReleaseKeepsDispatchHeld(){
        UUID restore=begin();recovery.scan(restore);recovery.scanInventory(restore);var verified=recovery.verify(restore);
        simulator.blockLane("site-a","ambient-a",true);externalPhysical();
        var result=recovery.release(restore,verified.path("version").asLong(),"platform-recovery","Release after comparison of retained recovery evidence.");
        assertThat(result.path("state").asString()).isEqualTo("QUARANTINED");assertThat(result.path("lastError").asString()).isEqualTo("PHYSICAL_INVENTORY_CHANGED");
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required FROM service_control").get(0,Boolean.class)).isTrue();
    }
    @Test void aKnownCompletionAfterVerificationRequiresFreshProofWithoutDiscardingTheSession(){
        accepted();UUID restore=begin();recovery.scan(restore);recovery.scanInventory(restore);var verified=recovery.verify(restore);
        completePhysical();
        var deferred=recovery.release(restore,verified.path("version").asLong(),"platform-recovery","Release the verified restoration without changing command identities.");
        assertThat(deferred.path("state").asString()).isEqualTo("SCANNING");assertThat(deferred.path("lastError").asString()).isEqualTo("RESTORE_SCAN_REQUIRED");
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required FROM service_control").get(0,Boolean.class)).isTrue();
        recovery.scan(restore);verified=recovery.verify(restore);
        assertThat(recovery.release(restore,verified.path("version").asLong(),"platform-recovery","Release after extending the proof for the original completed command.").path("state").asString()).isEqualTo("RELEASED");
    }
    @Test void staleWorldEvidenceFailsClosed(){
        UUID restore=begin();var stale=new RestoreReconciliation(adapterDb.sql(),port,simulator::history,(after,limit)->{
            var value=(ObjectNode)inventory(after,limit);value.put("observedAt",clock.instant().minusSeconds(6).toString());return value;
        },clock);
        assertThat(stale.scanInventory(restore).path("lastError").asString()).isEqualTo("PHYSICAL_OBSERVATION_STALE");
    }
    @Test void commandAddedBetweenInventoryPagesCannotHideBeforeTheCursor(){
        accepted();accepted();UUID restore=begin();
        var paged=new RestoreReconciliation(adapterDb.sql(),port,simulator::history,(after,limit)->inventory(after,1),clock);
        assertThat(paged.scanInventory(restore).path("inventoryComplete").asBoolean()).isFalse();
        externalPhysical();
        assertThat(paged.scanInventory(restore).path("lastError").asString()).isEqualTo("PHYSICAL_INVENTORY_CHANGED");
    }
    @Test void inventoryCannotBypassARewoundCheckpointHighWater(){
        var original=request();var checkpoint=new RestoreReconciliation.Start(original.restoreId(),original.checkpointName(),original.manifestSha256(),original.worldId(),original.journalGeneration(),original.checkpointAt(),1,original.actor(),original.reason());
        adapterDb.sql().execute("UPDATE service_control SET workers_paused=true,intake_paused=true,dispatch_paused=true");recovery.begin(checkpoint);
        adapterDb.sql().execute("UPDATE service_control SET workers_paused=false");
        assertThat(recovery.scanInventory(checkpoint.restoreId()).path("lastError").asString()).isEqualTo("PHYSICAL_HISTORY_REWOUND");
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required FROM service_control").get(0,Boolean.class)).isTrue();
    }
}
