package dev.cutover.adapter;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.OperationTrace;
import java.util.UUID;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** The adapter database, not a broker subscription, assigns physical dispatch ownership. */
public final class Allocations {
    private final DSLContext database;
    private final Clock clock;
    public Allocations(DSLContext database) { this(database,Clock.systemUTC()); }
    public Allocations(DSLContext database,Clock clock) { this.database=database;this.clock=clock; }

    public JsonNode register(String site, String source, JsonNode movement) {
        return OperationTrace.call("cutover.movement.allocate",site,null,()->registerTraced(site,source,movement));
    }
    private JsonNode registerTraced(String site, String source, JsonNode movement) {
        try { Contracts.validate("movement.v1",JsonSupport.write(movement)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid(invalid.getMessage()); }
        if (!site.equals(movement.required("siteId").asString())) throw Problem.missing();
        String expected="returns".equals(movement.required("product").asString()) ? "returns-service" : "legacy-core";
        if (!expected.equals(source)) throw new Problem(403,"INTENT_SOURCE","This client cannot originate the movement.");
        UUID id=Database.uuid(movement,"movementId"); String zone=movement.required("zoneId").asString();
        OperationTrace.annotate("cutover.movement_id",id.toString());
        if (("returns-service".equals(source))!=zone.equals("returns")) throw Problem.invalid("Product and zone are incompatible.");
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            Database.requireDurability(sql,false);
            // Fence route mutations through commit without upgrading the inbox's shared route locks.
            var route=sql.fetchOne("SELECT * FROM zone_routes WHERE site_id= ? AND zone_id= ? FOR SHARE",site,zone);
            if (route==null) throw Problem.missing();
            Database.lock(sql,"movement",site,id);
            var existing=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id= ? AND movement_id= ?",site,id);
            if (existing!=null) {
                if (!JsonSupport.hash(movement).equals(existing.get("payload_hash",String.class))) throw Problem.conflict("IMMUTABLE_MOVEMENT","An existing movement cannot change its payload.");
                return view(sql,site,id);
            }
            boolean active="ACTIVE".equals(route.get("state",String.class));
            UUID allocation=UUID.randomUUID();
            sql.execute("INSERT INTO movement_allocations(allocation_id,movement_id,site_id,zone_id,source,movement,payload_hash,owner,epoch,state,version,assigned_at) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,CASE WHEN ? THEN ?::timestamptz ELSE NULL END)",
                    allocation,id,site,zone,source,JsonSupport.write(movement),JsonSupport.hash(movement),
                    active?route.get("owner"):null,active?route.get("epoch"):null,active?"ASSIGNED":"PENDING",active?1:0,active,now());
            JsonNode result=view(sql,site,id);
            if (active) Events.append(sql,site,"equipment-adapter","movement",id,1,"MovementAssigned.v1",id,result);
            return result;
        });
    }

    public JsonNode get(String site,UUID movement) { return view(database,site,movement); }
    /** A route becoming active releases only still-unassigned intents. Restarts cannot change old assignments. */
    public int releasePending() {
        if (!database.fetchOne("SELECT EXISTS(SELECT 1 FROM movement_allocations a JOIN zone_routes r USING(site_id,zone_id) WHERE a.state='PENDING' AND r.state='ACTIVE')").get(0,Boolean.class)) return 0;
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            Database.requireDurability(sql,false);
            var routes=sql.fetch("SELECT site_id,zone_id,owner,epoch FROM zone_routes r WHERE state='ACTIVE' AND EXISTS(SELECT 1 FROM movement_allocations a WHERE a.site_id=r.site_id AND a.zone_id=r.zone_id AND a.state='PENDING') ORDER BY site_id,zone_id LIMIT 4 FOR UPDATE SKIP LOCKED");
            int assigned=0;
            for(var route:routes) {
                var pending=sql.fetch("SELECT * FROM movement_allocations WHERE site_id=? AND zone_id=? AND state='PENDING' ORDER BY created_at,movement_id LIMIT ? FOR UPDATE",route.get("site_id"),route.get("zone_id"),16-assigned);
                for(var item:pending) {
                    sql.execute("UPDATE movement_allocations SET owner=?,epoch=?,state='ASSIGNED',version=1,assigned_at=?::timestamptz WHERE allocation_id=? AND state='PENDING'",route.get("owner"),route.get("epoch"),now(),item.get("allocation_id"));
                    String site=item.get("site_id",String.class);UUID id=item.get("movement_id",UUID.class);
                    try (var trace=OperationTrace.movement(sql,"equipment-adapter",site,id,"cutover.movement.release")) {
                        Events.append(sql,site,"equipment-adapter","movement",id,1,"MovementAssigned.v1",id,view(sql,site,id));
                    }
                    assigned++;
                }
                if(assigned==16)break;
            }
            return assigned;
        });
    }
    public JsonNode registerBaseline(String site,String client,JsonNode body){return new BaselineRegistrations(database).register(site,client,body);}
    public JsonNode schedulingContext(String site,String zone,java.util.List<UUID> ids,EquipmentObservations observations){return new SchedulingContext(database,observations).read(site,zone,ids);}
    static JsonNode view(DSLContext sql,String site,UUID movement) {
        return Database.json(sql,"SELECT jsonb_build_object('allocationId',allocation_id,'movementId',movement_id,'siteId',site_id,'zoneId',zone_id,'owner',owner,'epoch',epoch,'state',state,'version',version,'movement',movement,'payloadHash',payload_hash,'createdAt',created_at,'assignedAt',assigned_at) FROM movement_allocations WHERE site_id= ? AND movement_id= ?",site,movement);
    }
    static Record lockRoute(DSLContext sql,String site,UUID movement) {
        var allocation=sql.fetchOne("SELECT zone_id FROM movement_allocations WHERE site_id= ? AND movement_id= ?",site,movement);
        if (allocation==null) throw Problem.missing();
        // Command transactions read authority; distinct allocations may progress together.
        // SHARE still fences every owner/epoch/state update until this transaction commits.
        return sql.fetchOne("SELECT * FROM zone_routes WHERE site_id= ? AND zone_id= ? FOR SHARE",site,allocation.get("zone_id"));
    }
    public JsonNode routes(String site) {
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('siteId',site_id,'zoneId',zone_id,'owner',owner,'epoch',epoch,'state',state,'version',version) ORDER BY zone_id),'[]'::jsonb) FROM zone_routes WHERE site_id= ?",site);
    }
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
