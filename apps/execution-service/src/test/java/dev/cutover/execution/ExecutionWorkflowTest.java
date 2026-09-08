package dev.cutover.execution;

import dev.cutover.adapter.*;
import dev.cutover.core.CoreMessages;
import dev.cutover.core.OrderService;
import dev.cutover.core.LegacyScheduler;
import dev.cutover.platform.*;
import dev.cutover.platform.messaging.*;
import dev.cutover.simulator.SimulatorEngine;
import dev.cutover.testing.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Four genuine owner databases; deterministic transport ports do not grant cross-owner writes. */
class ExecutionWorkflowTest {
    static DatabaseFixture core,execution,adapter,physical;
    MutableClock clock;OrderService orders;SimulatorEngine simulator;EquipmentObservations observations;Allocations allocations;CommandJournal journal;
    LegacyScheduler legacy;ExecutionScheduler scheduler;DispatchPort port;
    DurableInbox adapterInbox,coreInbox,executionInbox;
    AtomicBoolean contextDown,loseDispatchReply,loseEquipmentReply,freezeContext;
    AtomicInteger dispatchCalls;Set<UUID> coreDelivered,adapterDelivered;
    @BeforeAll static void databases(){core=new DatabaseFixture("legacy-core");execution=new DatabaseFixture("execution-service");adapter=new DatabaseFixture("equipment-adapter");physical=new DatabaseFixture("equipment-simulator");}
    @AfterAll static void stop(){core.close();execution.close();adapter.close();physical.close();}
    @BeforeEach void reset(){
        core.reset();execution.reset();adapter.reset();physical.reset();clock=new MutableClock(Instant.now().plusSeconds(2));
        contextDown=new AtomicBoolean();loseDispatchReply=new AtomicBoolean();loseEquipmentReply=new AtomicBoolean();freezeContext=new AtomicBoolean();dispatchCalls=new AtomicInteger();coreDelivered=new HashSet<>();adapterDelivered=new HashSet<>();
        // A fixture route establishes two owners; real session transitions are tested separately.
        adapter.sql().execute("UPDATE zone_routes SET owner='execution-service',epoch=3 WHERE site_id='site-a' AND zone_id='ambient'");
        simulator=new SimulatorEngine(physical.sql(),clock);
        var equipment=new EquipmentPort(){
            @Override public JsonNode equipment(){return simulator.equipment();}
            @Override public Reply command(UUID id){try{return new Reply(200,simulator.status(id));}catch(Problem absent){if(absent.status()!=404)throw absent;return new Reply(404,simulator.equipment());}}
            @Override public Reply send(UUID id,JsonNode payload){var result=simulator.accept(id,payload);if(loseEquipmentReply.getAndSet(false))throw new Unavailable("Response lost after physical acceptance");return new Reply(200,result.result());}
        };
        observations=new EquipmentObservations(adapter.sql(),equipment,clock);observations.refresh();allocations=new Allocations(adapter.sql(),clock);journal=new CommandJournal(adapter.sql(),equipment,observations,clock);orders=new OrderService(core.sql(),clock);
        legacy=new LegacyScheduler(core.sql(),new dev.cutover.core.DispatchPort(){
            @Override public JsonNode allocate(String site,JsonNode movement){throw new AssertionError("Post-boundary legacy tasks already have durable assignments.");}
            @Override public JsonNode command(String site,UUID id){return journal.get(site,id);}
            @Override public JsonNode equipment(String site){return observations.forSite(site);}
            @Override public JsonNode context(String site,String zone,List<UUID> ids){return new SchedulingContext(adapter.sql(),observations).read(site,zone,ids);}
            @Override public JsonNode dispatch(String site,UUID id,UUID allocation,long epoch,String lane,JsonNode movement){return journal.record(site,"legacy-core",id,allocation,epoch,lane,movement);}
        },orders,clock);
        port=new DispatchPort(){
            @Override public JsonNode context(String site,String zone,List<UUID> ids){if(contextDown.get())throw new ServiceHttp.Unavailable("Temporary adapter outage");var result=new SchedulingContext(adapter.sql(),observations).read(site,zone,ids);if(freezeContext.getAndSet(false))execution.sql().execute("UPDATE service_control SET workers_paused=true");return result;}
            @Override public JsonNode dispatch(String site,UUID id,UUID allocation,long epoch,String lane,JsonNode movement){dispatchCalls.incrementAndGet();var result=journal.record(site,"execution-service",id,allocation,epoch,lane,movement);if(loseDispatchReply.getAndSet(false))throw new ServiceHttp.Unavailable("Response lost after adapter commit");return result;}
        };
        scheduler=new ExecutionScheduler(execution.sql(),port,clock);
        adapterInbox=new DurableInbox(adapter.sql(),new AdapterMessages(clock),clock,Set.of("legacy-core"),Set.of("site-a"));
        coreInbox=new DurableInbox(core.sql(),new CoreMessages(clock),clock,Set.of("equipment-adapter"),Set.of("site-a"));
        executionInbox=new DurableInbox(execution.sql(),new ExecutionMessages(),clock,Set.of("equipment-adapter"),Set.of("site-a"));
    }
    UUID accept(String name,OrderService.Line... lines){return Database.uuid(orders.accept("scenario","site-a",name,JsonSupport.MAPPER.valueToTree(new OrderService.Request("test-driver",name,"store-01",5,List.of(lines)))),"id");}
    void messages(){
        for(var row:core.sql().fetch("SELECT event_id,envelope FROM outbox ORDER BY aggregate_id,aggregate_version"))if(coreDelivered.add(row.get("event_id",UUID.class)))adapterInbox.receive("cutover.legacy-core.v1",row.get("event_id").toString(),row.get("envelope").toString().getBytes(StandardCharsets.UTF_8));
        for(var row:adapter.sql().fetch("SELECT event_id,envelope FROM outbox ORDER BY aggregate_id,aggregate_version"))if(adapterDelivered.add(row.get("event_id",UUID.class))){var bytes=row.get("envelope").toString().getBytes(StandardCharsets.UTF_8);coreInbox.receive("cutover.equipment-adapter.v1",row.get("event_id").toString(),bytes);executionInbox.receive("cutover.equipment-adapter.v1",row.get("event_id").toString(),bytes);}
    }
    void tick(){clock.advance(Duration.ofMillis(500));observations.refresh();allocations.releasePending();messages();legacy.poll();scheduler.poll();journal.work();simulator.advance();messages();}
    void finish(UUID order){for(int n=0;n<40&&!orders.get("site-a",order).path("state").asString().equals("COMPLETED");n++)tick();assertThat(orders.get("site-a",order).path("state").asString()).isEqualTo("COMPLETED");}
    UUID movement(UUID order){return core.sql().fetchOne("SELECT movement_id FROM movement_intents WHERE order_id=?",order).get(0,UUID.class);}
    int count(DatabaseFixture db,String table){return db.sql().fetchOne("SELECT count(*) FROM "+table).get(0,Integer.class);}
    @Test void aSlowBatchUsesNewEquipmentEvidenceForItsRemainingMovement(){
        verifyEvidenceAfterSlowDispatch(true);
    }
    @Test void aSlowBatchStillRefusesEquipmentEvidenceThatRemainsStale(){
        verifyEvidenceAfterSlowDispatch(false);
    }
    void verifyEvidenceAfterSlowDispatch(boolean refreshEquipment){
        accept("slow-batch",new OrderService.Line("SKU-001",1),new OrderService.Line("SKU-003",1));messages();
        var first=new AtomicBoolean(true);
        var slow=new DispatchPort(){
            @Override public JsonNode context(String site,String zone,List<UUID> ids){return port.context(site,zone,ids);}
            @Override public JsonNode dispatch(String site,UUID id,UUID allocation,long epoch,String lane,JsonNode movement){
                var result=port.dispatch(site,id,allocation,epoch,lane,movement);
                if(first.getAndSet(false)){clock.advance(Duration.ofSeconds(6));if(refreshEquipment)observations.refresh();}
                return result;
            }
        };
        assertThat(new ExecutionScheduler(execution.sql(),slow,clock).poll()).isEqualTo(2);
        assertThat(count(adapter,"command_journal")).isEqualTo(refreshEquipment?2:1);
        assertThat(execution.sql().fetchOne("SELECT count(*) FROM execution_tasks WHERE last_error='OBSERVATION_STALE'").get(0,Integer.class)).isEqualTo(refreshEquipment?0:1);
        assertThat(count(physical,"execution_ledger")).isZero();
    }
    @Test void recordedCommandsCannotStarveANewEligibleMovement() {
        clock.advance(Duration.ofSeconds(30));observations.refresh();
        for(int n=0;n<16;n++)accept("pending-command-"+n,new OrderService.Line("SKU-001",1));
        messages();
        for(int n=0;n<4 && count(adapter,"command_journal")<16;n++){
            clock.advance(Duration.ofMillis(500));observations.refresh();scheduler.poll();
        }
        assertThat(count(adapter,"command_journal")).isEqualTo(16);
        assertThat(count(physical,"execution_ledger")).isZero();
        clock.advance(Duration.ofMillis(100));
        UUID order=accept("new-work-behind-recorded-commands",new OrderService.Line("SKU-003",1));messages();
        UUID movement=movement(order);
        clock.advance(Duration.ofMillis(500));observations.refresh();
        var previous=execution.sql().fetchOne("SELECT sum(version) FROM execution_tasks WHERE movement_id<>?",movement).get(0,Long.class);
        assertThat(scheduler.poll()).isLessThanOrEqualTo(16);
        assertThat(adapter.sql().fetchOne("SELECT count(*) FROM command_journal WHERE command_id=?",movement).get(0,Integer.class))
                .as("A new eligible movement must get a dispatch turn while older commands await equipment").isEqualTo(1);
        assertThat(execution.sql().fetchOne("SELECT sum(version) FROM execution_tasks WHERE movement_id<>?",movement).get(0,Long.class))
                .as("Existing command observation also keeps a bounded share of the work cycle").isGreaterThan(previous);
        assertThat(count(physical,"execution_ledger")).isZero();
        assertThat(count(core,"inventory_ledger")).isZero();
    }
    @Test void twoOwnersConsumeAssignmentsAndCompleteOneInventoryEffectPerPhysicalMovement(){
        UUID order=accept("two-owners",new OrderService.Line("SKU-001",2),new OrderService.Line("SKU-002",3));
        assertThat(count(core,"legacy_tasks")).isZero();assertThat(count(execution,"execution_tasks")).isZero();messages();
        assertThat(core.sql().fetchOne("SELECT zone_id,priority FROM legacy_tasks").intoArray()).containsExactly("chilled",500);
        assertThat(execution.sql().fetchOne("SELECT zone_id,priority,epoch FROM execution_tasks").intoArray()).containsExactly("ambient",500,3L);
        finish(order);assertThat(count(core,"inventory_ledger")).isEqualTo(2);assertThat(count(physical,"execution_ledger")).isEqualTo(2);
        assertThat(core.sql().fetchOne("SELECT sum(reserved) FROM stock").get(0,Integer.class)).isZero();
        assertThat(execution.sql().fetchOne("SELECT to_regclass('stock'),to_regclass('reservations'),to_regclass('legacy_tasks')").intoArray()).containsExactly(null,null,null);
        assertThat(count(execution,"decision_rounds")).isPositive();
        coreDelivered.clear();adapterDelivered.clear();messages();assertThat(count(core,"inventory_ledger")).isEqualTo(2);assertThat(count(execution,"execution_tasks")).isEqualTo(1);
        assertThat(execution.sql().fetchOne("SELECT count(*) FROM inbox WHERE state='QUARANTINED'").get(0,Integer.class)).isZero();
    }
    @Test void lostDispatchResponseIsRecoveredByObservingTheSameJournaledCommand(){
        UUID order=accept("lost-dispatch",new OrderService.Line("SKU-003",2));messages();loseDispatchReply.set(true);scheduler.poll();
        assertThat(count(adapter,"command_journal")).isEqualTo(1);assertThat(execution.sql().fetchOne("SELECT transport_failures FROM execution_tasks").get(0,Integer.class)).isEqualTo(1);
        finish(order);assertThat(dispatchCalls.get()).isEqualTo(1);assertThat(count(physical,"execution_ledger")).isEqualTo(1);assertThat(count(core,"inventory_ledger")).isEqualTo(1);
    }
    @Test void transportBudgetPausesVisiblyAndANewSupervisorCanResumeOriginalIdentity(){
        UUID order=accept("retry-budget",new OrderService.Line("SKU-005",1));messages();contextDown.set(true);
        for(int n=0;n<6;n++){clock.advance(Duration.ofSeconds(20));scheduler.poll();}
        var task=execution.sql().fetchOne("SELECT * FROM execution_tasks");assertThat(task.get("transport_failures",Integer.class)).isEqualTo(6);assertThat(task.get("transport_paused",Boolean.class)).isTrue();
        clock.advance(Duration.ofMinutes(5));assertThat(new ExecutionScheduler(execution.sql(),port,clock).poll()).isZero();assertThat(count(adapter,"command_journal")).isZero();
        UUID id=task.get("task_id",UUID.class);var body=JsonSupport.MAPPER.createObjectNode().put("expectedVersion",task.get("version",Long.class)).put("reason","Adapter connection restored; investigate the original assignment.");
        var result=scheduler.resume("second-supervisor","site-a",id,"resume",body);assertThat(scheduler.resume("second-supervisor","site-a",id,"resume",body)).isEqualTo(result);
        assertThatThrownBy(()->scheduler.resume("first-supervisor","site-a",id,"stale",body)).isInstanceOf(Problem.class);
        contextDown.set(false);finish(order);assertThat(execution.sql().fetchOne("SELECT task_id FROM execution_tasks").get(0)).isEqualTo(id);assertThat(count(physical,"execution_ledger")).isEqualTo(1);
        assertThat(execution.sql().fetchOne("SELECT count(*) FROM audit WHERE action='execution-task-recovery'").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void checkpointFreezeAfterContextReadCannotDispatchOrPersistADecision(){
        UUID order=accept("freeze-context",new OrderService.Line("SKU-007",1));messages();freezeContext.set(true);scheduler.poll();
        assertThat(count(adapter,"command_journal")).isZero();assertThat(count(execution,"decision_rounds")).isZero();
        assertThat(execution.sql().fetchOne("SELECT state FROM execution_tasks").get(0,String.class)).isEqualTo("READY");
        execution.sql().execute("UPDATE service_control SET workers_paused=false");clock.advance(Duration.ofSeconds(61));finish(order);
    }
    @Test void unknownPhysicalOutcomeNeverBecomesANewDispatchCandidate(){
        UUID order=accept("unknown-physical",new OrderService.Line("SKU-009",1));messages();scheduler.poll();loseEquipmentReply.set(true);journal.work();
        assertThat(journal.get("site-a",movement(order)).path("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        clock.advance(Duration.ofMillis(500));observations.refresh();scheduler.poll();
        assertThat(execution.sql().fetchOne("SELECT state FROM execution_tasks").get(0,String.class)).isEqualTo("RECONCILIATION_REQUIRED");assertThat(dispatchCalls.get()).isEqualTo(1);
        finish(order);assertThat(dispatchCalls.get()).isEqualTo(1);assertThat(count(physical,"execution_ledger")).isEqualTo(1);
    }
    @Test void fullObservationHistoryCannotBlockAcceptedExecution(){
        UUID order=accept("observation-quota",new OrderService.Line("SKU-011",1));messages();execution.sql().execute("UPDATE execution_decision_capacity SET retained_rounds=8192,retained_bytes=67108864");
        finish(order);assertThat(count(physical,"execution_ledger")).isEqualTo(1);assertThat(count(execution,"decision_rounds")).isZero();
        assertThat(execution.sql().fetchOne("SELECT omitted_rounds FROM execution_decision_capacity").get(0,Long.class)).isPositive();
    }
}
