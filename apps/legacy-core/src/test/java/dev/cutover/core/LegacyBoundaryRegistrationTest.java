package dev.cutover.core;

import dev.cutover.adapter.BaselineRegistrations;
import dev.cutover.platform.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class LegacyBoundaryRegistrationTest {
    private LegacyWorkflowTest fixture;
    @BeforeAll static void databases(){LegacyWorkflowTest.databases();}
    @AfterAll static void stop(){LegacyWorkflowTest.stop();}
    @BeforeEach void reset(){fixture=new LegacyWorkflowTest();fixture.reset();}
    private void pause(){LegacyWorkflowTest.coreDb.sql().execute("UPDATE service_control SET intake_paused=true,dispatch_paused=true,version=101");LegacyWorkflowTest.adapterDb.sql().execute("UPDATE service_control SET dispatch_paused=true,version=102");}
    private LegacyBoundaryRegistration recorder(){return new LegacyBoundaryRegistration(LegacyWorkflowTest.coreDb.sql(),(site,body)->new BaselineRegistrations(LegacyWorkflowTest.adapterDb.sql()).register(site,"legacy-core",body));}
    private void settled(){
        var moved=fixture.accept("registered-completed",new OrderService.Line("SKU-001",1),new OrderService.Line("SKU-002",1));fixture.finish(moved,"COMPLETED");
        var cancelled=fixture.accept("registered-cancelled",new OrderService.Line("SKU-003",1));fixture.cancellations(new AtomicBoolean()).cancel("supervisor","site-a",cancelled,"cancel",fixture.cancellationRequest(cancelled));
    }
    private static final String REASON="Verify the settled original task inventory before replacing automatic task creation.";
    @Test void completeInventoryPreservesIdsAndLinksCancelledTombstonesWithoutBusinessEffects(){
        settled();var sql=LegacyWorkflowTest.coreDb.sql();var before=sql.fetch("SELECT task_id FROM legacy_tasks ORDER BY task_id").getValues(0);
        var stock=sql.fetch("SELECT site_id,sku,on_hand,reserved FROM stock ORDER BY site_id,sku");
        assertThat(sql.fetchOne("SELECT count(*) FROM legacy_tasks WHERE allocation_id IS NULL").get(0,Integer.class)).isEqualTo(1);
        pause();UUID id=UUID.randomUUID();var result=recorder().register("site-a",id,102,REASON);
        assertThat(result.path("state").asString()).isEqualTo("VERIFIED");assertThat(result.path("taskCount").asInt()).isEqualTo(3);
        assertThat(sql.fetch("SELECT task_id FROM legacy_tasks ORDER BY task_id").getValues(0)).isEqualTo(before);
        assertThat(sql.fetch("SELECT site_id,sku,on_hand,reserved FROM stock ORDER BY site_id,sku")).isEqualTo(stock);
        assertThat(sql.fetchOne("SELECT count(*) FROM legacy_task_registration").get(0,Integer.class)).isEqualTo(3);
        assertThat(sql.fetchOne("SELECT count(*) FROM legacy_tasks WHERE allocation_id IS NULL").get(0,Integer.class)).isZero();
        assertThat(LegacyWorkflowTest.adapterDb.sql().fetchOne("SELECT count(*) FROM legacy_registration_receipts").get(0,Integer.class)).isEqualTo(3);
        assertThat(recorder().register("site-a",id,102,REASON)).isEqualTo(result);
        assertThatThrownBy(()->recorder().register("site-a",id,102,REASON+" Changed.")).isInstanceOf(Problem.class);
        assertThat(sql.fetchOne("SELECT count(*) FROM audit WHERE action='legacy-boundary-registration'").get(0,Integer.class)).isEqualTo(1);
        assertThat(LegacyWorkflowTest.simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void openStaleAndFrozenGatesCannotProduceARegistrationCheckpoint(){
        UUID id=UUID.randomUUID();assertThatThrownBy(()->recorder().register("site-a",id,102,REASON)).isInstanceOf(Problem.class);
        pause();assertThatThrownBy(()->recorder().register("site-a",id,101,REASON)).isInstanceOf(Problem.class);
        LegacyWorkflowTest.coreDb.sql().execute("UPDATE service_control SET workers_paused=true");
        assertThatThrownBy(()->recorder().register("site-a",id,102,REASON)).isInstanceOf(Problem.class);
        assertThat(LegacyWorkflowTest.coreDb.sql().fetchOne("SELECT count(*) FROM legacy_boundary_checkpoints").get(0,Integer.class)).isZero();
    }
    @Test void missingRetainedAdapterEvidenceCannotBeInventedFromCompletedCoreState(){
        var moved=fixture.accept("missing-history",new OrderService.Line("SKU-001",1));fixture.finish(moved,"COMPLETED");
        // An independently restored empty adapter is missing context while the physical database survives.
        LegacyWorkflowTest.adapterDb.reset();pause();
        assertThatThrownBy(()->recorder().register("site-a",UUID.randomUUID(),102,REASON)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).code()).isEqualTo("BASELINE_ALLOCATION_MISSING"));
        assertThat(LegacyWorkflowTest.coreDb.sql().fetchOne("SELECT count(*) FROM legacy_task_registration").get(0,Integer.class)).isZero();
        assertThat(LegacyWorkflowTest.simulatorDb.sql().fetchOne("SELECT count(*) FROM execution_ledger").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void unsettledOriginalWorkBlocksThisInitialBoundary(){
        fixture.accept("unsettled",new OrderService.Line("SKU-001",1));pause();
        assertThatThrownBy(()->recorder().register("site-a",UUID.randomUUID(),102,REASON)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).code()).isEqualTo("BASELINE_UNSETTLED"));
        assertThat(LegacyWorkflowTest.coreDb.sql().fetchOne("SELECT sum(reserved) FROM stock").get(0,Long.class)).isEqualTo(1);
    }
    @Test void lostBatchResponseResumesOriginalRegistrationWithoutPartialCoreLinks(){
        settled();pause();UUID id=UUID.randomUUID();var lose=new AtomicBoolean(true);
        var recorder=new LegacyBoundaryRegistration(LegacyWorkflowTest.coreDb.sql(),(site,body)->{
            JsonNode response=new BaselineRegistrations(LegacyWorkflowTest.adapterDb.sql()).register(site,"legacy-core",body);
            if(lose.getAndSet(false))throw new ServiceHttp.Unavailable("Lost registration response after adapter commit");return response;
        });
        assertThatThrownBy(()->recorder.register("site-a",id,102,REASON)).isInstanceOf(ServiceHttp.Unavailable.class);
        assertThat(LegacyWorkflowTest.coreDb.sql().fetchOne("SELECT count(*) FROM legacy_boundary_checkpoints").get(0,Integer.class)).isZero();
        assertThat(LegacyWorkflowTest.coreDb.sql().fetchOne("SELECT count(*) FROM legacy_tasks WHERE allocation_id IS NULL").get(0,Integer.class)).isEqualTo(1);
        assertThat(recorder.register("site-a",id,102,REASON).path("state").asString()).isEqualTo("VERIFIED");
        assertThat(LegacyWorkflowTest.adapterDb.sql().fetchOne("SELECT count(*) FROM legacy_registration_receipts").get(0,Integer.class)).isEqualTo(3);
    }
    @Test void coreGateChangeDuringRemoteEvidenceCollectionPreventsFinalization(){
        settled();pause();var changed=new AtomicBoolean();
        var recorder=new LegacyBoundaryRegistration(LegacyWorkflowTest.coreDb.sql(),(site,body)->{
            var result=new BaselineRegistrations(LegacyWorkflowTest.adapterDb.sql()).register(site,"legacy-core",body);
            if(!changed.getAndSet(true))LegacyWorkflowTest.coreDb.sql().execute("UPDATE service_control SET version=version+1");
            return result;
        });
        assertThatThrownBy(()->recorder.register("site-a",UUID.randomUUID(),102,REASON)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).code()).isEqualTo("REGISTRATION_INVENTORY_CHANGED"));
        assertThat(LegacyWorkflowTest.coreDb.sql().fetchOne("SELECT count(*) FROM legacy_task_registration").get(0,Integer.class)).isZero();
        assertThat(LegacyWorkflowTest.coreDb.sql().fetchOne("SELECT count(*) FROM legacy_tasks WHERE allocation_id IS NULL").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void emptyBaselineStillRequiresBothPausedGatesAndRecordsZeroInventory(){
        pause();var result=recorder().register("site-a",UUID.randomUUID(),102,REASON);
        assertThat(result.path("taskCount").asInt()).isZero();assertThat(result.path("state").asString()).isEqualTo("VERIFIED");
    }
    private void applyBoundary(){org.flywaydb.core.Flyway.configure().dataSource(LegacyWorkflowTest.coreDb.dataSource()).locations("classpath:db/platform","classpath:db/legacy-core").load().migrate();}
    @Test void migrationRefusesMissingRegistrationAndStaleGateWithoutChangingTheOriginalTrigger(){
        settled();pause();var sql=LegacyWorkflowTest.coreDb.sql();
        assertThatThrownBy(this::applyBoundary).hasStackTraceContaining("BASELINE_REGISTRATION_REQUIRED");
        assertThat(sql.fetchOne("SELECT count(*) FROM pg_trigger WHERE tgname='reservation_creates_legacy_task'").get(0,Integer.class)).isEqualTo(1);
        recorder().register("site-a",UUID.randomUUID(),102,REASON);sql.execute("UPDATE service_control SET version=version+1");
        assertThatThrownBy(this::applyBoundary).hasStackTraceContaining("BASELINE_REGISTRATION_REQUIRED");
        assertThat(sql.fetchOne("SELECT count(*) FROM pg_trigger WHERE tgname='reservation_creates_legacy_task'").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void verifiedMigrationRetainsEveryOriginalTaskAndSwitchesOnlyNewTaskCreation(){
        settled();pause();var sql=LegacyWorkflowTest.coreDb.sql();recorder().register("site-a",UUID.randomUUID(),102,REASON);
        var before=sql.fetch("SELECT * FROM legacy_tasks ORDER BY task_id");applyBoundary();
        assertThat(sql.fetch("SELECT * FROM legacy_tasks ORDER BY task_id")).isEqualTo(before);
        assertThat(sql.fetchOne("SELECT original_task_count FROM legacy_task_boundary").get(0,Integer.class)).isEqualTo(3);
        sql.execute("UPDATE service_control SET intake_paused=false,dispatch_paused=false,version=version+1");LegacyWorkflowTest.adapterDb.sql().execute("UPDATE service_control SET dispatch_paused=false,version=version+1");
        var id=fixture.accept("first-assigned-task",new OrderService.Line("SKU-005",2));
        assertThat(sql.fetchOne("SELECT count(*) FROM legacy_tasks WHERE order_id=?",id).get(0,Integer.class)).isZero();
        var intent=sql.fetchOne("SELECT movement_id,movement FROM movement_intents WHERE order_id=?",id);UUID movement=intent.get("movement_id",UUID.class);
        var allocation=fixture.allocations.register("site-a","legacy-core",JsonSupport.read(intent.get("movement").toString()));
        var event=new Events.Envelope(UUID.randomUUID(),"MovementAssigned.v1",1,fixture.clock.instant(),"site-a","equipment-adapter","movement",movement,1,id,null,null,allocation);
        var handler=new CoreMessages(fixture.clock);
        sql.transaction(c->handler.apply(org.jooq.impl.DSL.using(c),event));
        var task=sql.fetchOne("SELECT task_id,priority,allocation_id FROM legacy_tasks WHERE movement_id=?",movement);
        assertThat(task.get("priority",Integer.class)).isEqualTo(500);assertThat(task.get("allocation_id",UUID.class)).isEqualTo(Database.uuid(allocation,"allocationId"));
        sql.transaction(c->handler.apply(org.jooq.impl.DSL.using(c),event));
        assertThat(sql.fetchOne("SELECT task_id FROM legacy_tasks WHERE movement_id=?",movement).get(0)).isEqualTo(task.get("task_id"));
        fixture.finish(id,"COMPLETED");
        assertThat(sql.fetchOne("SELECT count(*) FROM inventory_ledger WHERE movement_id=?",movement).get(0,Integer.class)).isEqualTo(1);
    }
    @Test void emptySchemaCanInstallBoundaryAndOtherOwnerAssignmentDoesNotCreateALegacyTask(){
        applyBoundary();var sql=LegacyWorkflowTest.coreDb.sql();LegacyWorkflowTest.adapterDb.sql().execute("UPDATE zone_routes SET owner='execution-service',epoch=1 WHERE site_id='site-a' AND zone_id='ambient'");
        UUID order=fixture.accept("execution-owned-intent",new OrderService.Line("SKU-007",1));
        var intent=sql.fetchOne("SELECT movement_id,movement FROM movement_intents WHERE order_id=?",order);UUID movement=intent.get("movement_id",UUID.class);
        var allocation=fixture.allocations.register("site-a","legacy-core",JsonSupport.read(intent.get("movement").toString()));
        var event=new Events.Envelope(UUID.randomUUID(),"MovementAssigned.v1",1,fixture.clock.instant(),"site-a","equipment-adapter","movement",movement,1,order,null,null,allocation);
        sql.transaction(c->new CoreMessages(fixture.clock).apply(org.jooq.impl.DSL.using(c),event));
        assertThat(sql.fetchOne("SELECT count(*) FROM legacy_tasks").get(0,Integer.class)).isZero();
        assertThat(sql.fetchOne("SELECT state FROM movement_intents WHERE movement_id=?",movement).get(0,String.class)).isEqualTo("ASSIGNED");
        var changed=allocation.deepCopy();((tools.jackson.databind.node.ObjectNode)changed.path("movement")).put("quantity",2);
        var invalid=new Events.Envelope(UUID.randomUUID(),"MovementAssigned.v1",1,fixture.clock.instant(),"site-a","equipment-adapter","movement",movement,2,order,null,null,changed);
        assertThatThrownBy(()->sql.transaction(c->new CoreMessages(fixture.clock).apply(org.jooq.impl.DSL.using(c),invalid))).hasStackTraceContaining("IMMUTABLE_MOVEMENT_INTENT");
    }
}
