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

    @Test void independentCommandsShareAuthorityWhileOwnerChangesWaitForCommit() throws Exception {
        UUID firstId=UUID.randomUUID(),secondId=UUID.randomUUID(),laterId=UUID.randomUUID();
        var firstMovement=movement(firstId);var secondMovement=movement(secondId);var laterMovement=movement(laterId);
        var firstAllocation=allocations.register("site-a","legacy-core",firstMovement);
        var secondAllocation=allocations.register("site-a","legacy-core",secondMovement);
        var laterAllocation=allocations.register("site-a","legacy-core",laterMovement);
        var recorded=new java.util.concurrent.CountDownLatch(1);var commitFirst=new java.util.concurrent.CountDownLatch(1);
        var firstPid=new java.util.concurrent.atomic.AtomicInteger();var secondPid=new java.util.concurrent.atomic.AtomicInteger();
        var writerPid=new java.util.concurrent.atomic.AtomicInteger();
        try(var threads=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=threads.submit(()->adapterDb.sql().transaction(configuration->{
                var sql=org.jooq.impl.DSL.using(configuration);firstPid.set(sql.fetchOne("SELECT pg_backend_pid()").get(0,Integer.class));
                new CommandJournal(sql,port,observations,clock).record("site-a","legacy-core",firstId,Database.uuid(firstAllocation,"allocationId"),0,"ambient-a",firstMovement);
                recorded.countDown();
                try {if(!commitFirst.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("The first command transaction was not released.");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }));
            try {
                assertThat(recorded.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second=threads.submit(()->adapterDb.sql().transactionResult(configuration->{
                    var sql=org.jooq.impl.DSL.using(configuration);secondPid.set(sql.fetchOne("SELECT pg_backend_pid()").get(0,Integer.class));
                    return new CommandJournal(sql,port,observations,clock).record("site-a","legacy-core",secondId,Database.uuid(secondAllocation,"allocationId"),0,"ambient-a",secondMovement);
                }));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);boolean peerBlocked=false;
                while(!second.isDone()&&System.nanoTime()<deadline){
                    if(secondPid.get()!=0&&adapterDb.sql().fetchOne("SELECT ?=ANY(pg_blocking_pids(?))",firstPid.get(),secondPid.get()).get(0,Boolean.class)){peerBlocked=true;break;}
                    Thread.sleep(10);
                }
                assertThat(peerBlocked).as("Independent commands must not wait for each other's route authority read").isFalse();
                assertThat(second.get(5,java.util.concurrent.TimeUnit.SECONDS).path("commandId").asString()).isEqualTo(secondId.toString());
                assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal").get(0,Integer.class)).isEqualTo(1);
                // Owner/epoch updates use a conflicting lock even when they do not change the route key.
                var writer=threads.submit(()->adapterDb.sql().transaction(configuration->{
                    var sql=org.jooq.impl.DSL.using(configuration);writerPid.set(sql.fetchOne("SELECT pg_backend_pid()").get(0,Integer.class));
                    sql.execute("UPDATE zone_routes SET owner='execution-service',epoch=1,version=version+1 WHERE site_id='site-a' AND zone_id='ambient'");
                }));
                deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);boolean writerBlocked=false;
                while(System.nanoTime()<deadline){
                    if(writerPid.get()!=0&&adapterDb.sql().fetchOne("SELECT ?=ANY(pg_blocking_pids(?))",firstPid.get(),writerPid.get()).get(0,Boolean.class)){writerBlocked=true;break;}
                    Thread.sleep(10);
                }
                assertThat(writerBlocked).as("Owner/epoch changes remain fenced until the command transaction commits").isTrue();
                assertThat(writer.isDone()).isFalse();commitFirst.countDown();
                first.get(10,java.util.concurrent.TimeUnit.SECONDS);writer.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal").get(0,Integer.class)).isEqualTo(2);
                assertThatThrownBy(()->journal.record("site-a","legacy-core",laterId,Database.uuid(laterAllocation,"allocationId"),0,"ambient-a",laterMovement))
                        .isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).code()).isEqualTo("STALE_OWNER"));
                assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal WHERE command_id=?",laterId).get(0,Integer.class)).isZero();
                assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isZero();
            } finally {commitFirst.countDown();}
        }
    }

    @Test void mixedZoneInboxBatchDoesNotInvertRouteAndOutboxBudgetLocks() throws Exception {
        var otherRouteHeld=new java.util.concurrent.CountDownLatch(1);
        var publishOther=new java.util.concurrent.CountDownLatch(1);
        var otherPid=new java.util.concurrent.atomic.AtomicInteger();
        var batchPid=new java.util.concurrent.atomic.AtomicInteger();
        var ambient=movement(UUID.randomUUID());
        var chilled=((tools.jackson.databind.node.ObjectNode)movement(UUID.randomUUID())).put("zoneId","chilled");
        var concurrent=((tools.jackson.databind.node.ObjectNode)movement(UUID.randomUUID())).put("zoneId","chilled");
        java.util.function.Function<JsonNode,dev.cutover.platform.messaging.DurableInbox.Delivery> delivery=body->{
            var id=Database.uuid(body,"movementId");
            var event=new dev.cutover.platform.Events.Envelope(UUID.randomUUID(),"MovementRequested.v1",1,clock.instant(),"site-a","legacy-core","movement",id,1,id,null,null,body);
            return new dev.cutover.platform.messaging.DurableInbox.Delivery("cutover.legacy-core.v1",event.eventId().toString(),JsonSupport.write(event).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        };
        try(var threads=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var other=threads.submit(()->adapterDb.sql().transaction(configuration->{
                var sql=org.jooq.impl.DSL.using(configuration);
                otherPid.set(sql.fetchOne("SELECT pg_backend_pid()").get(0,Integer.class));
                sql.fetch("SELECT * FROM zone_routes WHERE site_id='site-a' AND zone_id='chilled' FOR UPDATE");
                otherRouteHeld.countDown();
                try {if(!publishOther.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("The competing writer was not released.");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                new Allocations(sql,clock).register("site-a","legacy-core",concurrent);
            }));
            try {
                assertThat(otherRouteHeld.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var batch=threads.submit(()->adapterDb.sql().transactionResult(configuration->{
                    var sql=org.jooq.impl.DSL.using(configuration);
                    batchPid.set(sql.fetchOne("SELECT pg_backend_pid()").get(0,Integer.class));
                    return new dev.cutover.platform.messaging.DurableInbox(sql,new AdapterMessages(clock),clock,java.util.Set.of("legacy-core"),java.util.Set.of("site-a"))
                            .receiveBatch(java.util.List.of(delivery.apply(ambient),delivery.apply(chilled)));
                }));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<deadline){
                    if(batchPid.get()!=0 && adapterDb.sql().fetchOne("SELECT ?=ANY(pg_blocking_pids(?))",otherPid.get(),batchPid.get()).get(0,Boolean.class)){waiting=true;break;}
                    Thread.sleep(10);
                }
                assertThat(waiting).as("The batch reaches the competing chilled route before its owner publishes").isTrue();
                publishOther.countDown();
                other.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(batch.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasSize(2);
                assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM inbox WHERE state='APPLIED'").get(0,Integer.class)).isEqualTo(2);
                assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM movement_allocations").get(0,Integer.class)).isEqualTo(3);
                assertThat(adapterDb.sql().fetchOne("SELECT unpublished_events FROM admission").get(0,Integer.class)).isEqualTo(3);
            } finally {publishOther.countDown();}
        }
    }

    @Test void retainedTimelineShowsActualTransitionsAndReportsCompactedHistoryWithoutChangingProof(){
        UUID id=UUID.randomUUID();record(id,movement(id));journal.work();tick();
        var command=journal.get("site-a",id);assertThat(command.path("state").asString()).isEqualTo("COMPLETED");
        var timeline=journal.timeline("site-a",id);assertThat(timeline.path("historyComplete").asBoolean()).isTrue();
        assertThat(timeline.path("events").get(0).path("type").asString()).isEqualTo("MovementAssigned.v1");
        assertThat(timeline.path("events").get(timeline.path("events").size()-1).path("type").asString()).isEqualTo("MovementCompleted.v1");
        assertThatThrownBy(()->journal.timeline("site-b",id)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).status()).isEqualTo(404));
        // Isolated retention fixture: the lifetime command proof survives loss of the oldest published-payload range.
        adapterDb.sql().execute("DELETE FROM outbox WHERE aggregate_id=? AND aggregate_version=1",id);
        assertThat(journal.timeline("site-a",id).path("historyComplete").asBoolean()).isFalse();
        assertThat(journal.get("site-a",id)).isEqualTo(command);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }

    @Test void emptyEquipmentAndAssignmentPollsDoNotDirtyTheDurabilityGuard() {
        adapterDb.sql().transaction(configuration -> {
            var sql = org.jooq.impl.DSL.using(configuration); sql.execute("SET TRANSACTION READ ONLY");
            assertThat(new Allocations(sql, clock).releasePending()).isZero();
            assertThat(new CommandJournal(sql, port, observations, clock).work()).isZero();
            assertThat(sql.fetchOne("SELECT pg_current_xact_id_if_assigned()::text").get(0)).isNull();
        });
        simulatorDb.sql().transaction(configuration -> {
            var sql = org.jooq.impl.DSL.using(configuration); sql.execute("SET TRANSACTION READ ONLY");
            assertThat(new SimulatorEngine(sql, clock).advance()).isZero();
            assertThat(sql.fetchOne("SELECT pg_current_xact_id_if_assigned()::text").get(0)).isNull();
        });
    }

    @Test void restorationHoldCannotBeClearedByOrdinaryControlsAndStillAllowsKnownCompletion() {
        UUID accepted=UUID.randomUUID();record(accepted,movement(accepted));journal.work();
        UUID waiting=UUID.randomUUID();var waitingMovement=movement(waiting);var allocation=allocations.register("site-a","legacy-core",waitingMovement);
        adapterDb.sql().execute("UPDATE service_control SET restoration_required=true");
        var controls=new dev.cutover.platform.control.RuntimeControls(adapterDb.sql());
        controls.change("scenario","site-a","ordinary-resume",JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",0,"workersPaused",false,"dispatchPaused",false,"intakePaused",false,"reason","Ordinary controls cannot bypass an unresolved application restoration.")));
        assertThatThrownBy(()->journal.record("site-a","legacy-core",waiting,Database.uuid(allocation,"allocationId"),0,"ambient-a",waitingMovement))
            .isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).code()).isEqualTo("RESTORATION_REQUIRED"));
        UUID fresh=UUID.randomUUID();
        assertThatThrownBy(()->allocations.register("site-a","legacy-core",movement(fresh))).isInstanceOf(Problem.class)
            .satisfies(error->assertThat(((Problem)error).code()).isEqualTo("RESTORATION_REQUIRED"));
        tick();
        assertThat(journal.get("site-a",accepted).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(adapterDb.sql().fetchOne("SELECT restoration_required FROM service_control").get(0,Boolean.class)).isTrue();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal WHERE command_id=?",waiting).get(0,Integer.class)).isZero();
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }

    @Test void checkpointFreezePreventsManualInvestigationAndRetainsTheSameCommandForResume() {
        UUID id=UUID.randomUUID();record(id,movement(id));simulator.fault("BEFORE_ACCEPT",id,1,0);journal.work();
        var before=journal.get("site-a",id);
        var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",before.path("version").asLong(),"reason","Investigate the retained command after the checkpoint completes."));
        adapterDb.sql().execute("UPDATE service_control SET workers_paused=true");
        long audits=adapterDb.sql().fetchOne("SELECT count(*) FROM audit").get(0,Long.class);
        assertThatThrownBy(()->journal.reconcile("supervisor","site-a",id,"investigate",request)).isInstanceOf(Problem.class)
            .satisfies(error->assertThat(((Problem)error).status()).isEqualTo(503));
        assertThat(journal.get("site-a",id)).isEqualTo(before);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM audit").get(0,Long.class)).isEqualTo(audits);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM idempotency").get(0,Integer.class)).isZero();
        adapterDb.sql().execute("UPDATE service_control SET workers_paused=false,dispatch_paused=true,critical_storage=true");
        var resumed=journal.reconcile("supervisor","site-a",id,"investigate",request);
        assertThat(resumed.path("state").asString()).isEqualTo("INVESTIGATION_RECORDED");
        assertThat(journal.reconcile("supervisor","site-a",id,"investigate",request)).isEqualTo(resumed);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isZero();
    }

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
