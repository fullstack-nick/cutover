package dev.cutover.core;

import dev.cutover.adapter.Allocations;
import dev.cutover.adapter.CommandJournal;
import dev.cutover.adapter.EquipmentObservations;
import dev.cutover.adapter.EquipmentPort;
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
}
