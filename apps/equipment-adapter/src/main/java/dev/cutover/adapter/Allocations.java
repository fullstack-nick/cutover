package dev.cutover.adapter;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** The adapter database, not a broker subscription, assigns physical dispatch ownership. */
public final class Allocations {
    private final DSLContext database;
    public Allocations(DSLContext database) { this.database=database; }

    public JsonNode register(String site, String source, JsonNode movement) {
        try { Contracts.validate("movement.v1",JsonSupport.write(movement)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid(invalid.getMessage()); }
        if (!site.equals(movement.required("siteId").asString())) throw Problem.missing();
        String expected="returns".equals(movement.required("product").asString()) ? "returns-service" : "legacy-core";
        if (!expected.equals(source)) throw new Problem(403,"INTENT_SOURCE","This client cannot originate the movement.");
        UUID id=Database.uuid(movement,"movementId"); String zone=movement.required("zoneId").asString();
        if (("returns-service".equals(source))!=zone.equals("returns")) throw Problem.invalid("Product and zone are incompatible.");
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            Database.requireDurability(sql,false);
            var route=sql.fetchOne("SELECT * FROM zone_routes WHERE site_id= ? AND zone_id= ? FOR UPDATE",site,zone);
            if (route==null) throw Problem.missing();
            Database.lock(sql,"movement",site,id);
            var existing=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id= ? AND movement_id= ?",site,id);
            if (existing!=null) {
                if (!JsonSupport.hash(movement).equals(existing.get("payload_hash",String.class))) throw Problem.conflict("IMMUTABLE_MOVEMENT","An existing movement cannot change its payload.");
                return view(sql,site,id);
            }
            boolean active="ACTIVE".equals(route.get("state",String.class));
            UUID allocation=UUID.randomUUID();
            sql.execute("INSERT INTO movement_allocations(allocation_id,movement_id,site_id,zone_id,source,movement,payload_hash,owner,epoch,state,version) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?)",
                    allocation,id,site,zone,source,JsonSupport.write(movement),JsonSupport.hash(movement),
                    active?route.get("owner"):null,active?route.get("epoch"):null,active?"ASSIGNED":"PENDING",active?1:0);
            JsonNode result=view(sql,site,id);
            if (active) Events.append(sql,site,"equipment-adapter","movement",id,1,"MovementAssigned.v1",id,result);
            return result;
        });
    }

    public JsonNode get(String site,UUID movement) { return view(database,site,movement); }
    public JsonNode schedulingContext(String site,String zone,java.util.List<UUID> ids,EquipmentObservations observations){return new SchedulingContext(database,observations).read(site,zone,ids);}
    static JsonNode view(DSLContext sql,String site,UUID movement) {
        return Database.json(sql,"SELECT jsonb_build_object('allocationId',allocation_id,'movementId',movement_id,'siteId',site_id,'zoneId',zone_id,'owner',owner,'epoch',epoch,'state',state,'version',version,'movement',movement,'payloadHash',payload_hash,'createdAt',created_at) FROM movement_allocations WHERE site_id= ? AND movement_id= ?",site,movement);
    }
    static Record lockRoute(DSLContext sql,String site,UUID movement) {
        var allocation=sql.fetchOne("SELECT zone_id FROM movement_allocations WHERE site_id= ? AND movement_id= ?",site,movement);
        if (allocation==null) throw Problem.missing();
        return sql.fetchOne("SELECT * FROM zone_routes WHERE site_id= ? AND zone_id= ? FOR UPDATE",site,allocation.get("zone_id"));
    }
    public JsonNode routes(String site) {
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('siteId',site_id,'zoneId',zone_id,'owner',owner,'epoch',epoch,'state',state,'version',version) ORDER BY zone_id),'[]'::jsonb) FROM zone_routes WHERE site_id= ?",site);
    }
}
