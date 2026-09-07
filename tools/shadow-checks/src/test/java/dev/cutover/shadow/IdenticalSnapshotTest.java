package dev.cutover.shadow;

import dev.cutover.core.LegacyDecision;
import dev.cutover.execution.SchedulingDecision;
import dev.cutover.platform.JsonSupport;
import dev.cutover.testing.DatabaseFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Independent implementations consume persisted identical inputs; export every comparison before teardown. */
class IdenticalSnapshotTest {
    private static final long SEED=840271L;
    @Test void oneThousandPersistedRoundsMatchTheActualLegacySql() throws Exception {
        Path directory=Path.of("target","shadow-evidence");Files.createDirectories(directory);
        var reasons=new java.util.TreeMap<String,Integer>();int mismatches=0;
        try(var database=new DatabaseFixture("legacy-core");var evidence=Files.newBufferedWriter(directory.resolve("comparisons.jsonl"))) {
            var random=new Random(SEED);
            for(int round=0;round<1000;round++) {
                ObjectNode snapshot=snapshot(random,round);UUID id=new UUID(SEED,round+1);String inputHash=JsonSupport.hash(snapshot);
                JsonNode legacy=LegacyDecision.propose(database.sql(),snapshot);
                database.sql().execute("INSERT INTO legacy_decision_rounds(round_id,site_id,zone_id,input_hash,input,proposal,rule_version,decision_at) VALUES (?,'site-a',?,?,?::jsonb,?::jsonb,1,?::timestamptz)",id,snapshot.path("zoneId").asString(),inputHash,JsonSupport.write(snapshot),JsonSupport.write(legacy),snapshot.path("decisionAt").asString());
                JsonNode retained=JsonSupport.read(database.sql().fetchOne("SELECT input FROM legacy_decision_rounds WHERE round_id=?",id).get(0).toString());
                assertThat(JsonSupport.hash(retained)).isEqualTo(inputHash);
                JsonNode execution=SchedulingDecision.propose(retained);boolean matches=legacy.equals(execution);
                if(!matches)mismatches++;
                execution.path("ranking").forEach(item->reasons.merge(item.path("reason").asString(),1,Integer::sum));
                var record=new LinkedHashMap<String,Object>();record.put("roundId",id);record.put("inputHash",inputHash);record.put("input",retained);record.put("legacy",legacy);record.put("execution",execution);record.put("matches",matches);
                evidence.write(JsonSupport.write(record));evidence.newLine();
            }
            assertThat(database.sql().fetchOne("SELECT count(*) FROM legacy_decision_rounds").get(0,Integer.class)).isEqualTo(1000);
        }
        Files.writeString(directory.resolve("manifest.json"),JsonSupport.write(Map.of("seed",SEED,"rounds",1000,"mismatches",mismatches,"reasons",reasons,"scope","component: actual PostgreSQL routine and pure Java; runtime shadow identity is a separate gate")));
        assertThat(reasons.keySet()).contains("READY","LANE_BLOCKED","COMMAND_RECORDED","OWNER_MISMATCH","NOT_ELIGIBLE","OBSERVATION_STALE","WORLD_MISMATCH","UNASSIGNED","ZONE_MISMATCH","ROUTE_UNCERTAIN");
        assertThat(mismatches).as("Inspect retained comparisons.jsonl for each differing proposal").isZero();
    }
    ObjectNode snapshot(Random random,int round) {
        String zone=round%2==0?"ambient":"chilled";
        Instant at=Instant.parse("2026-09-08T00:00:00Z").plusMillis(round*17L);
        var result=JsonSupport.MAPPER.createObjectNode();result.put("ruleVersion",1);result.put("siteId","site-a");result.put("zoneId",zone);result.put("decisionAt",at.toString());result.put("observedAt",at.minusMillis(round%23==0?6000:round%29==0?-1:300).toString());result.put("topologyVersion",round/10);result.put("worldMismatch",round%31==0);
        result.set("route",JsonSupport.MAPPER.valueToTree(Map.of("owner","legacy-core","epoch",3,"state",round%37==0?"RECONCILIATION_REQUIRED":round%3==0?"DRAINING":"ACTIVE")));
        var candidates=new ArrayList<ObjectNode>();
        for(int item=0;item<1+round%16;item++) {
            var candidate=JsonSupport.MAPPER.createObjectNode();candidate.put("movementId",new UUID(random.nextLong(),random.nextLong()).toString());candidate.put("priority",round%7==0?500:random.nextInt(10)*100);candidate.put("eligibleAt",at.plusMillis(round%7==0?-1000:random.nextInt(2001)-1700).toString());candidate.put("zoneId",round%19==0&&item==0?(zone.equals("ambient")?"chilled":"ambient"):zone);candidate.put("owner",round%17==0&&item==0?"execution-service":"legacy-core");candidate.put("epoch",round%13==0&&item==1?2:3);candidate.put("allocationState",round%11==0&&item==0?"PENDING":"ASSIGNED");
            if(round%5==0&&item==0)candidate.put("commandState","OUTCOME_UNKNOWN");else candidate.putNull("commandState");
            candidates.add(candidate);
        }
        Collections.shuffle(candidates,random);var array=result.putArray("candidates");candidates.forEach(array::add);
        var lanes=result.putArray("lanes");
        for(String temperature:new String[]{"chilled","ambient"})for(String suffix:new String[]{"b","a"})lanes.add(JsonSupport.MAPPER.valueToTree(Map.of("siteId","site-a","zoneId",temperature,"laneId",temperature+"-"+suffix,"blocked",round%4==0||suffix.equals("a"))));
        return result;
    }
}
