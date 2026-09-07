package dev.cutover.execution;

import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Contracts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Pure proposal over a supplied immutable snapshot; no database, clock, broker or equipment access. */
public final class SchedulingDecision {
    public static final int RULE_VERSION=1;
    private SchedulingDecision() {}
    public static JsonNode propose(JsonNode snapshot) {
        Contracts.validate("scheduling-snapshot.v1",JsonSupport.write(snapshot));
        if(snapshot.path("ruleVersion").asInt()!=RULE_VERSION)throw new IllegalArgumentException("Unsupported scheduling rule version.");
        Instant decisionAt=Instant.parse(snapshot.required("decisionAt").asString());
        Instant observedAt=Instant.parse(snapshot.required("observedAt").asString());
        var candidates=new ArrayList<JsonNode>();snapshot.required("candidates").forEach(candidates::add);
        var identities=new HashSet<String>();
        for(var candidate:candidates)if(!identities.add(id(candidate)))throw new IllegalArgumentException("A scheduling snapshot cannot contain a repeated movement identity.");
        if(candidates.size()>64 || snapshot.required("lanes").size()>16)throw new IllegalArgumentException("Scheduling snapshot exceeds the bounded round size.");
        // Canonical UUID text has the same unsigned ordering as PostgreSQL uuid, unlike UUID.compareTo.
        candidates.sort(Comparator.<JsonNode>comparingInt(item->item.required("priority").asInt()).reversed()
                .thenComparing(item->Instant.parse(item.required("eligibleAt").asString()))
                .thenComparing(SchedulingDecision::id));
        var output=JsonSupport.MAPPER.createObjectNode();output.put("ruleVersion",RULE_VERSION);
        output.putNull("selectedMovementId");output.putNull("selectedLaneId");
        var ranking=output.putArray("ranking");
        for(JsonNode candidate:candidates) {
            String reason="READY",lane=null;
            JsonNode route=snapshot.required("route");
            if(snapshot.path("worldMismatch").asBoolean())reason="WORLD_MISMATCH";
            else if(observedAt.plusSeconds(5).isBefore(decisionAt) || observedAt.isAfter(decisionAt))reason="OBSERVATION_STALE";
            else if(!Set.of("ACTIVE","DRAINING").contains(route.path("state").asString()))reason="ROUTE_UNCERTAIN";
            else if(!candidate.path("zoneId").equals(snapshot.path("zoneId")))reason="ZONE_MISMATCH";
            else if(candidate.path("owner").isNull() || candidate.path("epoch").isNull() || !candidate.path("owner").equals(route.path("owner")) || candidate.path("epoch").asLong()!=route.path("epoch").asLong())reason="OWNER_MISMATCH";
            else if(!candidate.path("allocationState").asString().equals("ASSIGNED"))reason="UNASSIGNED";
            else if(!candidate.path("commandState").isNull() && !candidate.path("commandState").isMissingNode())reason="COMMAND_RECORDED";
            else if(Instant.parse(candidate.required("eligibleAt").asString()).isAfter(decisionAt))reason="NOT_ELIGIBLE";
            else {
                var lanes=new ArrayList<String>();
                for(JsonNode item:snapshot.required("lanes"))if(item.path("siteId").equals(snapshot.path("siteId"))
                        && item.path("zoneId").equals(candidate.path("zoneId")) && !item.path("blocked").asBoolean())lanes.add(item.required("laneId").asString());
                lane=lanes.stream().sorted().findFirst().orElse(null);
                if(lane==null)reason="LANE_BLOCKED";
            }
            var result=ranking.addObject();result.put("movementId",id(candidate));result.put("reason",reason);
            if(lane==null)result.putNull("laneId");else result.put("laneId",lane);
            if(reason.equals("READY") && output.path("selectedMovementId").isNull()){
                output.put("selectedMovementId",id(candidate));output.put("selectedLaneId",lane);
            }
        }
        return output;
    }
    private static String id(JsonNode candidate){return UUID.fromString(candidate.required("movementId").asString()).toString();}
}
