package dev.cutover.execution;

import dev.cutover.platform.JsonSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class SchedulingDecisionTest {
    private static final String EARLY="00000000-0000-4000-8000-000000000001",LATE="80000000-0000-4000-8000-000000000001";
    ObjectNode candidate(String id,int priority){var item=JsonSupport.MAPPER.createObjectNode();item.put("movementId",id);item.put("priority",priority);item.put("eligibleAt","2026-09-08T00:00:00Z");item.put("zoneId","ambient");item.put("owner","legacy-core");item.put("epoch",0);item.put("allocationState","ASSIGNED");item.putNull("commandState");return item;}
    ObjectNode snapshot(){
        var input=JsonSupport.MAPPER.createObjectNode();input.put("ruleVersion",1);input.put("siteId","site-a");input.put("zoneId","ambient");input.put("decisionAt","2026-09-08T00:00:02Z");input.put("observedAt","2026-09-08T00:00:01Z");input.put("topologyVersion",1);input.put("worldMismatch",false);
        input.set("route",JsonSupport.MAPPER.valueToTree(Map.of("owner","legacy-core","epoch",0,"state","DRAINING")));
        input.set("lanes",JsonSupport.MAPPER.valueToTree(List.of(Map.of("siteId","site-a","zoneId","chilled","laneId","chilled-a","blocked",false),Map.of("siteId","site-a","zoneId","ambient","laneId","ambient-b","blocked",false),Map.of("siteId","site-a","zoneId","ambient","laneId","ambient-a","blocked",true))));
        input.putArray("candidates");return input;
    }
    @Test void unsignedUuidTieAndLaneOrderingAgreeWithTheDatabaseContract(){
        var input=snapshot();input.withArray("candidates").add(candidate(LATE,500)).add(candidate(EARLY,500));
        var before=input.deepCopy();var result=SchedulingDecision.propose(input);
        assertThat(result.path("selectedMovementId").asString()).isEqualTo(EARLY);
        assertThat(result.path("selectedLaneId").asString()).isEqualTo("ambient-b");
        assertThat(input).isEqualTo(before); // Pure proposal does not rewrite the persisted input.
    }
    @Test void anUnknownCommandNeverBecomesAnotherDispatchProposal(){
        var input=snapshot();var unknown=candidate(EARLY,900);unknown.put("commandState","OUTCOME_UNKNOWN");input.withArray("candidates").add(unknown).add(candidate(LATE,100));
        var result=SchedulingDecision.propose(input);
        assertThat(result.path("ranking").get(0).path("reason").asString()).isEqualTo("COMMAND_RECORDED");
        assertThat(result.path("selectedMovementId").asString()).isEqualTo(LATE);
        input.put("worldMismatch",true);assertThat(SchedulingDecision.propose(input).path("selectedMovementId").isNull()).isTrue();
        input.put("worldMismatch",false);((ObjectNode)input.path("candidates").get(1)).putNull("epoch");
        assertThat(SchedulingDecision.propose(input).path("selectedMovementId").isNull()).isTrue();
    }
    @Test void snapshotsRejectRepeatedIdentitiesAndExplainFutureObservations(){
        var input=snapshot();input.withArray("candidates").add(candidate(EARLY,500));input.put("observedAt","2026-09-08T00:00:03Z");
        assertThat(SchedulingDecision.propose(input).path("ranking").get(0).path("reason").asString()).isEqualTo("OBSERVATION_STALE");
        input.withArray("candidates").add(candidate(EARLY,100));assertThatThrownBy(()->SchedulingDecision.propose(input)).isInstanceOf(IllegalArgumentException.class);
    }
}
