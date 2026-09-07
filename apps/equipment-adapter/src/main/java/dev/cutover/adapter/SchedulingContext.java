package dev.cutover.adapter;

import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Bounded observations under the route lock; command intake still revalidates authority and equipment. */
public final class SchedulingContext {
    private final DSLContext database;
    private final EquipmentObservations observations;
    public SchedulingContext(DSLContext database,EquipmentObservations observations){this.database=database;this.observations=observations;}
    public JsonNode read(String site,String zone,List<UUID> ids){
        if(ids==null || ids.size()>64 || ids.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(ids).size()!=ids.size())throw Problem.invalid("A unique inventory of at most 64 movement IDs is required.");
        return database.transactionResult(configuration->{
            var sql=DSL.using(configuration);
            var route=sql.fetchOne("SELECT * FROM zone_routes WHERE site_id=? AND zone_id=? FOR SHARE",site,zone);
            if(route==null)throw Problem.missing();
            var result=JsonSupport.MAPPER.createObjectNode();
            result.putObject("route").put("owner",route.get("owner",String.class)).put("epoch",route.get("epoch",Long.class)).put("state",route.get("state",String.class)).put("version",route.get("version",Long.class));
            result.set("equipment",observations.forSite(sql,site));var inventory=result.putArray("movements");
            for(var id:ids){
                var item=inventory.addObject().put("movementId",id.toString());
                var exists=sql.fetchOne("SELECT allocation_id FROM movement_allocations WHERE site_id=? AND zone_id=? AND movement_id=?",site,zone,id);
                if(exists==null){item.putNull("allocation");item.putNull("command");continue;}
                item.set("allocation",Allocations.view(sql,site,id));
                if(sql.fetchExists(DSL.table("command_journal"),DSL.field("site_id").eq(site).and(DSL.field("command_id").eq(id))))item.set("command",CommandJournal.view(sql,site,id));else item.putNull("command");
            }
            return result;
        });
    }
}
