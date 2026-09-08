package dev.cutover.returns;

import dev.cutover.adapter.*;
import dev.cutover.platform.*;
import dev.cutover.platform.messaging.*;
import dev.cutover.simulator.SimulatorEngine;
import dev.cutover.testing.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Three real owner databases; no fulfilment service, scheduler, schema or model is on this path. */
class ReturnsWorkflowTest {
    static DatabaseFixture returns,adapter,physical;
    MutableClock clock;ReceiptService receipts;ReturnsCoordinator coordinator;DispatchPort port;
    SimulatorEngine simulator;EquipmentObservations observations;Allocations allocations;CommandJournal journal;
    DurableInbox adapterInbox,returnsInbox;Set<UUID> requested,observed;
    AtomicBoolean contextDown,loseDispatchReply,losePhysicalReply,freezeContext,failCompletion;
    AtomicInteger dispatchCalls;
    @BeforeAll static void databases(){returns=new DatabaseFixture("returns-service");adapter=new DatabaseFixture("equipment-adapter");physical=new DatabaseFixture("equipment-simulator");}
    @AfterAll static void close(){returns.close();adapter.close();physical.close();}
    @BeforeEach void reset(){
        returns.reset();adapter.reset();physical.reset();clock=new MutableClock(Instant.now().plusSeconds(2));
        contextDown=new AtomicBoolean();loseDispatchReply=new AtomicBoolean();losePhysicalReply=new AtomicBoolean();freezeContext=new AtomicBoolean();failCompletion=new AtomicBoolean();dispatchCalls=new AtomicInteger();requested=new HashSet<>();observed=new HashSet<>();
        simulator=new SimulatorEngine(physical.sql(),clock);
        var equipment=new EquipmentPort(){
            public JsonNode equipment(){return simulator.equipment();}
            public Reply command(UUID id){try{return new Reply(200,simulator.status(id));}catch(Problem missing){if(missing.status()!=404)throw missing;return new Reply(404,simulator.equipment());}}
            public Reply send(UUID id,JsonNode payload){var result=simulator.accept(id,payload);if(losePhysicalReply.getAndSet(false))throw new Unavailable("Lost response after durable simulator acceptance");return new Reply(200,result.result());}
        };
        observations=new EquipmentObservations(adapter.sql(),equipment,clock);observations.refresh();allocations=new Allocations(adapter.sql(),clock);journal=new CommandJournal(adapter.sql(),equipment,observations,clock);receipts=new ReceiptService(returns.sql(),clock);
        port=new DispatchPort(){
            public JsonNode context(String site,List<UUID> movements){if(contextDown.get())throw new ServiceHttp.Unavailable("Adapter unavailable");var result=new SchedulingContext(adapter.sql(),observations).read(site,"returns",movements);if(freezeContext.getAndSet(false))returns.sql().execute("UPDATE service_control SET workers_paused=true");return result;}
            public JsonNode dispatch(String site,UUID id,UUID allocation,long epoch,String lane,JsonNode movement){dispatchCalls.incrementAndGet();var result=journal.record(site,"returns-service",id,allocation,epoch,lane,movement);if(loseDispatchReply.getAndSet(false))throw new ServiceHttp.Unavailable("Lost reply after journal commit");return result;}
        };
        coordinator=new ReturnsCoordinator(returns.sql(),port,receipts,clock);
        adapterInbox=new DurableInbox(adapter.sql(),new AdapterMessages(clock),clock,Set.of("returns-service"),Set.of("site-a"));
        var handler=new ReturnsMessages(receipts);
        returnsInbox=new DurableInbox(returns.sql(),(sql,event)->{handler.apply(sql,event);if(event.eventType().equals("MovementCompleted.v1") && failCompletion.get())throw new IllegalStateException("Crash after effects, before inbox commit");},clock,Set.of("equipment-adapter"),Set.of("site-a"));
    }
    ObjectNode request(String reference,int reusable,int cleaning,int damaged){var body=JsonSupport.MAPPER.createObjectNode().put("sourceSystem","fixture-receiving").put("externalReceiptRef",reference);body.putObject("counts").put("REUSABLE",reusable).put("NEEDS_CLEANING",cleaning).put("DAMAGED",damaged);return body;}
    UUID register(String reference,int reusable,int cleaning,int damaged){return Database.uuid(receipts.register("scenario","site-a",reference,request(reference,reusable,cleaning,damaged)),"id");}
    @Test void freshKeyBusinessDuplicatesRespectEveryStorageAdmissionGate(){
        var body=request("metadata-headroom",3,2,1);var first=receipts.register("scenario","site-a","original",body);var sql=returns.sql();
        long budget=sql.fetchOne("SELECT database_budget_bytes FROM admission").get(0,Long.class);
        String volume=sql.fetchOne("SELECT pg_get_functiondef('cutover_ops.volume_status()'::regprocedure)").get(0,String.class);
        for(String gate:List.of("UPDATE service_control SET critical_storage=true","UPDATE service_control SET intake_paused=true",
                "UPDATE admission SET database_budget_bytes=16777216","CREATE OR REPLACE FUNCTION cutover_ops.volume_status() RETURNS jsonb LANGUAGE sql AS $$ SELECT '{\"state\":\"STALE\"}'::jsonb $$")) {
            try {
                if(gate.contains("database_budget_bytes")) {
                    sql.execute("CREATE TABLE admission_pressure_fixture(payload text NOT NULL)");
                    sql.execute("ALTER TABLE admission_pressure_fixture ALTER COLUMN payload SET STORAGE PLAIN");
                    sql.execute("INSERT INTO admission_pressure_fixture SELECT repeat(md5(i::text),64) FROM generate_series(1,8192) i");
                    assertThat(sql.fetchOne("SELECT pg_database_size(current_database())").get(0,Long.class)).isGreaterThan(16777216L);
                }
                sql.execute(gate);
                assertThat(receipts.register("scenario","site-a","original",body)).isEqualTo(first);
                assertThatThrownBy(()->receipts.register("scenario","site-a","fresh-key",body)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status()).isEqualTo(503));
                assertThat(sql.fetchOne("SELECT count(*) FROM idempotency").get(0,Integer.class)).isEqualTo(1);
                assertThat(count(returns,"receipts")).isEqualTo(1);assertThat(total("received")).isEqualTo(6);assertThat(total("sorted")).isZero();
            } finally {sql.execute("UPDATE service_control SET critical_storage=false,intake_paused=false");sql.execute("UPDATE admission SET database_budget_bytes=?",budget);sql.execute(volume);sql.execute("DROP TABLE IF EXISTS admission_pressure_fixture");}
        }
        assertThat(receipts.register("scenario","site-a","fresh-key",body)).isEqualTo(first);
        assertThat(sql.fetchOne("SELECT count(*) FROM idempotency").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void intakeControllerRejectsRestrictedClientsBeforeBusinessWrites(){
        var controller=new ReturnsController(receipts,coordinator);var body=request("client-boundary",1,0,0);
        for(String client:List.of("shadow-scheduler","unregistered-source")) {
            var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("component-claims").header("alg","test").subject(client)
                    .claim("azp",client).claim("sites",List.of("site-a")).claim("realm_access",Map.of("roles",List.of("service"))).build();
            assertThatThrownBy(()->controller.register("site-a","restricted",body,jwt)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status()).isEqualTo(403));
        }
        assertThat(count(returns,"receipts")).isZero();assertThat(count(returns,"idempotency")).isZero();assertThat(total("received")).isZero();
        var allowed=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("component-claims").header("alg","test").subject("scenario")
                .claim("azp","scenario-driver").claim("sites",List.of("site-a")).claim("realm_access",Map.of("roles",List.of("scenario"))).build();
        assertThat(controller.register("site-a","allowed",body,allowed).getStatusCode().value()).isEqualTo(202);
        assertThat(count(returns,"receipts")).isEqualTo(1);assertThat(total("received")).isEqualTo(1);
    }
    @Test void newestReceiptPagingIsStableAcrossNewArrivalsAndForeignCursors(){
        UUID oldest=register("oldest",1,0,0);clock.advance(Duration.ofSeconds(1));
        UUID first=register("same-time-a",1,0,0),second=register("same-time-b",0,1,0);clock.advance(Duration.ofSeconds(1));
        UUID newest=register("newest",0,0,1);
        var tied=java.util.stream.Stream.of(first,second).sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        var page=receipts.list("site-a",null,2);assertThat(Database.uuid(page.path("items").get(0),"id")).isEqualTo(newest);assertThat(Database.uuid(page.path("items").get(1),"id")).isEqualTo(tied.getFirst());
        clock.advance(Duration.ofSeconds(1));register("later-arrival",1,0,0);
        var next=receipts.list("site-a",Database.uuid(page,"nextCursor"),2);assertThat(Database.uuid(next.path("items").get(0),"id")).isEqualTo(tied.getLast());assertThat(Database.uuid(next.path("items").get(1),"id")).isEqualTo(oldest);
        assertThat(receipts.list("site-a",oldest,2).path("items").size()).isZero();
        assertThat(receipts.list("site-b",newest,25).path("items").size()).isZero();
        assertThat(receipts.list("site-a",UUID.randomUUID(),25).path("items").size()).isZero();
        assertThat(total("received")).isEqualTo(5);assertThat(total("sorted")).isZero();
    }
    void deliver(DurableInbox inbox,String exchange,JsonNode event){inbox.receive(exchange,event.path("eventId").asString(),JsonSupport.write(event).getBytes(StandardCharsets.UTF_8));}
    void messages(){
        for(var row:returns.sql().fetch("SELECT event_id,envelope FROM outbox ORDER BY aggregate_id,aggregate_version"))if(requested.add(row.get("event_id",UUID.class)))deliver(adapterInbox,"cutover.returns-service.v1",JsonSupport.read(row.get("envelope").toString()));
        for(var row:adapter.sql().fetch("SELECT event_id,envelope FROM outbox ORDER BY aggregate_id,aggregate_version"))if(observed.add(row.get("event_id",UUID.class)))deliver(returnsInbox,"cutover.equipment-adapter.v1",JsonSupport.read(row.get("envelope").toString()));
    }
    void tick(){clock.advance(Duration.ofMillis(500));observations.refresh();allocations.releasePending();messages();coordinator.poll();journal.work();simulator.advance();messages();}
    void finish(UUID id){for(int n=0;n<50 && !receipts.get("site-a",id).path("state").asString().equals("COMPLETED");n++)tick();assertThat(receipts.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");}
    UUID movement(UUID receipt){return returns.sql().fetchOne("SELECT movement_id FROM return_movements WHERE receipt_id=?",receipt).get(0,UUID.class);}
    int count(DatabaseFixture db,String table){return db.sql().fetchOne("SELECT count(*) FROM "+table).get(0,Integer.class);}
    long total(String column){return returns.sql().fetchOne("SELECT sum("+column+") FROM crate_counters").get(0,Long.class);}
    ObjectNode newEvent(String type,UUID movement,long version){
        var event=(ObjectNode)JsonSupport.read(adapter.sql().fetchOne("SELECT envelope FROM outbox WHERE aggregate_id=? ORDER BY aggregate_version DESC LIMIT 1",movement).get(0).toString()).deepCopy();
        return event.put("eventId",UUID.randomUUID().toString()).put("eventType",type).put("aggregateVersion",version);
    }
    @Test void mixedReceiptUsesOnlyReturnsAssignmentsAndReconcilesThreeDestinations(){
        UUID id=register("mixed",7,3,2);assertThat(count(returns,"return_tasks")).isZero();assertThat(total("received")).isEqualTo(12);messages();
        assertThat(count(returns,"return_tasks")).isEqualTo(3);finish(id);
        assertThat(physical.sql().fetch("SELECT destination,quantity FROM execution_ledger ORDER BY destination").map(row->List.of(row.get(0),row.get(1))))
            .containsExactly(List.of("cleaning",3),List.of("damaged",2),List.of("reusable",7));
        assertThat(total("sorted")).isEqualTo(12);assertThat(count(returns,"sorting_ledger")).isEqualTo(3);
        assertThat(returns.sql().fetchOne("SELECT active_requests FROM admission").get(0,Integer.class)).isZero();
        assertThat(returns.sql().fetchOne("SELECT to_regclass('stock'),to_regclass('reservations'),to_regclass('execution_tasks'),to_regclass('legacy_tasks')").intoArray()).containsExactly(null,null,null,null);
        assertThat(adapter.sql().fetchOne("SELECT count(*) FROM movement_allocations WHERE owner='returns-service' AND zone_id='returns'").get(0,Integer.class)).isEqualTo(3);
        Contracts.validate("receipt-view.v1",JsonSupport.write(receipts.get("site-a",id)));
        Contracts.validate("receipt-page.v1",JsonSupport.write(receipts.list("site-a",null,25)));
        Contracts.validate("return-counters.v1",JsonSupport.write(receipts.counters("site-a")));
        Contracts.validate("return-task-list.v1",JsonSupport.write(coordinator.tasks("site-a")));
    }
    @Test void concurrentReceiptsAndNewTransportIdsCannotInflateEitherCounter(){
        var body=request("concurrent",4,0,2);
        try(var pool=Executors.newFixedThreadPool(6)){
            var futures=new ArrayList<Future<JsonNode>>();for(int n=0;n<6;n++){String key="parallel-"+n;futures.add(pool.submit(()->receipts.register("scenario","site-a",key,body)));}
            var ids=new HashSet<UUID>();for(var future:futures)try{ids.add(Database.uuid(future.get(20,TimeUnit.SECONDS),"id"));}catch(Exception failure){throw new AssertionError(failure);}
            assertThat(ids).hasSize(1);UUID receipt=ids.iterator().next();messages();
            for(var row:returns.sql().fetch("SELECT envelope FROM outbox WHERE event_type='MovementRequested.v1'")){
                var duplicate=(ObjectNode)JsonSupport.read(row.get(0).toString()).deepCopy();duplicate.put("eventId",UUID.randomUUID().toString()).put("aggregateVersion",2);deliver(adapterInbox,"cutover.returns-service.v1",duplicate);
            }
            finish(receipt);
            for(var row:adapter.sql().fetch("SELECT aggregate_id,max(aggregate_version) v FROM outbox GROUP BY aggregate_id"))deliver(returnsInbox,"cutover.equipment-adapter.v1",newEvent("MovementCompleted.v1",row.get("aggregate_id",UUID.class),row.get("v",Long.class)+1));
        }
        assertThat(count(returns,"receipts")).isEqualTo(1);assertThat(count(returns,"return_movements")).isEqualTo(2);assertThat(count(physical,"execution_ledger")).isEqualTo(2);assertThat(count(returns,"sorting_ledger")).isEqualTo(2);
        assertThat(total("received")).isEqualTo(6);assertThat(total("sorted")).isEqualTo(6);assertThat(count(adapter,"movement_allocations")).isEqualTo(2);
    }
    @Test void conflictingReferenceAndInvalidClassificationAreRejectedBeforeEffects(){
        var body=request("reference",2,0,0);receipts.register("scenario","site-a","same-key",body);
        assertThatThrownBy(()->receipts.register("scenario","site-a","same-key",request("reference",3,0,0))).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).status()).isEqualTo(409));
        assertThatThrownBy(()->receipts.register("scenario","site-a","new-key",request("reference",3,0,0))).isInstanceOf(Problem.class);
        for(var invalid:List.of(request("negative",-1,0,0),request("empty",0,0,0),request("large",10001,0,0),request("unknown",1,0,0).set("counts",JsonSupport.MAPPER.createObjectNode().put("OTHER",1))))
            assertThatThrownBy(()->receipts.register("scenario","site-a",UUID.randomUUID().toString(),invalid)).isInstanceOf(Problem.class);
        assertThat(count(returns,"receipts")).isEqualTo(1);assertThat(total("received")).isEqualTo(2);
    }
    @Test void sortedEffectRollsBackWithInboxBeforeRetry(){
        UUID id=register("rollback",3,0,0);messages();coordinator.poll();journal.work();clock.advance(Duration.ofSeconds(2));simulator.advance();journal.work();failCompletion.set(true);messages();
        assertThat(count(physical,"execution_ledger")).isEqualTo(1);assertThat(count(returns,"sorting_ledger")).isZero();assertThat(total("sorted")).isZero();
        assertThat(returns.sql().fetchOne("SELECT count(*) FROM inbox WHERE state='PENDING'").get(0,Integer.class)).isPositive();
        failCompletion.set(false);clock.advance(Duration.ofSeconds(20));returnsInbox.retry(32);
        assertThat(receipts.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");assertThat(total("sorted")).isEqualTo(3);
    }
    @Test void lostAdapterResponseUsesOriginalJournalInsteadOfAnotherDispatch(){
        UUID id=register("lost-adapter",2,0,0);messages();loseDispatchReply.set(true);coordinator.poll();assertThat(count(adapter,"command_journal")).isEqualTo(1);
        finish(id);assertThat(dispatchCalls.get()).isEqualTo(1);assertThat(count(physical,"execution_ledger")).isEqualTo(1);
    }
    @Test void physicalUncertaintyStopsPollingUntilAdapterCompletion(){
        UUID id=register("lost-physical",1,0,0);messages();coordinator.poll();losePhysicalReply.set(true);journal.work();messages();
        assertThat(receipts.get("site-a",id).path("state").asString()).isEqualTo("RECONCILIATION_REQUIRED");clock.advance(Duration.ofSeconds(2));assertThat(coordinator.poll()).isZero();
        finish(id);assertThat(dispatchCalls.get()).isEqualTo(1);assertThat(count(returns,"sorting_ledger")).isEqualTo(1);
    }
    @Test void lateUnknownAndAcceptedEventsCannotRegressCompletedMovement(){
        UUID id=register("late",1,0,0);finish(id);UUID movement=movement(id);long version=adapter.sql().fetchOne("SELECT max(aggregate_version) FROM outbox WHERE aggregate_id=?",movement).get(0,Long.class);
        deliver(returnsInbox,"cutover.equipment-adapter.v1",newEvent("CommandOutcomeUnknown.v1",movement,version+1));deliver(returnsInbox,"cutover.equipment-adapter.v1",newEvent("CommandAccepted.v1",movement,version+2));
        assertThat(receipts.get("site-a",id).path("state").asString()).isEqualTo("COMPLETED");assertThat(total("sorted")).isEqualTo(1);
    }
    @Test void lateUnknownForCompletedChildDoesNotRegressPartiallySortedReceipt(){
        UUID id=register("partial-late",1,1,0);messages();coordinator.poll();journal.work();
        UUID delayed=returns.sql().fetchOne("SELECT movement_id FROM return_movements WHERE receipt_id=? AND classification='NEEDS_CLEANING'",id).get(0,UUID.class);
        physical.sql().execute("UPDATE simulator_commands SET execute_after=?::timestamptz WHERE command_id=?",OffsetDateTime.ofInstant(clock.instant().plusSeconds(60),ZoneOffset.UTC),delayed);
        clock.advance(Duration.ofSeconds(2));simulator.advance();journal.work();messages();
        assertThat(receipts.get("site-a",id).path("state").asString()).isEqualTo("SORTING");assertThat(total("sorted")).isEqualTo(1);
        UUID completed=returns.sql().fetchOne("SELECT movement_id FROM sorting_ledger").get(0,UUID.class);
        long version=adapter.sql().fetchOne("SELECT max(aggregate_version) FROM outbox WHERE aggregate_id=?",completed).get(0,Long.class);
        deliver(returnsInbox,"cutover.equipment-adapter.v1",newEvent("CommandOutcomeUnknown.v1",completed,version+1));
        assertThat(receipts.get("site-a",id).path("state").asString()).isEqualTo("SORTING");clock.advance(Duration.ofSeconds(61));finish(id);
    }
    @Test void completingOneChildKeepsOtherPhysicalUncertaintyVisible(){
        UUID id=register("partial-unknown",1,1,0);messages();coordinator.poll();losePhysicalReply.set(true);journal.work();messages();
        UUID uncertain=adapter.sql().fetchOne("SELECT command_id FROM command_journal WHERE state='OUTCOME_UNKNOWN'").get(0,UUID.class);
        physical.sql().execute("UPDATE simulator_commands SET execute_after=?::timestamptz WHERE command_id=?",OffsetDateTime.ofInstant(clock.instant().plusSeconds(60),ZoneOffset.UTC),uncertain);
        clock.advance(Duration.ofSeconds(2));simulator.advance();journal.work();messages();
        assertThat(total("sorted")).isEqualTo(1);assertThat(receipts.get("site-a",id).path("state").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        clock.advance(Duration.ofSeconds(61));finish(id);assertThat(total("sorted")).isEqualTo(2);
    }
    @Test void failedOutboxCommitRollsBackTheEntireReceiptAndReceivedCounters(){
        returns.sql().execute("CREATE FUNCTION fail_receipt_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='ReturnReceiptRegistered.v1' THEN RAISE EXCEPTION 'Fixture commit fault'; END IF; RETURN NEW; END $$");
        returns.sql().execute("CREATE TRIGGER receipt_commit_fault BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION fail_receipt_event()");
        assertThatThrownBy(()->register("commit-fault",2,1,1)).isInstanceOf(RuntimeException.class);
        assertThat(count(returns,"receipts")).isZero();assertThat(count(returns,"return_movements")).isZero();assertThat(count(returns,"outbox")).isZero();assertThat(total("received")).isZero();assertThat(count(returns,"idempotency")).isZero();
        returns.sql().execute("DROP TRIGGER receipt_commit_fault ON outbox");finish(register("commit-fault",2,1,1));assertThat(total("sorted")).isEqualTo(4);
    }
    @Test void independentReturnLaneContinuesWhenEveryOutboundLaneIsBlocked(){
        physical.sql().execute("UPDATE lanes SET blocked=true WHERE zone_id IN ('ambient','chilled')");observations.refresh();UUID id=register("outbound-blocked",2,3,1);finish(id);
        assertThat(physical.sql().fetchOne("SELECT count(*) FROM simulator_commands WHERE lane_id IN (SELECT lane_id FROM lanes WHERE zone_id='returns')").get(0,Integer.class)).isEqualTo(3);
    }
    @Test void blockedReturnLaneWaitsWithoutChangingIdentityOrReceivedCounts(){
        physical.sql().execute("UPDATE lanes SET blocked=true WHERE zone_id='returns'");UUID id=register("returns-blocked",2,0,0);tick();
        assertThat(count(adapter,"command_journal")).isZero();assertThat(returns.sql().fetchOne("SELECT state FROM return_tasks").get(0,String.class)).isEqualTo("BLOCKED");
        UUID before=movement(id);physical.sql().execute("UPDATE lanes SET blocked=false WHERE zone_id='returns'");finish(id);assertThat(movement(id)).isEqualTo(before);assertThat(total("received")).isEqualTo(2);
    }
    @Test void transportExhaustionNeedsAuditedVersionedRecovery(){
        UUID id=register("transport",1,0,0);messages();contextDown.set(true);for(int n=0;n<6;n++){clock.advance(Duration.ofSeconds(20));coordinator.poll();}
        var task=returns.sql().fetchOne("SELECT * FROM return_tasks");assertThat(task.get("transport_failures",Integer.class)).isEqualTo(6);assertThat(task.get("transport_paused",Boolean.class)).isTrue();
        clock.advance(Duration.ofMinutes(2));assertThat(new ReturnsCoordinator(returns.sql(),port,receipts,clock).poll()).isZero();UUID taskId=task.get("task_id",UUID.class);
        var body=JsonSupport.MAPPER.createObjectNode().put("expectedVersion",task.get("version",Long.class)).put("reason","Adapter connectivity restored; investigate the original sorting command.");
        var result=coordinator.resume("supervisor","site-a",taskId,"recovery",body);assertThat(coordinator.resume("supervisor","site-a",taskId,"recovery",body)).isEqualTo(result);
        assertThatThrownBy(()->coordinator.resume("another-supervisor","site-a",taskId,"stale",body)).isInstanceOf(Problem.class);contextDown.set(false);finish(id);
        assertThat(count(returns,"audit")).isEqualTo(1);assertThat(count(physical,"execution_ledger")).isEqualTo(1);
    }
    @Test void checkpointFreezeCoversNewKeysAndThePostContextDispatchBarrier(){
        UUID id=register("freeze",1,0,0);messages();freezeContext.set(true);coordinator.poll();assertThat(count(adapter,"command_journal")).isZero();
        int keys=count(returns,"idempotency");assertThatThrownBy(()->receipts.register("scenario","site-a","new-key-during-freeze",request("freeze",1,0,0))).isInstanceOf(Problem.class);assertThat(count(returns,"idempotency")).isEqualTo(keys);
        returns.sql().execute("UPDATE service_control SET workers_paused=false");clock.advance(Duration.ofSeconds(61));finish(id);
    }
    @Test void emptyPollingUsesNoWriteTransactionAndNewArrivalStillRuns(){
        returns.sql().transaction(configuration->{var sql=DSL.using(configuration);assertThat(new ReturnsCoordinator(sql,port,new ReceiptService(sql,clock),clock).poll()).isZero();assertThat(sql.fetchOne("SELECT pg_current_xact_id_if_assigned()").get(0)).isNull();});
        finish(register("new-after-idle",1,0,0));
    }
    @Test void siteLookupsAndUntrustedCompletionsCannotChangeCounts(){
        UUID id=register("isolation",1,0,0);messages();UUID movement=movement(id);
        assertThatThrownBy(()->receipts.get("site-b",id)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).status()).isEqualTo(404));
        assertThat(receipts.list("site-b",null,25).path("items")).isEmpty();
        var forged=newEvent("MovementCompleted.v1",movement,2).put("source","returns-service");deliver(returnsInbox,"cutover.returns-service.v1",forged);
        var wrongSite=newEvent("MovementCompleted.v1",movement,2).put("siteId","site-b");deliver(returnsInbox,"cutover.equipment-adapter.v1",wrongSite);
        assertThat(total("sorted")).isZero();assertThat(count(returns,"sorting_ledger")).isZero();finish(id);
    }
}
