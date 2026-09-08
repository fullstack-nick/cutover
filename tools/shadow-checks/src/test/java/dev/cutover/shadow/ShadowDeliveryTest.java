package dev.cutover.shadow;

import dev.cutover.core.ShadowObservations;
import dev.cutover.core.ShadowRecovery;
import dev.cutover.execution.ShadowMessages;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.*;
import dev.cutover.testing.DatabaseFixture;
import dev.cutover.testing.MutableClock;
import dev.cutover.platform.Problem;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShadowDeliveryTest {
    @Test void exhaustedObservationResumesOriginalBytesWithAuditedIdempotentRecovery(){
        try(var core=new DatabaseFixture("legacy-core");var shadow=new DatabaseFixture("execution-service")){
            var clock=new MutableClock(Instant.now().plusSeconds(1));
            var reject=new java.util.concurrent.atomic.AtomicBoolean(true);
            var inbox=new DurableInbox(shadow.sql(),new ShadowMessages(),clock,new MessageSubscription("cutover.shadow-scheduler.inbox",Set.of("legacy-core"),Set.of("site-a"),Map.of("legacy-core","cutover.observation.v1")));
            var rounds=new ShadowObservations(core.sql(),clock,(exchange,type,id,body)->{
                if(reject.get())throw DeliveryFailure.pending("PUBLISH_NACK");
                inbox.receive(exchange,id.toString(),body.getBytes(StandardCharsets.UTF_8));
            });
            var result=rounds.capture(new IdenticalSnapshotTest().snapshot(new Random(840271),8),"SEEDED_TEST");
            for(int i=0;i<RetryDelay.MAX_ATTEMPTS;i++){rounds.relay();clock.advance(Duration.ofSeconds(40));}
            String original=core.sql().fetchOne("SELECT envelope FROM shadow_observation_outbox WHERE event_id=?",result.id()).get(0).toString();
            var recovery=new ShadowRecovery(core.sql(),clock);
            var status=recovery.status("site-a");assertThat(status.path("paused").asInt()).isEqualTo(1);
            assertThat(status.path("items").get(0).path("lastError").asString()).isEqualTo("PUBLISH_NACK");
            assertThat(recovery.status("site-b").path("items").size()).isZero();
            var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",0,"reason","The isolated observation queue is available again."));
            assertThatThrownBy(()->recovery.replay("reviewer","site-b",result.id(),"wrong-site",request)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status()).isEqualTo(404));
            var recorded=recovery.replay("reviewer","site-a",result.id(),"original-recovery",request);
            assertThat(recorded.path("version").asLong()).isEqualTo(1);
            assertThat(recovery.replay("reviewer","site-a",result.id(),"original-recovery",request)).isEqualTo(recorded);
            assertThatThrownBy(()->recovery.replay("other-reviewer","site-a",result.id(),"competing-recovery",request)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.code()).isEqualTo("VERSION_CONFLICT"));
            var changed=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",0,"reason","A different reason must not replace the recorded request."));
            assertThatThrownBy(()->recovery.replay("reviewer","site-a",result.id(),"original-recovery",changed)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
            assertThat(core.sql().fetchOne("SELECT envelope FROM shadow_observation_outbox WHERE event_id=?",result.id()).get(0).toString()).isEqualTo(original);
            assertThat(core.sql().fetchOne("SELECT pending_count FROM shadow_observation_capacity").get(0,Integer.class)).isEqualTo(1);
            reject.set(false);assertThat(rounds.relay()).isEqualTo(1);
            assertThat(core.sql().fetchOne("SELECT pending_count FROM shadow_observation_capacity").get(0,Integer.class)).isZero();
            assertThat(core.sql().fetchOne("SELECT unpublished_events FROM admission").get(0,Integer.class)).isZero();
            assertThat(shadow.sql().fetchOne("SELECT count(*),bool_and(matches) FROM shadow_comparisons").intoArray()).containsExactly(1L,true);
            assertThat(shadow.sql().fetchOne("SELECT count(*) FROM execution_tasks").get(0,Integer.class)).isZero();
            assertThat(core.sql().fetchOne("SELECT count(*),min(actor),min(reason),min(before_version),max(after_version) FROM audit WHERE action='recover-shadow-observation'").intoArray()).containsExactly(1L,"reviewer",request.path("reason").asString(),0L,1L);
            assertThat(recovery.replay("reviewer","site-a",result.id(),"original-recovery",request)).isEqualTo(recorded);
            var latest=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",1,"reason","A delivered observation must not be reopened automatically."));
            assertThatThrownBy(()->recovery.replay("reviewer","site-a",result.id(),"already-delivered",latest)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.code()).isEqualTo("DELIVERY_STATE"));
        }
    }

    @Test void observationRecoveryHonoursFreezeAndDoesNotReopenAnActiveLease(){
        try(var core=new DatabaseFixture("legacy-core")){
            var clock=new MutableClock(Instant.now().plusSeconds(1));
            var rounds=new ShadowObservations(core.sql(),clock,(exchange,type,id,body)->{throw DeliveryFailure.pending("PUBLISH_NACK");});
            var result=rounds.capture(new IdenticalSnapshotTest().snapshot(new Random(840271),8),"SEEDED_TEST");
            var recovery=new ShadowRecovery(core.sql(),clock);
            var request=JsonSupport.MAPPER.valueToTree(Map.of("expectedVersion",0,"reason","The original observation transport is now available."));
            assertThatThrownBy(()->recovery.replay("reviewer","site-a",result.id(),"not-exhausted",request)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.code()).isEqualTo("DELIVERY_STATE"));
            for(int i=0;i<RetryDelay.MAX_ATTEMPTS;i++){rounds.relay();clock.advance(Duration.ofSeconds(40));}
            core.sql().execute("UPDATE service_control SET workers_paused=true WHERE singleton");
            assertThatThrownBy(()->recovery.replay("reviewer","site-a",result.id(),"frozen",request)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.code()).isEqualTo("WORKERS_PAUSED"));
            core.sql().execute("UPDATE service_control SET workers_paused=false WHERE singleton");
            core.sql().execute("UPDATE shadow_observation_outbox SET lease_until=?::timestamptz WHERE event_id=?",clock.instant().plusSeconds(60).toString(),result.id());
            assertThatThrownBy(()->recovery.replay("reviewer","site-a",result.id(),"leased",request)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.code()).isEqualTo("DELIVERY_IN_FLIGHT"));
            assertThat(core.sql().fetchOne("SELECT recovery_version,paused FROM shadow_observation_outbox WHERE event_id=?",result.id()).intoArray()).containsExactly(0L,true);
            assertThat(core.sql().fetchOne("SELECT count(*) FROM audit WHERE action='recover-shadow-observation'").get(0,Integer.class)).isZero();
        }
    }
    @Test void observationUsesAuthenticatedDedicatedExchangeAndOneDurableComparison(){
        try(var core=new DatabaseFixture("legacy-core");var shadow=new DatabaseFixture("execution-service")){
            var failed=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
            var inbox=new DurableInbox(shadow.sql(),(sql,event)->{try{new ShadowMessages().apply(sql,event);}catch(RuntimeException failure){failed.set(failure);throw failure;}},Clock.systemUTC(),new MessageSubscription("cutover.shadow-scheduler.inbox",Set.of("legacy-core"),Set.of("site-a"),Map.of("legacy-core","cutover.observation.v1")));
            var rounds=new ShadowObservations(core.sql(),Clock.systemUTC(),(exchange,type,id,body)->inbox.receive(exchange,id.toString(),body.getBytes(StandardCharsets.UTF_8)));
            var result=rounds.capture(new IdenticalSnapshotTest().snapshot(new Random(840271),8),"SEEDED_TEST");
            assertThat(result.retained()).isTrue();assertThat(rounds.relay()).isEqualTo(1);
            assertThat(failed.get()).isNull();
            String body=core.sql().fetchOne("SELECT envelope FROM shadow_observation_outbox").get(0).toString();
            var event=JsonSupport.MAPPER.readValue(body,Events.Envelope.class);
            assertThat(shadow.sql().fetchOne("SELECT state,last_error FROM inbox WHERE event_id=?",event.eventId()).intoArray()).containsExactly("APPLIED",null);
            inbox.receive("cutover.observation.v1",event.eventId().toString(),body.getBytes(StandardCharsets.UTF_8));
            assertThat(shadow.sql().fetchOne("SELECT count(*),bool_and(matches) FROM shadow_comparisons").intoArray()).containsExactly(1L,true);
            assertThat(shadow.sql().fetchOne("SELECT count(*) FROM execution_tasks").get(0,Integer.class)).isZero();
            assertThat(core.sql().fetchOne("SELECT pending_count FROM shadow_observation_capacity").get(0,Integer.class)).isZero();
            assertThat(core.sql().fetchOne("SELECT unpublished_events FROM admission").get(0,Integer.class)).isZero();
            inbox.receive("cutover.legacy-core.v1",event.eventId().toString(),body.getBytes(StandardCharsets.UTF_8));
            assertThat(shadow.sql().fetchOne("SELECT reason,site_id FROM delivery_quarantine").intoArray()).containsExactly("UNTRUSTED_EVENT_SOURCE",null);
            assertThat(shadow.sql().fetchOne("SELECT count(*) FROM shadow_comparisons").get(0,Integer.class)).isEqualTo(1);
        }
    }
    @Test void observationBudgetOmissionDoesNotConsumeTheBusinessOutbox(){
        try(var core=new DatabaseFixture("legacy-core")){
            var rounds=new ShadowObservations(core.sql(),Clock.systemUTC(),null);
            // A focused quota boundary test; the process capacity scenario fills the real queue.
            core.sql().execute("UPDATE shadow_observation_capacity SET pending_count=1000");
            var result=rounds.capture(new IdenticalSnapshotTest().snapshot(new Random(840271),9),"SEEDED_TEST");
            assertThat(result.retained()).isFalse();assertThat(result.proposal().path("selectedMovementId").isNull()).isFalse();
            assertThat(core.sql().fetchOne("SELECT count(*) FROM legacy_decision_rounds").get(0,Integer.class)).isZero();
            assertThat(core.sql().fetchOne("SELECT omitted_rounds FROM shadow_observation_capacity").get(0,Long.class)).isEqualTo(1);
            assertThat(core.sql().fetchOne("SELECT unpublished_events FROM admission").get(0,Integer.class)).isZero();
        }
    }
    @Test void excessTimestampPrecisionIsRejectedBeforeSqlCanRoundIt(){
        var snapshot=new IdenticalSnapshotTest().snapshot(new Random(840271),8);
        snapshot.put("observedAt","2026-09-08T00:00:00.123456789Z");
        assertThatThrownBy(()->dev.cutover.execution.SchedulingDecision.propose(snapshot)).isInstanceOf(IllegalArgumentException.class);
    }
}
