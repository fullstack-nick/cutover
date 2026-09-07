package dev.cutover.shadow;

import dev.cutover.core.ShadowObservations;
import dev.cutover.execution.ShadowMessages;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.*;
import dev.cutover.testing.DatabaseFixture;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShadowDeliveryTest {
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
