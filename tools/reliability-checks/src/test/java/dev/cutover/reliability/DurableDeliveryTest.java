package dev.cutover.reliability;

import com.rabbitmq.client.ConnectionFactory;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.messaging.*;
import dev.cutover.testing.DatabaseFixture;
import dev.cutover.testing.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.impl.DSL;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL transactions and real quorum deliveries; crash hooks identify exact commit boundaries. */
class DurableDeliveryTest {
    static DatabaseFixture db;
    static RabbitMQContainer broker;
    static com.rabbitmq.client.Connection admin;
    CachingConnectionFactory connections;
    RabbitTemplate template;
    RabbitDelivery rabbit;
    MutableClock clock;
    DurableInbox inbox;
    String queue;
    static final String EXCHANGE = "cutover.producer.v1";
    static final MessageHandler EFFECT = (sql, event) -> sql.execute("INSERT INTO effects(event_id,aggregate_id,version,value) VALUES (?,?,?,?)", event.eventId(), event.aggregateId(), event.aggregateVersion(), event.payload().path("value").asInt());

    @BeforeAll static void start() throws Exception {
        db = new DatabaseFixture("reliability");
        broker = new RabbitMQContainer(System.getProperty("cutover.rabbitmq.image"))
                .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2").withLabel("dev.cutover.purpose", "reliability-verification");
        broker.start();
        var factory = new ConnectionFactory(); factory.setUri(broker.getAmqpUrl()); admin = factory.newConnection();
        try (var channel = admin.createChannel()) { channel.exchangeDeclare(EXCHANGE, "topic", true); }
    }
    @AfterAll static void stop() throws Exception { if (admin != null) admin.close(); if (broker != null) broker.stop(); if (db != null) db.close(); }
    @BeforeEach void reset() throws Exception {
        db.reset(); clock = new MutableClock(Instant.now().plusSeconds(60));
        var factory = new ConnectionFactory(); factory.setUri(broker.getAmqpUrl()); factory.setAutomaticRecoveryEnabled(false);
        connections = new CachingConnectionFactory(factory);
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        template = new RabbitTemplate(connections); rabbit = new RabbitDelivery(template, DeliveryHooks.NONE);
        inbox = new DurableInbox(db.sql(), EFFECT, clock, Set.of("producer"), Set.of("site-a"));
        queue = "cutover-verification-" + UUID.randomUUID();
        try (var channel = admin.createChannel()) {
            channel.queueDeclare(queue, true, false, false, Map.of("x-queue-type", "quorum"));
            channel.queueBind(queue, EXCHANGE, "#");
        }
    }
    @AfterEach void cleanup() throws Exception { connections.destroy(); try (var channel = admin.createChannel()) { channel.queueDelete(queue); } }
    OutboxRelay relay(DeliveryHooks hooks) { return new OutboxRelay(db.sql(), rabbit, clock, hooks); }
    UUID append(UUID aggregate, long version) {
        db.sql().transaction(configuration -> Events.append(DSL.using(configuration), "site-a", "producer", "thing", aggregate, version, "Changed.v1", aggregate, JsonSupport.MAPPER.valueToTree(Map.of("value", version))));
        return db.sql().fetchOne("SELECT event_id FROM outbox WHERE aggregate_id=? AND aggregate_version=?", aggregate, version).get(0, UUID.class);
    }
    Events.Envelope event(UUID aggregate, long version, int value) { return new Events.Envelope(UUID.randomUUID(), "Changed.v1", 1, clock.instant(), "site-a", "producer", "thing", aggregate, version, aggregate, null, null, JsonSupport.MAPPER.valueToTree(Map.of("value", value))); }
    void deliver(Events.Envelope event) { inbox.receive(EXCHANGE, event.eventId().toString(), JsonSupport.write(event).getBytes(StandardCharsets.UTF_8)); }
    int count(String table) { return db.sql().fetchOne("SELECT count(*) FROM " + table).get(0, Integer.class); }
    String state(UUID id) { return db.sql().fetchOne("SELECT state FROM inbox WHERE event_id=?", id).get(0, String.class); }
    static final class Crash extends Error {}

