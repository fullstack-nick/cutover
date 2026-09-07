package dev.cutover.core;

import dev.cutover.adapter.Allocations;
import dev.cutover.adapter.CommandJournal;
import dev.cutover.adapter.EquipmentObservations;
import dev.cutover.adapter.EquipmentPort;
import dev.cutover.adapter.AdapterMessages;
import dev.cutover.platform.Contracts;
import dev.cutover.platform.Events;
import dev.cutover.platform.messaging.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.simulator.SimulatorEngine;
import dev.cutover.testing.DatabaseFixture;
import dev.cutover.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Three owning databases exercise the real SQL routines and journals with deterministic transport faults. */
class LegacyWorkflowTest {
    static DatabaseFixture coreDb,adapterDb,simulatorDb;
    MutableClock clock;SimulatorEngine simulator;EquipmentObservations observations;Allocations allocations;CommandJournal journal;
    OrderService orders;LegacyScheduler scheduler;EquipmentPort equipment;
    @BeforeAll static void databases(){coreDb=new DatabaseFixture("legacy-core");adapterDb=new DatabaseFixture("equipment-adapter");simulatorDb=new DatabaseFixture("equipment-simulator");}
    @AfterAll static void stop(){coreDb.close();adapterDb.close();simulatorDb.close();}
    @BeforeEach void reset(){
        coreDb.reset();adapterDb.reset();simulatorDb.reset();clock=new MutableClock(Instant.now().plusSeconds(2));
        simulator=new SimulatorEngine(simulatorDb.sql(),clock);
        equipment=new EquipmentPort(){
            @Override public JsonNode equipment(){return simulator.equipment();}
            @Override public Reply command(UUID id){try{return new Reply(200,simulator.status(id));}catch(Problem absent){if(absent.status()!=404)throw absent;return new Reply(404,simulator.equipment());}}
            @Override public Reply send(UUID id,JsonNode payload){
                try{var response=simulator.accept(id,payload);if(response.responseDelayMillis()>0)throw new Unavailable("Lost response");return new Reply(200,response.result());}
                catch(SimulatorEngine.DisconnectBeforeAcceptance failed){throw new Unavailable("Connection failed before acceptance");}
            }
        };
        allocations=new Allocations(adapterDb.sql());observations=new EquipmentObservations(adapterDb.sql(),equipment,clock);observations.refresh();
        journal=new CommandJournal(adapterDb.sql(),equipment,observations,clock);orders=new OrderService(coreDb.sql(),clock);scheduler=newScheduler();
    }
    LegacyScheduler newScheduler(){return new LegacyScheduler(coreDb.sql(),new DispatchPort(){
        @Override public JsonNode allocate(String site,JsonNode movement){return allocations.register(site,"legacy-core",movement);}
        @Override public JsonNode command(String site,UUID movement){try{return journal.get(site,movement);}catch(Problem absent){if(absent.status()!=404)throw absent;return null;}}
        @Override public JsonNode equipment(String site){return observations.forSite(site);}
        @Override public JsonNode context(String site,String zone,List<UUID> movements){return new dev.cutover.adapter.SchedulingContext(adapterDb.sql(),observations).read(site,zone,movements);}
        @Override public JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload){return journal.record(site,"legacy-core",movement,allocation,epoch,lane,payload);}
    },orders,clock);}
    JsonNode request(String reference,OrderService.Line... lines){return JsonSupport.MAPPER.valueToTree(new OrderService.Request("test-driver",reference,"store-01",5,List.of(lines)));}
    UUID accept(String reference,OrderService.Line... lines){return Database.uuid(orders.accept("scenario","site-a",reference,request(reference,lines)),"id");}
    void tick(){clock.advance(Duration.ofMillis(500));observations.refresh();scheduler.poll();journal.work();simulator.advance();}
    void finish(UUID id,String expected){for(int i=0;i<20&&!orders.get("site-a",id).path("state").asString().equals(expected);i++)tick();assertThat(orders.get("site-a",id).path("state").asString()).isEqualTo(expected);}

    @Test void fullAmbientAndChilledOrderRunsThroughTriggerTasksAndConsumesStockOnce(){
        UUID id=accept("complete",new OrderService.Line("SKU-001",4),new OrderService.Line("SKU-002",2));
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM legacy_tasks").get(0,Integer.class)).isEqualTo(2);
        assertThat(coreDb.sql().fetchOne("SELECT min(priority) FROM legacy_tasks").get(0,Integer.class)).isEqualTo(500);
        finish(id,"COMPLETED");
        assertThat(coreDb.sql().fetchOne("SELECT on_hand FROM stock WHERE site_id='site-a' AND sku='SKU-001'").get(0,Integer.class)).isEqualTo(96);
        assertThat(coreDb.sql().fetchOne("SELECT sum(reserved) FROM stock WHERE site_id='site-a'").get(0,Integer.class)).isZero();
        assertThat(coreDb.sql().fetchOne("SELECT sum(quantity) FROM inventory_ledger").get(0,Integer.class)).isEqualTo(6);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(2);
        for(var row:coreDb.sql().fetch("SELECT movement_id FROM inventory_ledger")){UUID movement=row.get("movement_id",UUID.class);orders.complete("site-a",movement,journal.get("site-a",movement));}
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM inventory_ledger").get(0,Integer.class)).isEqualTo(2);
        assertThat(coreDb.sql().fetchOne("SELECT active_requests FROM admission").get(0,Integer.class)).isZero();
    }
    @Test void partialAndTotalShortagesRemainExplicitAndCannotCreateStock(){
        UUID partial=accept("partial",new OrderService.Line("SKU-100",5),new OrderService.Line("SKU-099",3));
        UUID empty=accept("empty",new OrderService.Line("SKU-099",1));
        assertThat(orders.get("site-a",empty).path("state").asString()).isEqualTo("SHORTAGE");finish(partial,"COMPLETED_WITH_SHORTAGE");
        assertThat(coreDb.sql().fetchOne("SELECT sum(reserved_quantity),sum(shortage),sum(requested) FROM order_lines WHERE order_id=?",partial).intoArray()).containsExactly(2L,6L,8L);
        assertThat(coreDb.sql().fetchOne("SELECT on_hand FROM stock WHERE site_id='site-a' AND sku='SKU-100'").get(0,Integer.class)).isZero();
    }
    @Test void idempotencyAndBusinessReferenceBothPreventDuplicateReservations(){
        var body=request("unique",new OrderService.Line("SKU-001",3));
        var first=orders.accept("scenario","site-a","key-1",body);
        assertThat(orders.accept("scenario","site-a","key-1",body)).isEqualTo(first);
        assertThat(orders.accept("scenario","site-a","key-2",body)).isEqualTo(first);
        var changed=request("unique",new OrderService.Line("SKU-001",4));
        assertThatThrownBy(()->orders.accept("scenario","site-a","key-1",changed)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->orders.accept("scenario","site-a","key-3",changed)).isInstanceOf(Problem.class);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM orders").get(0,Integer.class)).isEqualTo(1);
        assertThat(coreDb.sql().fetchOne("SELECT sum(quantity) FROM reservations").get(0,Integer.class)).isEqualTo(3);
    }
    @Test void concurrentLastUnitRequestsCannotOversell() throws Exception{
        coreDb.sql().execute("UPDATE stock SET on_hand=7 WHERE site_id='site-a' AND sku='SKU-001'");
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(12)){
            var futures=new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for(int i=0;i<12;i++){final int index=i;futures.add(workers.submit(()->{start.await();return accept("race-"+index,new OrderService.Line("SKU-001",5));}));}
            start.countDown();for(var future:futures)assertThat(future.get(30,TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(coreDb.sql().fetchOne("SELECT reserved FROM stock WHERE site_id='site-a' AND sku='SKU-001'").get(0,Integer.class)).isEqualTo(7);
        assertThat(coreDb.sql().fetchOne("SELECT sum(quantity) FROM reservations").get(0,Integer.class)).isEqualTo(7);
        assertThat(coreDb.sql().fetchOne("SELECT sum(shortage) FROM order_lines").get(0,Integer.class)).isEqualTo(53);
    }
    @Test void lostResponseAndRestartedCoordinatorsCompleteWithoutDuplicateEffects(){
        UUID id=accept("lost-response",new OrderService.Line("SKU-001",2));
        UUID movement=Database.uuid(orders.get("site-a",id).path("movements").get(0),"movementId");
        simulator.fault("LOST_RESPONSE",movement,1,5000);tick();
        assertThat(journal.get("site-a",movement).path("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        journal=new CommandJournal(adapterDb.sql(),equipment,observations,clock);simulator=new SimulatorEngine(simulatorDb.sql(),clock);scheduler=newScheduler();
        finish(id,"COMPLETED");
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM inventory_ledger").get(0,Integer.class)).isEqualTo(1);
        assertThat(journal.get("site-a",movement).path("attempts").asInt()).isEqualTo(1);
    }
    @Test void allBlockedAmbientLanesDoNotStopChilledOrders(){
        simulator.blockLane("site-a","ambient-a",true);simulator.blockLane("site-a","ambient-b",true);
        UUID ambient=accept("blocked",new OrderService.Line("SKU-001",1));UUID chilled=accept("moving",new OrderService.Line("SKU-002",1));
        finish(chilled,"COMPLETED");assertThat(orders.get("site-a",ambient).path("state").asString()).isEqualTo("RESERVED");
        assertThat(coreDb.sql().fetchOne("SELECT state FROM legacy_tasks WHERE order_id=?",ambient).get(0,String.class)).isEqualTo("BLOCKED");
        simulator.blockLane("site-a","ambient-b",false);finish(ambient,"COMPLETED");
    }
    @Test void invalidInputsAndWrongSiteLookupsDoNotLeakOrWrite(){
        assertThatThrownBy(()->accept("invalid",new OrderService.Line("SKU-404",1))).isInstanceOf(Problem.class);
        assertThatThrownBy(()->accept("duplicate-lines",new OrderService.Line("SKU-001",1),new OrderService.Line("SKU-001",2))).isInstanceOf(Problem.class);
        assertThatThrownBy(()->accept("negative",new OrderService.Line("SKU-001",-1))).isInstanceOf(Problem.class);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM orders").get(0,Integer.class)).isZero();
        UUID id=accept("site-check",new OrderService.Line("SKU-001",1));
        assertThatThrownBy(()->orders.get("site-b",id)).isInstanceOf(Problem.class);
        assertThat(orders.list("site-b",null,25).path("items")).isEmpty();
    }
    @Test void completionEventAndInventoryEffectShareTheInboxTransaction() {
        UUID id=accept("message-completion",new OrderService.Line("SKU-001",2));
        UUID movement=Database.uuid(orders.get("site-a",id).path("movements").get(0),"movementId");
        scheduler.poll(); journal.work();
        for(int i=0;i<10&&!journal.get("site-a",movement).path("state").asString().equals("COMPLETED");i++) {
            clock.advance(Duration.ofMillis(500));simulator.advance();journal.work();
        }
        assertThat(journal.get("site-a",movement).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM inventory_ledger").get(0,Integer.class)).isZero();
        var broken=new AtomicBoolean(true);var owner=new CoreMessages(clock);
        var inbox=new DurableInbox(coreDb.sql(),(sql,event)->{owner.apply(sql,event);if(event.eventType().equals("MovementCompleted.v1")&&broken.get())throw DeliveryFailure.pending("SIMULATED_EFFECT_FAILURE");},clock,Set.of("equipment-adapter"),Set.of("site-a"));
        for(var row:adapterDb.sql().fetch("SELECT envelope FROM outbox WHERE aggregate_id=? ORDER BY aggregate_version",movement)) {
            var event=JsonSupport.MAPPER.readValue(row.get(0).toString(),Events.Envelope.class);
            inbox.receive("cutover.equipment-adapter.v1",event.eventId().toString(),row.get(0).toString().getBytes(StandardCharsets.UTF_8));
        }
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM inventory_ledger").get(0,Integer.class)).isZero();
        assertThat(coreDb.sql().fetchOne("SELECT state FROM reservations WHERE reservation_id=?",movement).get(0,String.class)).isEqualTo("RESERVED");
        broken.set(false);clock.advance(Duration.ofSeconds(2));inbox.retry(16);
        assertThat(orders.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM inventory_ledger").get(0,Integer.class)).isEqualTo(1);
        Contracts.validate("order-view.v1",JsonSupport.write(orders.get("site-a",id)));
        Contracts.validate("order-page.v1",JsonSupport.write(orders.list("site-a",null,25)));
        Contracts.validate("command-view.v1",JsonSupport.write(journal.get("site-a",movement)));
    }
    @Test void movementAllocationAndAssignmentOutboxShareTheInboxTransaction() {
        UUID id=accept("message-allocation",new OrderService.Line("SKU-001",1));
        var row=coreDb.sql().fetchOne("SELECT envelope FROM outbox WHERE event_type='MovementRequested.v1'");
        var event=JsonSupport.MAPPER.readValue(row.get(0).toString(),Events.Envelope.class);
        var broken=new AtomicBoolean(true);var owner=new AdapterMessages();
        var inbox=new DurableInbox(adapterDb.sql(),(sql,message)->{owner.apply(sql,message);if(broken.get())throw DeliveryFailure.pending("SIMULATED_ALLOCATION_FAILURE");},clock,Set.of("legacy-core"),Set.of("site-a"));
        inbox.receive("cutover.legacy-core.v1",event.eventId().toString(),row.get(0).toString().getBytes(StandardCharsets.UTF_8));
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM movement_allocations").get(0,Integer.class)).isZero();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM outbox").get(0,Integer.class)).isZero();
        broken.set(false);clock.advance(Duration.ofSeconds(2));inbox.retry(16);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM movement_allocations").get(0,Integer.class)).isEqualTo(1);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM outbox").get(0,Integer.class)).isEqualTo(1);
        assertThat(orders.get("site-a",id).path("state").asString()).isEqualTo("RESERVED");
    }
    JsonNode cancellationRequest(UUID order) { return JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",orders.get("site-a",order).path("version").asLong(),"reason","Cancel the complete unstarted order after reviewing its movements.")); }
    OrderCancellations cancellations(AtomicBoolean loseResponse) {
        var gate=new dev.cutover.adapter.CancellationGate(adapterDb.sql(),observations,clock);
        return new OrderCancellations(coreDb.sql(),new DispatchPort() {
            public JsonNode allocate(String site,JsonNode movement){throw new AssertionError();}
            public JsonNode command(String site,UUID movement){throw new AssertionError();}
            public JsonNode equipment(String site){throw new AssertionError();}
            public JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload){throw new AssertionError();}
            public JsonNode cancellation(String site,JsonNode request) {
                JsonNode result=gate.fence(site,"legacy-core",request);
                if(loseResponse.getAndSet(false))throw new dev.cutover.platform.ServiceHttp.Unavailable("Response lost after durable fence");
                return result;
            }
        },clock);
    }
    @Test void unstartedMixedOrderReleasesExactlyOnceAndLateIntentCannotRecreateWork() {
        UUID id=accept("cancel-mixed",new OrderService.Line("SKU-001",3),new OrderService.Line("SKU-002",2));
        var cancel=cancellations(new AtomicBoolean());var request=cancellationRequest(id);
        var response=cancel.cancel("supervisor","site-a",id,"cancel",request);
        assertThat(response.path("state").asString()).isEqualTo("CANCELLED");
        assertThat(cancel.cancel("supervisor","site-a",id,"cancel",request)).isEqualTo(response);
        assertThatThrownBy(()->cancel.cancel("supervisor","site-a",id,"another-key",cancellationRequest(id))).isInstanceOf(Problem.class);
        for(var row:coreDb.sql().fetch("SELECT movement FROM movement_intents WHERE order_id=?",id)) {
            var allocation=allocations.register("site-a","legacy-core",JsonSupport.read(row.get(0).toString()));
            assertThat(allocation.path("state").asString()).isEqualTo("CANCELLED");
        }
        scheduler.poll();journal.work();simulator.advance();
        assertThat(coreDb.sql().fetchOne("SELECT count(*),sum(quantity) FROM reservation_releases").intoArray()).containsExactly(2L,5L);
        assertThat(coreDb.sql().fetchOne("SELECT sum(reserved) FROM stock").get(0,Long.class)).isZero();
        assertThat(coreDb.sql().fetchOne("SELECT on_hand FROM stock WHERE site_id='site-a' AND sku='SKU-001'").get(0,Integer.class)).isEqualTo(100);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isZero();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM cancellation_certificates").get(0,Integer.class)).isEqualTo(1);
        assertThat(coreDb.sql().fetchOne("SELECT active_requests FROM admission").get(0,Integer.class)).isZero();
    }
    @Test void recordedButNeverSubmittedCommandCanBeFencedBeforePhysicalAcceptance() {
        UUID id=accept("cancel-unsent",new OrderService.Line("SKU-001",2));scheduler.poll();
        assertThat(adapterDb.sql().fetchOne("SELECT attempts FROM command_journal").get(0,Integer.class)).isZero();
        cancellations(new AtomicBoolean()).cancel("supervisor","site-a",id,"unsent",cancellationRequest(id));journal.work();
        assertThat(adapterDb.sql().fetchOne("SELECT state FROM command_journal").get(0,String.class)).isEqualTo("REJECTED_BEFORE_EXECUTION");
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM reservation_releases").get(0,Integer.class)).isEqualTo(1);
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM simulator_commands").get(0,Integer.class)).isZero();
    }
    @Test void oneUnknownMovementRejectsTheWholeCancellationWithoutReleasingOtherLines() {
        UUID id=accept("cancel-unknown",new OrderService.Line("SKU-001",2),new OrderService.Line("SKU-002",2));
        var movement=orders.get("site-a",id).path("movements").get(0).path("movement");UUID movementId=Database.uuid(movement,"movementId");
        var allocation=allocations.register("site-a","legacy-core",movement);
        journal.record("site-a","legacy-core",movementId,Database.uuid(allocation,"allocationId"),0,movement.path("zoneId").asString()+"-a",movement);
        simulator.fault("LOST_RESPONSE",movementId,1,5000);journal.work();
        var cancel=cancellations(new AtomicBoolean());var request=cancellationRequest(id);
        assertThatThrownBy(()->cancel.cancel("supervisor","site-a",id,"unknown",request)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).status()).isEqualTo(409));
        assertThatThrownBy(()->cancel.cancel("supervisor","site-a",id,"unknown",request)).isInstanceOf(Problem.class);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM reservation_releases").get(0,Integer.class)).isZero();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM movement_allocations WHERE state='CANCELLED'").get(0,Integer.class)).isZero();
        assertThat(coreDb.sql().fetchOne("SELECT cancellation_pending FROM orders WHERE order_id=?",id).get(0,Boolean.class)).isFalse();
        finish(id,"COMPLETED");
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM inventory_ledger").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void lostFenceResponseAndCoordinatorRestartDoNotRepeatAnyReservationRelease() {
        UUID id=accept("cancel-lost-proof",new OrderService.Line("SKU-001",2));var request=cancellationRequest(id);
        assertThatThrownBy(()->cancellations(new AtomicBoolean(true)).cancel("supervisor","site-a",id,"lost-proof",request)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).status()).isEqualTo(503));
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM reservation_releases").get(0,Integer.class)).isZero();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM cancellation_certificates").get(0,Integer.class)).isEqualTo(1);
        assertThat(scheduler.poll()).isZero();
        clock.advance(Duration.ofSeconds(2));var restarted=cancellations(new AtomicBoolean());restarted.poll();
        var result=restarted.cancel("supervisor","site-a",id,"lost-proof",request);
        assertThat(result.path("state").asString()).isEqualTo("CANCELLED");
        assertThat(restarted.cancel("supervisor","site-a",id,"lost-proof",request)).isEqualTo(result);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM reservation_releases").get(0,Integer.class)).isEqualTo(1);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM outbox WHERE event_type='MovementCancelled.v1'").get(0,Integer.class)).isEqualTo(1);
        coreDb.sql().execute("UPDATE service_control SET workers_paused=true");
        assertThat(restarted.cancel("supervisor","site-a",id,"lost-proof",request)).isEqualTo(result);
    }
    @Test void exhaustedCancellationCanResumeFromANewSessionWithVersionAndReason() {
        UUID order=accept("cancel-paused",new OrderService.Line("SKU-001",2));
        var lost=new AtomicBoolean(true);var coordinator=cancellations(lost);
        assertThatThrownBy(()->coordinator.cancel("first-supervisor","site-a",order,"original-key",cancellationRequest(order))).isInstanceOf(Problem.class);
        for(int i=0;i<5;i++){clock.advance(Duration.ofSeconds(20));observations.refresh();lost.set(true);coordinator.poll();}
        var paused=orders.get("site-a",order).path("cancellation");
        assertThat(paused.path("state").asString()).isEqualTo("PAUSED");
        assertThat(paused.path("attempts").asInt()).isEqualTo(6);
        UUID id=Database.uuid(paused,"cancellationId");
        var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",paused.path("version").asLong(),"reason","Response delivery repaired; resume the retained cancellation certificate."));
        assertThatThrownBy(()->coordinator.retry("second-supervisor","site-b",order,id,"retry",request)).isInstanceOf(Problem.class);
        var restarted=cancellations(new AtomicBoolean());
        var recorded=restarted.retry("second-supervisor","site-a",order,id,"retry",request);
        assertThat(restarted.retry("second-supervisor","site-a",order,id,"retry",request)).isEqualTo(recorded);
        assertThatThrownBy(()->restarted.retry("third-supervisor","site-a",order,id,"other",request)).isInstanceOf(Problem.class);
        restarted.poll();
        assertThat(orders.get("site-a",order).path("state").asString()).isEqualTo("CANCELLED");
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM reservation_releases").get(0,Integer.class)).isEqualTo(1);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM audit WHERE action='order-cancellation-retry' AND actor='second-supervisor'").get(0,Integer.class)).isEqualTo(1);
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM cancellation_certificates").get(0,Integer.class)).isEqualTo(1);
        assertThat(restarted.retry("second-supervisor","site-a",order,id,"retry",request)).isEqualTo(recorded);
    }
    @Test void simultaneousSupervisorsCannotBothCreateCancellationIntents() throws Exception {
        UUID order=accept("cancel-race",new OrderService.Line("SKU-001",2));
        var request=cancellationRequest(order);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<2;i++){final int actor=i;futures.add(workers.submit(()->{
                start.await();
                try {cancellations(new AtomicBoolean()).cancel("supervisor-"+actor,"site-a",order,"race-"+actor,request);return 200;}
                catch(Problem conflict){return conflict.status();}
            }));}
            start.countDown();
            assertThat(java.util.List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM order_cancellations").get(0,Integer.class)).isEqualTo(1);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM reservation_releases").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void actualSqlProposalSelectsHighestPriorityAndRetainsItsInputBeforeDispatch() {
        UUID normal=accept("decision-normal",new OrderService.Line("SKU-001",1));
        var body=(tools.jackson.databind.node.ObjectNode)request("decision-urgent",new OrderService.Line("SKU-003",1));body.put("priority",9);
        UUID urgent=Database.uuid(orders.accept("scenario","site-a","decision-urgent",body),"id");
        UUID urgentMovement=Database.uuid(orders.get("site-a",urgent).path("movements").get(0),"movementId");
        scheduler.poll();
        var first=coreDb.sql().fetchOne("SELECT input,input_hash,proposal FROM legacy_decision_rounds ORDER BY created_at,round_id LIMIT 1");
        var input=JsonSupport.read(first.get("input").toString());var proposal=JsonSupport.read(first.get("proposal").toString());
        assertThat(input.path("candidates")).hasSize(2);
        assertThat(JsonSupport.hash(input)).isEqualTo(first.get("input_hash"));
        assertThat(proposal.path("selectedMovementId").asString()).isEqualTo(urgentMovement.toString());
        assertThat(adapterDb.sql().fetchOne("SELECT command_id FROM command_journal ORDER BY created_at LIMIT 1").get(0,UUID.class)).isEqualTo(urgentMovement);
        finish(normal,"COMPLETED");finish(urgent,"COMPLETED");
    }
    @Test void exhaustedTaskTransportPausesUntilVersionedAuditedStatusRecovery() {
        UUID order=accept("task-transport",new OrderService.Line("SKU-001",1));
        var unavailable=new AtomicBoolean(true);var calls=new java.util.concurrent.atomic.AtomicInteger();
        scheduler=new LegacyScheduler(coreDb.sql(),new DispatchPort(){
            public JsonNode allocate(String site,JsonNode movement){calls.incrementAndGet();if(unavailable.get())throw new dev.cutover.platform.ServiceHttp.Unavailable("Test transport outage");return allocations.register(site,"legacy-core",movement);}
            public JsonNode equipment(String site){return observations.forSite(site);}
            public JsonNode command(String site,UUID id){try{return journal.get(site,id);}catch(Problem missing){if(missing.status()==404)return null;throw missing;}}
            public JsonNode context(String site,String zone,List<UUID> ids){return new dev.cutover.adapter.SchedulingContext(adapterDb.sql(),observations).read(site,zone,ids);}
            public JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload){return journal.record(site,"legacy-core",movement,allocation,epoch,lane,payload);}
        },orders,clock);
        for(int attempt=0;attempt<6;attempt++){scheduler.poll();clock.advance(Duration.ofSeconds(20));}
        var task=coreDb.sql().fetchOne("SELECT task_id,version,transport_paused,transport_failures FROM legacy_tasks WHERE order_id=?",order);
        assertThat(task.get("transport_paused",Boolean.class)).isTrue();assertThat(calls.get()).isEqualTo(6);
        scheduler.poll();assertThat(calls.get()).isEqualTo(6);
        UUID id=task.get("task_id",UUID.class);var body=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",task.get("version"),"reason","Adapter transport repaired; investigate the retained movement identity."));
        assertThatThrownBy(()->scheduler.resume("supervisor","site-b",id,"resume",body)).isInstanceOf(Problem.class);
        var response=scheduler.resume("supervisor","site-a",id,"resume",body);
        assertThat(scheduler.resume("supervisor","site-a",id,"resume",body)).isEqualTo(response);
        assertThatThrownBy(()->scheduler.resume("other-supervisor","site-a",id,"resume",body)).isInstanceOf(Problem.class);
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM audit WHERE action='legacy-task-recovery'").get(0,Integer.class)).isEqualTo(1);
        unavailable.set(false);finish(order,"COMPLETED");
        assertThat(simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void criticalCoreStorageStopsNewAllocationButRetainsAcceptedWork() {
        UUID id=accept("storage-paused",new OrderService.Line("SKU-001",2));
        coreDb.sql().execute("UPDATE service_control SET critical_storage=true");
        scheduler.poll();
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM movement_allocations").get(0,Integer.class)).isZero();
        assertThat(coreDb.sql().fetchOne("SELECT count(*) FROM reservations WHERE state='RESERVED'").get(0,Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->accept("storage-new",new OrderService.Line("SKU-001",1))).isInstanceOf(Problem.class);
        assertThat(orders.get("site-a",id).path("state").asString()).isEqualTo("RESERVED");
        coreDb.sql().execute("UPDATE service_control SET critical_storage=false");finish(id,"COMPLETED");
    }
    @Test void allocationResponseAfterAWorkerFreezeCannotMutateTheCheckpoint() {
        UUID id=accept("freeze-in-flight",new OrderService.Line("SKU-001",2));
        var late=new LegacyScheduler(coreDb.sql(),new DispatchPort(){
            public JsonNode allocate(String site,JsonNode movement){
                JsonNode result=allocations.register(site,"legacy-core",movement);
                coreDb.sql().execute("UPDATE service_control SET workers_paused=true");
                return result;
            }
            public JsonNode command(String site,UUID movement){return null;}
            public JsonNode equipment(String site){throw new AssertionError("Frozen core must not start a dispatch decision");}
            public JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload){throw new AssertionError("Frozen core must not submit");}
        },orders,clock);
        late.poll();
        assertThat(coreDb.sql().fetchOne("SELECT allocation_id,epoch,version FROM legacy_tasks WHERE order_id=?",id).intoArray()).containsExactly(null,null,0L);
        assertThat(coreDb.sql().fetchOne("SELECT state FROM movement_intents WHERE order_id=?",id).get(0,String.class)).isEqualTo("REQUESTED");
        assertThat(adapterDb.sql().fetchOne("SELECT count(*) FROM command_journal").get(0,Integer.class)).isZero();
        coreDb.sql().execute("UPDATE service_control SET workers_paused=false");clock.advance(Duration.ofSeconds(61));finish(id,"COMPLETED");
    }
}
