package dev.cutover.adapter;

import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static dev.cutover.generated.equipment_adapter.Tables.EQUIPMENT_OBSERVATION;

public final class EquipmentObservations {
    private final DSLContext database;
    private final EquipmentPort equipment;
    private final Clock clock;
    public EquipmentObservations(DSLContext database,EquipmentPort equipment,Clock clock) { this.database=database;this.equipment=equipment;this.clock=clock; }
    public void refresh() {
        JsonNode observation=equipment.equipment();
        UUID world=Database.uuid(observation,"worldId"),generation=Database.uuid(observation,"journalGeneration");
        database.transaction(configuration -> {
            var sql=DSL.using(configuration);
            var row=sql.selectFrom(EQUIPMENT_OBSERVATION).where(EQUIPMENT_OBSERVATION.SINGLETON.isTrue()).forUpdate().fetchSingle();
            UUID pinned=row.get("pinned_world_id",UUID.class),journal=row.get("pinned_journal_generation",UUID.class);
            boolean mismatch=(pinned!=null && (!pinned.equals(world)||!journal.equals(generation))) || !observation.path("completeHistory").asBoolean(false);
            sql.execute("UPDATE equipment_observation SET pinned_world_id=COALESCE(pinned_world_id,?),pinned_journal_generation=COALESCE(pinned_journal_generation,?),observation= ?::jsonb,observed_at= ?::timestamptz,world_mismatch= ? WHERE singleton",
                    world,generation,JsonSupport.write(observation),OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),mismatch);
        });
    }
    public JsonNode forSite(String site) {
        var row=database.selectFrom(EQUIPMENT_OBSERVATION).where(EQUIPMENT_OBSERVATION.SINGLETON.isTrue()).fetchSingle();
        var result=JsonSupport.MAPPER.createObjectNode();
        if (row.get("observation")==null) { result.put("stale",true); result.putArray("lanes"); return result; }
        var observation=JsonSupport.read(row.get("observation").toString());
        result.setAll((ObjectNode)observation);
        var lanes=JsonSupport.MAPPER.createArrayNode();
        observation.path("lanes").forEach(lane->{if(site.equals(lane.path("siteId").asString())) lanes.add(lane);});
        result.set("lanes",lanes);
        result.put("stale",stale(row.get("observed_at",OffsetDateTime.class)));
        result.put("worldMismatch",row.get("world_mismatch",Boolean.class));
        result.put("observedAt",row.get("observed_at",OffsetDateTime.class).toInstant().toString());
        return result;
    }
    JsonNode current(DSLContext sql) {
        var row=sql.selectFrom(EQUIPMENT_OBSERVATION).where(EQUIPMENT_OBSERVATION.SINGLETON.isTrue()).forShare().fetchSingle();
        if (row.get("observation")==null || stale(row.get("observed_at",OffsetDateTime.class))) throw new Problem(503,"EQUIPMENT_STALE","Fresh equipment evidence is required before dispatch.");
        if (row.get("world_mismatch",Boolean.class)) throw Problem.conflict("WORLD_MISMATCH","The simulator world or history requires reconciliation.");
        return JsonSupport.read(row.get("observation").toString());
    }
    public String lane(String site,String zone) {
        JsonNode snapshot=current(database);
        for (JsonNode lane:snapshot.path("lanes"))
            if(site.equals(lane.path("siteId").asString()) && zone.equals(lane.path("zoneId").asString()) && !lane.path("blocked").asBoolean()) return lane.path("laneId").asString();
        throw Problem.conflict("LANE_BLOCKED","No compatible lane is currently available.");
    }
    private boolean stale(OffsetDateTime at) { return at==null || at.toInstant().plusSeconds(5).isBefore(clock.instant()); }
}