    @Test void emptyDeliveryPollsNeedNoWriteTransactionAndStillNoticeLaterWork() {
        db.sql().transaction(configuration -> {
            var sql = DSL.using(configuration);
            sql.execute("SET TRANSACTION READ ONLY");
            assertThat(new OutboxRelay(sql, rabbit, clock, DeliveryHooks.NONE).poll(16)).isZero();
            assertThat(new DurableInbox(sql, EFFECT, clock, Set.of("producer"), Set.of("site-a")).retry(16)).isZero();
            assertThat(sql.fetchOne("SELECT pg_current_xact_id_if_assigned()::text").get(0)).isNull();
        });
        UUID event = append(UUID.randomUUID(), 1);
        assertThat(relay(DeliveryHooks.NONE).poll(16)).isEqualTo(1);
        assertThat(rabbit.consume(queue, inbox, 16)).isEqualTo(1);
        assertThat(state(event)).isEqualTo("APPLIED");
        assertThat(count("effects")).isEqualTo(1);
    }

    @Test void publishedHistoryHasASeparateHardBudgetAndIntakeHeadroom() {
        db.sql().execute("UPDATE admission SET retained_outbox_limit=65536");
        int committed=0;
        for(int i=0;i<30;i++) {
            UUID id=UUID.randomUUID();
            try {
                db.sql().transaction(configuration->{var sql=DSL.using(configuration);sql.execute("INSERT INTO effects(event_id,aggregate_id,version,value) VALUES (?,?,1,1)",id,id);Events.append(sql,"site-a","producer","thing",id,1,"Changed.v1",id,JsonSupport.MAPPER.valueToTree(Map.of("padding","x".repeat(6000))));});
                committed++;
                var claimed=relay(DeliveryHooks.NONE).claim();relay(DeliveryHooks.NONE).confirmed(claimed);
            } catch(Problem full) {assertThat(full.code()).isEqualTo("OUTBOX_CAPACITY");break;}
        }
        assertThat(committed).isBetween(8,11);
        assertThat(count("effects")).isEqualTo(committed);
        assertThat(count("outbox")).isEqualTo(committed);
        assertThat(db.sql().fetchOne("SELECT unpublished_events,retained_outbox_bytes FROM admission").get("unpublished_events",Integer.class)).isZero();
        assertThat(db.sql().fetchOne("SELECT retained_outbox_bytes FROM admission").get(0,Long.class)).isLessThanOrEqualTo(65536);
        assertThatThrownBy(()->db.sql().transaction(configuration->dev.cutover.platform.Database.requireDurability(DSL.using(configuration),true))).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).code()).isEqualTo("STORAGE_HEADROOM"));
        assertThat(dev.cutover.platform.StorageBudget.status(db.sql()).path("databaseBytes").asLong()).isPositive();
    }

    @Test void retentionKeepsTheReplayHorizonPendingWorkAndPermanentDuplicateIdentities() {
        UUID published=append(UUID.randomUUID(),1);relay(DeliveryHooks.NONE).poll(16);rabbit.consume(queue,inbox,16);
        var event=JsonSupport.MAPPER.readValue(db.sql().fetchOne("SELECT envelope FROM inbox WHERE event_id=?",published).get(0).toString(),Events.Envelope.class);
        var pending=event(UUID.randomUUID(),2,2);deliver(pending);
        UUID unpublished=append(UUID.randomUUID(),1);
        var retention=new MessageRetention(db.sql(),clock);
        clock.advance(Duration.ofDays(6));assertThat(retention.compact()).isZero();
        clock.advance(Duration.ofDays(2));assertThat(retention.compact()).isEqualTo(2);
        assertThat(db.sql().fetchExists(DSL.table("outbox"),DSL.field("event_id").eq(published))).isFalse();
        assertThat(db.sql().fetchOne("SELECT envelope,raw_body,payload_bytes FROM inbox WHERE event_id=?",published).intoArray()).containsExactly(null,null,0);
        assertThat(db.sql().fetchExists(DSL.table("outbox"),DSL.field("event_id").eq(unpublished))).isTrue();
        assertThat(state(pending.eventId())).isEqualTo("PENDING");
        deliver(event);assertThat(count("effects")).isEqualTo(1);
        var conflicting=new Events.Envelope(event.eventId(),event.eventType(),1,event.occurredAt(),event.siteId(),event.source(),event.aggregateType(),event.aggregateId(),1,event.correlationId(),null,null,JsonSupport.MAPPER.valueToTree(Map.of("value",999)));
        deliver(conflicting);assertThat(count("delivery_quarantine")).isEqualTo(1);assertThat(count("effects")).isEqualTo(1);
        assertThat(db.sql().fetchOne("SELECT retained_bytes=(SELECT COALESCE(sum(payload_bytes),0) FROM inbox)+(SELECT COALESCE(sum(payload_bytes),0) FROM delivery_quarantine) FROM message_storage").get(0,Boolean.class)).isTrue();
        assertThat(db.sql().fetchOne("SELECT retained_outbox_bytes=(SELECT COALESCE(sum(payload_bytes),0) FROM outbox) FROM admission").get(0,Boolean.class)).isTrue();
    }

    @Test void sourceConfigurationRepairTransfersOriginalQuarantineBytesThroughTheInboxOnce() {
        var event=event(UUID.randomUUID(),1,42);byte[] original=JsonSupport.write(event).getBytes(StandardCharsets.UTF_8);
        var misconfigured=new DurableInbox(db.sql(),EFFECT,clock,Set.of("other-source"),Set.of("site-a"));
        UUID raw=misconfigured.receive(EXCHANGE,event.eventId().toString(),original);
        assertThat(count("effects")).isZero();
        var operations=new QuarantineOperations(db.sql(),EFFECT,new MessageSubscription(queue,Set.of("producer"),Set.of("site-a")),clock);
        assertThat(operations.untrustedDiagnostics()).hasSize(1);
        assertThat(operations.untrustedDiagnostics().get(0).has("transportMessageId")).isFalse();
        var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",1,"reason","Repaired the receiving publisher allowlist to the reviewed source configuration."));
        var result=operations.reprocess("supervisor","site-a",raw,"repair",request);
        assertThat(result.path("state").asString()).isEqualTo("TRANSFERRED");
        assertThat(operations.reprocess("supervisor","site-a",raw,"repair",request)).isEqualTo(result);
        assertThat(db.sql().fetchOne("SELECT raw_body FROM delivery_quarantine WHERE delivery_id=?",raw).get(0,byte[].class)).containsExactly(original);
        assertThat(db.sql().fetchOne("SELECT event_id FROM effects").get(0,UUID.class)).isEqualTo(event.eventId());
        assertThat(count("audit")).isEqualTo(1);assertThat(count("inbox")).isEqualTo(1);
        assertThat(db.sql().fetchOne("SELECT active_messages FROM message_storage").get(0,Integer.class)).isZero();
        clock.advance(Duration.ofDays(8));new MessageRetention(db.sql(),clock).compact();
        assertThat(db.sql().fetchOne("SELECT raw_body FROM delivery_quarantine WHERE delivery_id=?",raw).get(0)).isNull();
        deliver(event);assertThat(count("effects")).isEqualTo(1);
    }

    @Test void untrustedSiteAndMalformedQuarantineCannotBeReinterpretedAsLocalWork() {
        var wrongSite=new Events.Envelope(UUID.randomUUID(),"Changed.v1",1,clock.instant(),"site-b","producer","thing",UUID.randomUUID(),1,UUID.randomUUID(),null,null,JsonSupport.MAPPER.valueToTree(Map.of("value",1)));
        UUID raw=inbox.receive(EXCHANGE,wrongSite.eventId().toString(),JsonSupport.write(wrongSite).getBytes(StandardCharsets.UTF_8));
        UUID malformed=inbox.receive(EXCHANGE,"untrusted",new byte[]{(byte)0xc3,(byte)0x28});
        var operations=new QuarantineOperations(db.sql(),EFFECT,new MessageSubscription(queue,Set.of("producer"),Set.of("site-a")),clock);
        var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",1,"reason","Inspect the original delivery without changing its claimed site or bytes."));
        for(UUID id:java.util.List.of(raw,malformed))for(String site:java.util.List.of("site-a","site-b"))assertThatThrownBy(()->operations.reprocess("supervisor",site,id,"probe-"+id+site,request)).isInstanceOf(Problem.class).satisfies(error->assertThat(((Problem)error).status()).isEqualTo(404));
        assertThat(operations.status("site-a")).isEmpty();assertThat(count("effects")).isZero();assertThat(count("audit")).isZero();
    }

    @Test void frozenWorkersPreventRetentionAndManualDeliveryRecovery() {
        UUID id=append(UUID.randomUUID(),1);relay(DeliveryHooks.NONE).poll(16);rabbit.consume(queue,inbox,16);clock.advance(Duration.ofDays(8));
        db.sql().execute("UPDATE service_control SET workers_paused=true");
        assertThat(new MessageRetention(db.sql(),clock).compact()).isZero();
        long version=db.sql().fetchOne("SELECT version FROM outbox WHERE event_id=?",id).get(0,Long.class);
        var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",version,"reason","A frozen checkpoint must prevent a new manual replay mutation."));
        assertThatThrownBy(()->new MessagingOperations(db.sql()).recover("supervisor","site-a","outbox",id,"paused",request)).isInstanceOf(Problem.class);
        assertThat(count("audit")).isZero();assertThat(count("outbox")).isEqualTo(1);
    }

    @Test void processFaultCommitsItsOneShotConsumptionBeforeTerminating() {
        var controls=new dev.cutover.platform.control.RuntimeControls(db.sql());
        for (String checkpoint:java.util.List.of("AFTER_BUSINESS_COMMIT","AFTER_BROKER_CONFIRM","AFTER_EFFECT_BEFORE_ACK")) {
            var hooks=new dev.cutover.platform.control.ProcessFaults(db.sql(),clock,code->{assertThat(code).isEqualTo(73);throw new Crash();});
            UUID id=append(UUID.randomUUID(),1);
            if (checkpoint.equals("AFTER_EFFECT_BEFORE_ACK")) { relay(DeliveryHooks.NONE).poll(16); rabbit.consume(queue,inbox,16); }
            var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",controls.status("site-a").path("version").asLong(),"checkpoint",checkpoint,"eventId",id,"reason","Verify the one-shot durable process crash boundary."));
            var armed=controls.arm("scenario","site-a",checkpoint,request);
            assertThat(controls.arm("scenario","site-a",checkpoint,request)).isEqualTo(armed);
            assertThatThrownBy(()->hooks.reached(checkpoint,id)).isInstanceOf(Crash.class);
            assertThat(db.sql().fetchOne("SELECT remaining FROM process_faults WHERE fault_id=?",UUID.fromString(armed.path("faultId").asString())).get(0,Integer.class)).isZero();
            var restarted=new dev.cutover.platform.control.ProcessFaults(db.sql(),clock,code->{throw new AssertionError("A consumed fault fired twice");});
            restarted.reached(checkpoint,id);
        }
        assertThat(db.sql().fetchOne("SELECT count(*) FROM audit WHERE action='process-fault-fired'").get(0,Integer.class)).isEqualTo(3);
    }

    @Test void checkpointControlsFreezeAllFaultMutationsUntilExplicitResume() {
        var controls=new dev.cutover.platform.control.RuntimeControls(db.sql());
        UUID event=append(UUID.randomUUID(),1);
        var arm=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",0,"checkpoint","AFTER_BUSINESS_COMMIT","eventId",event,"reason","Verify the fault stays unchanged throughout the frozen checkpoint."));
        var armed=controls.arm("scenario","site-a","arm",arm);
        var pause=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",1,"workersPaused",true,"reason","Freeze application writes while the checkpoint is exported."));
        controls.change("scenario","site-a","freeze",pause);
        long audits=count("audit"),requests=count("idempotency");
        var next=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",2,"checkpoint","AFTER_BROKER_CONFIRM","reason","This new fault must wait until the checkpoint is released."));
        var clear=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",1,"reason","This clear must wait until the checkpoint is released."));
        assertThatThrownBy(()->controls.arm("scenario","site-a","blocked-arm",next)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->controls.clear("scenario","site-a",Database.uuid(armed,"faultId"),"blocked-clear",clear)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->controls.change("scenario","site-a","blocked-control",JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",2,"relayPaused",true,"reason","Do not write other controls during the frozen checkpoint.")))).isInstanceOf(Problem.class);
        new dev.cutover.platform.control.ProcessFaults(db.sql(),clock,code->{throw new AssertionError("A frozen fault fired");}).reached("AFTER_BUSINESS_COMMIT",event);
        assertThat(controls.arm("scenario","site-a","arm",arm)).isEqualTo(armed);
        assertThat(count("audit")).isEqualTo(audits);assertThat(count("idempotency")).isEqualTo(requests);
        assertThat(db.sql().fetchOne("SELECT remaining FROM process_faults").get(0,Integer.class)).isEqualTo(1);
        controls.change("scenario","site-a","resume",JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",2,"workersPaused",false,"reason","Resume application writes after all checkpoint dumps finish.")));
        assertThat(controls.clear("scenario","site-a",Database.uuid(armed,"faultId"),"blocked-clear",clear).path("state").asString()).isEqualTo("CLEARED");
    }

    @Test void processControlsAreVersionedAndCannotAffectAnotherSite() {
        var controls=new dev.cutover.platform.control.RuntimeControls(db.sql());
        var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",0,"relayPaused",true,"reason","Pause event publication to inspect durable intake."));
        var response=controls.change("scenario","site-a","pause",request);
        assertThat(controls.change("scenario","site-a","pause",request)).isEqualTo(response);
        assertThatThrownBy(()->controls.change("scenario","site-a","stale",request)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->controls.change("scenario","site-b","other-site",request)).isInstanceOf(Problem.class).satisfies(e->assertThat(((Problem)e).status()).isEqualTo(404));
        append(UUID.randomUUID(),1); assertThat(relay(DeliveryHooks.NONE).poll(16)).isZero();
        controls.change("scenario","site-a","resume",JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",1,"relayPaused",false,"reason","Resume the inspected publisher without changing data.")));
        assertThat(relay(DeliveryHooks.NONE).poll(16)).isEqualTo(1);
        assertThat(count("audit")).isEqualTo(2);
    }

    @Test void brokerFailureBacksOffAcrossDifferentAggregateStreams() {
        append(UUID.randomUUID(),1); append(UUID.randomUUID(),1);
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        var failing=new OutboxRelay(db.sql(),(exchange,type,id,body)->{attempts.incrementAndGet();throw DeliveryFailure.pending("BROKER_UNAVAILABLE");},clock,DeliveryHooks.NONE);
        failing.poll(16); for(int i=0;i<30;i++) failing.poll(16);
        assertThat(attempts.get()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(2)); failing.poll(16);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test void committedWorkSurvivesCrashBeforePublish() {
        UUID event = append(UUID.randomUUID(), 1);
        assertThatThrownBy(() -> relay((checkpoint, id) -> { if (checkpoint.equals("AFTER_BUSINESS_COMMIT")) throw new Crash(); }).poll(16)).isInstanceOf(Crash.class);
        assertThat(db.sql().fetchOne("SELECT published_at FROM outbox WHERE event_id=?", event).get(0)).isNull();
        clock.advance(Duration.ofSeconds(21));
        assertThat(relay(DeliveryHooks.NONE).poll(16)).isEqualTo(1);
        assertThat(rabbit.consume(queue, inbox, 16)).isEqualTo(1);
        assertThat(count("effects")).isEqualTo(1);
        assertThat(db.sql().fetchOne("SELECT unpublished_events FROM admission").get(0, Integer.class)).isZero();
    }

    @Test void confirmBeforeMarkCrashProducesDuplicateDeliveryWithOneEffect() {
        UUID event = append(UUID.randomUUID(), 1);
        assertThatThrownBy(() -> relay((checkpoint, id) -> { if (checkpoint.equals("AFTER_BROKER_CONFIRM")) throw new Crash(); }).poll(16)).isInstanceOf(Crash.class);
        clock.advance(Duration.ofSeconds(21));
        relay(DeliveryHooks.NONE).poll(16);
        assertThat(rabbit.consume(queue, inbox, 16)).isEqualTo(2);
        assertThat(count("effects")).isEqualTo(1);
        assertThat(db.sql().fetchOne("SELECT deliveries FROM inbox WHERE event_id=?", event).get(0, Integer.class)).isEqualTo(2);
    }

    @Test void effectCommitBeforeAcknowledgementCrashIsSafeOnRedelivery() {
        UUID event = append(UUID.randomUUID(), 1); relay(DeliveryHooks.NONE).poll(16);
        var crashing = new RabbitDelivery(template, (checkpoint, id) -> { if (checkpoint.equals("AFTER_EFFECT_BEFORE_ACK")) throw new Crash(); });
        assertThatThrownBy(() -> crashing.consume(queue, inbox, 1)).isInstanceOf(Crash.class);
        assertThat(state(event)).isEqualTo("APPLIED");
        assertThat(count("effects")).isEqualTo(1);
        assertThat(rabbit.consume(queue, inbox, 16)).isEqualTo(1);
        assertThat(count("effects")).isEqualTo(1);
    }

    @Test void partialEffectRollsBackWhileRetryPayloadCommits() {
        var broken = new AtomicBoolean(true);
        inbox = new DurableInbox(db.sql(), (sql, event) -> { EFFECT.apply(sql, event); if (broken.get()) throw DeliveryFailure.pending("DEPENDENCY_UNAVAILABLE"); }, clock, Set.of("producer"), Set.of("site-a"));
        var event = event(UUID.randomUUID(), 1, 7); deliver(event);
        assertThat(state(event.eventId())).isEqualTo("PENDING");
        assertThat(count("effects")).isZero();
        assertThat(count("stream_entry")).isZero();
        broken.set(false); clock.advance(Duration.ofSeconds(2)); inbox.retry(16);
        assertThat(state(event.eventId())).isEqualTo("APPLIED");
        assertThat(count("effects")).isEqualTo(1);
        assertThat(db.sql().fetchOne("SELECT active_messages FROM message_storage").get(0, Integer.class)).isZero();
    }

    @Test void gapsWaitAndConflictingVersionsCannotRewriteHistory() {
        UUID aggregate = UUID.randomUUID(); var second = event(aggregate, 2, 2); deliver(second);
        assertThat(state(second.eventId())).isEqualTo("PENDING"); assertThat(count("effects")).isZero();
        deliver(event(aggregate, 1, 1)); clock.advance(Duration.ofSeconds(2)); inbox.retry(16);
        assertThat(state(second.eventId())).isEqualTo("APPLIED"); assertThat(count("effects")).isEqualTo(2);
        var sameMeaning = event(aggregate, 2, 2); deliver(sameMeaning);
        assertThat(state(sameMeaning.eventId())).isEqualTo("APPLIED"); assertThat(count("effects")).isEqualTo(2);
        var contradictory = event(aggregate, 2, 999); deliver(contradictory);
        assertThat(state(contradictory.eventId())).isEqualTo("QUARANTINED");
        assertThat(db.sql().fetchOne("SELECT sum(value) FROM effects").get(0, Integer.class)).isEqualTo(3);
    }

    @Test void exhaustedRetriesRequireVersionedAuditedCorrection() {
        var broken = new AtomicBoolean(true);
        inbox = new DurableInbox(db.sql(), (sql, event) -> { if (broken.get()) throw DeliveryFailure.pending("DEPENDENCY_UNAVAILABLE"); EFFECT.apply(sql, event); }, clock, Set.of("producer"), Set.of("site-a"));
        var event = event(UUID.randomUUID(), 1, 5); deliver(event);
        for (int retry = 0; retry < 5; retry++) { clock.advance(Duration.ofSeconds(20)); inbox.retry(16); }
        assertThat(state(event.eventId())).isEqualTo("QUARANTINED");
        clock.advance(Duration.ofMinutes(5)); assertThat(inbox.retry(16)).isZero();
        var operations = new MessagingOperations(db.sql());
        long version = db.sql().fetchOne("SELECT version FROM inbox WHERE event_id=?", event.eventId()).get(0, Long.class);
        var request = JsonSupport.MAPPER.valueToTree(Map.of("reason", "Dependency repaired and verified", "expectedVersion", version));
        var result = operations.recover("supervisor-subject", "site-a", "inbox", event.eventId(), "retry-once", request);
        assertThat(operations.recover("supervisor-subject", "site-a", "inbox", event.eventId(), "retry-once", request)).isEqualTo(result);
        assertThatThrownBy(() -> operations.recover("supervisor-subject", "site-a", "inbox", event.eventId(), "another-key", request)).isInstanceOf(Problem.class);
        broken.set(false); inbox.retry(16);
        assertThat(state(event.eventId())).isEqualTo("APPLIED"); assertThat(count("audit")).isEqualTo(1); assertThat(count("effects")).isEqualTo(1);
    }

    @Test void malformedSourceSiteAndMajorVersionRemainInDurableQuarantine() {
        byte[] invalid = "{not-json".getBytes(StandardCharsets.UTF_8);
        UUID id = inbox.receive(EXCHANGE, "original-transport-id", invalid);
        inbox.receive(EXCHANGE, "original-transport-id", invalid);
        assertThat(db.sql().fetchOne("SELECT raw_body FROM delivery_quarantine WHERE delivery_id=?", id).get(0, byte[].class)).containsExactly(invalid);
        var event = event(UUID.randomUUID(), 1, 1);
        inbox.receive("cutover.impostor.v1", event.eventId().toString(), JsonSupport.write(event).getBytes(StandardCharsets.UTF_8));
        inbox.receive(EXCHANGE, event.eventId().toString(), JsonSupport.write(event).replace("site-a", "site-b").getBytes(StandardCharsets.UTF_8));
        inbox.receive(EXCHANGE, event.eventId().toString(), JsonSupport.write(event).replace("\"schemaVersion\":1", "\"schemaVersion\":2").getBytes(StandardCharsets.UTF_8));
        assertThat(count("delivery_quarantine")).isEqualTo(4); assertThat(count("effects")).isZero();
    }

    @Test void mandatoryReturnDoesNotMarkPublishedAndRoutingRepairUsesOriginalId() throws Exception {
        try (var channel = admin.createChannel()) { channel.queueUnbind(queue, EXCHANGE, "#"); }
        UUID event = append(UUID.randomUUID(), 1);
        assertThat(relay(DeliveryHooks.NONE).poll(16)).isZero();
        assertThat(db.sql().fetchOne("SELECT published_at,last_error FROM outbox WHERE event_id=?", event).get("published_at")).isNull();
        assertThat(db.sql().fetchOne("SELECT last_error FROM outbox WHERE event_id=?", event).get(0, String.class)).isEqualTo("MANDATORY_RETURN");
        try (var channel = admin.createChannel()) { channel.queueBind(queue, EXCHANGE, "#"); }
        clock.advance(Duration.ofSeconds(2)); assertThat(relay(DeliveryHooks.NONE).poll(16)).isEqualTo(1);
        rabbit.consume(queue, inbox, 16); assertThat(state(event)).isEqualTo("APPLIED");
    }

    @Test void leasesFenceLateMarksAndNoStreamOvertakesItsUnpublishedPredecessor() {
        UUID aggregate = UUID.randomUUID(); UUID first = append(aggregate, 1); append(aggregate, 2);
        var relay = relay(DeliveryHooks.NONE); var oldLease = relay.claim();
        assertThat(oldLease.eventId()).isEqualTo(first); assertThat(relay.claim()).isNull();
        clock.advance(Duration.ofSeconds(21)); var newLease = relay.claim();
        relay.confirmed(oldLease);
        assertThat(db.sql().fetchOne("SELECT unpublished_events FROM admission").get(0, Integer.class)).isEqualTo(2);
        relay.confirmed(newLease);
        assertThat(relay.claim().eventId()).isNotEqualTo(first);
    }

    @Test void inboxCapacityRetainsBrokerOwnershipAndDoesNotAcknowledgeUnpersistedWork() throws Exception {
        append(UUID.randomUUID(), 1); relay(DeliveryHooks.NONE).poll(16);
        db.sql().execute("UPDATE message_storage SET active_messages=10000");
        assertThatThrownBy(() -> rabbit.consume(queue, inbox, 16)).hasRootCauseInstanceOf(Problem.class);
        assertThat(count("inbox")).isZero(); assertThat(count("effects")).isZero();
        try (var channel = admin.createChannel()) {
            var retained = channel.basicGet(queue, false); assertThat(retained).isNotNull(); channel.basicNack(retained.getEnvelope().getDeliveryTag(), false, true);
        }
        db.sql().execute("UPDATE message_storage SET active_messages=0");
        assertThat(rabbit.consume(queue, inbox, 16)).isEqualTo(1); assertThat(count("effects")).isEqualTo(1);
    }

    @Test void brokerRetryBudgetPausesWithoutDroppingTheEvent() {
        UUID id = append(UUID.randomUUID(), 1);
        var unavailable = new OutboxRelay(db.sql(), (exchange, key, event, body) -> { throw DeliveryFailure.pending("BROKER_UNAVAILABLE"); }, clock, DeliveryHooks.NONE);
        for (int attempt = 0; attempt < RetryDelay.MAX_ATTEMPTS; attempt++) { unavailable.poll(1); clock.advance(Duration.ofSeconds(20)); }
        assertThat(db.sql().fetchOne("SELECT paused FROM outbox WHERE event_id=?", id).get(0, Boolean.class)).isTrue();
        assertThat(unavailable.claim()).isNull();
        assertThat(db.sql().fetchOne("SELECT unpublished_events FROM admission").get(0, Integer.class)).isEqualTo(1);
    }
    @Test void exhaustedQuorumCapacityIsRetriedAndEveryConfirmedMessageIsRetained() throws Exception {
        try (var channel = admin.createChannel()) {
            channel.queueDelete(queue);
            channel.queueDeclare(queue, true, false, false, Map.of("x-queue-type", "quorum", "x-max-length", 1, "x-overflow", "reject-publish"));
            channel.queueBind(queue, EXCHANGE, "#");
        }
        var accepted = new java.util.HashSet<UUID>(); UUID rejected = null;
        for (int attempt=0;attempt<24&&rejected==null;attempt++) {
            UUID id=append(UUID.randomUUID(),1);
            int result=relay(DeliveryHooks.NONE).poll(1);
            if(result==1) accepted.add(id); else rejected=id;
        }
        assertThat(rejected).as("bounded capacity rejection").isNotNull();
        assertThat(db.sql().fetchOne("SELECT last_error FROM outbox WHERE event_id=?",rejected).get(0,String.class)).isEqualTo("PUBLISH_NACK");
        rabbit.consume(queue,inbox,32);
        for(UUID id:accepted) assertThat(state(id)).isEqualTo("APPLIED");
        clock.advance(Duration.ofSeconds(2));relay(DeliveryHooks.NONE).poll(1);rabbit.consume(queue,inbox,32);
        assertThat(state(rejected)).isEqualTo("APPLIED");
        assertThat(count("effects")).isEqualTo(accepted.size()+1);
        assertThat(db.sql().fetchOne("SELECT unpublished_events FROM admission").get(0,Integer.class)).isZero();
    }
    @Test void pausedCheckpointPreventsDeliveryMutationAndStaleConfirmation() {
        UUID id=append(UUID.randomUUID(),1);var relay=relay(DeliveryHooks.NONE);var claimed=relay.claim();
        db.sql().execute("UPDATE service_control SET workers_paused=true");
        relay.confirmed(claimed);
        assertThat(db.sql().fetchOne("SELECT published_at FROM outbox WHERE event_id=?",id).get(0)).isNull();
        var event=event(UUID.randomUUID(),1,1);
        assertThatThrownBy(()->deliver(event)).isInstanceOf(Problem.class);
        assertThat(inbox.retry(16)).isZero();assertThat(count("inbox")).isZero();
        db.sql().execute("UPDATE service_control SET workers_paused=false");
        relay.confirmed(claimed);deliver(event);
        assertThat(state(event.eventId())).isEqualTo("APPLIED");
    }
    @Test void deliveryMigrationUpgradesThePreviousOwnerSchemaWithoutErasingBusinessRows() {
        var baseline=Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/platform","classpath:db/reliability").cleanDisabled(false).target("100").load();
        baseline.clean();baseline.migrate();
        UUID id=UUID.randomUUID();db.sql().execute("INSERT INTO effects(event_id,aggregate_id,version,value) VALUES (?,?,1,7)",id,id);
        var upgrade=Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/platform","classpath:db/reliability").load();
        assertThat(upgrade.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);
        assertThat(db.sql().fetchOne("SELECT value FROM effects WHERE event_id=?",id).get(0,Integer.class)).isEqualTo(7);
        assertThat(db.sql().fetchOne("SELECT active_messages FROM message_storage").get(0,Integer.class)).isZero();
        assertThat(db.sql().fetch("SELECT version FROM flyway_schema_history WHERE success").getValues(0,String.class)).contains("101","102");
        assertThat(db.sql().fetchOne("SELECT relay_paused OR consumer_paused FROM service_control").get(0,Boolean.class)).isFalse();
    }
}
