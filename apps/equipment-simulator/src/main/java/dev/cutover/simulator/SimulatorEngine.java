package dev.cutover.simulator;

import dev.cutover.platform.Contracts;
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

public final class SimulatorEngine {
    private final DSLContext database;
    private final Clock clock;
    public SimulatorEngine(DSLContext database, Clock clock) { this.database = database; this.clock = clock; }
    public record Command(UUID commandId, UUID allocationId, UUID movementId, String siteId, UUID loadId,
                          UUID worldId, UUID journalGeneration, long expectedLoadVersion, String source,
                          String destination, String zoneId, String laneId, int quantity) {}
    public record Acceptance(JsonNode result, int responseDelayMillis) {}
    public static final class DisconnectBeforeAcceptance extends RuntimeException {}

    public Acceptance accept(UUID id, JsonNode body) {
        try { Contracts.validate("equipment-command.v1", JsonSupport.write(body)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid(invalid.getMessage()); }
        Command command = JsonSupport.MAPPER.treeToValue(body, Command.class);
        if (!id.equals(command.commandId())) throw Problem.conflict("COMMAND_ID_MISMATCH", "Path and command identities differ.");
        String hash = JsonSupport.hash(command);
        var fault = database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (sql.fetchExists(DSL.table("simulator_commands"), DSL.field("command_id").eq(id))) return null;
            var selected = sql.fetchOne("SELECT * FROM simulation_faults WHERE remaining>0 AND (command_selector IS NULL OR command_selector= ?) ORDER BY created_at,fault_id LIMIT 1 FOR UPDATE SKIP LOCKED", id);
            if (selected != null) sql.execute("UPDATE simulation_faults SET remaining=remaining-1 WHERE fault_id= ?", selected.get("fault_id"));
            return selected;
        });
        if (fault != null && "BEFORE_ACCEPT".equals(fault.get("kind"))) throw new DisconnectBeforeAcceptance();
        int responseDelay = fault != null && "LOST_RESPONSE".equals(fault.get("kind")) ? fault.get("delay_ms", Integer.class) : 0;
        database.transaction(configuration -> {
            var sql = DSL.using(configuration);
            Database.requireDurability(sql, false);
            Database.lock(sql, "command", id);
            var existing = sql.fetchOne("SELECT payload_hash FROM simulator_commands WHERE command_id= ?", id);
            if (existing != null) {
                if (!hash.equals(existing.get("payload_hash", String.class))) throw Problem.conflict("IMMUTABLE_COMMAND", "A command identity cannot change its physical payload.");
                return;
            }
            var world = sql.fetchOne("SELECT * FROM simulation_world WHERE singleton FOR SHARE");
            if (!command.worldId().equals(world.get("world_id", UUID.class))
                    || !command.journalGeneration().equals(world.get("journal_generation", UUID.class))
                    || !world.get("complete_history", Boolean.class))
                throw Problem.conflict("WORLD_MISMATCH", "World identity or retained history cannot establish this command's safety.");
            var lane = sql.fetchOne("SELECT * FROM lanes WHERE site_id= ? AND lane_id= ? FOR SHARE", command.siteId(), command.laneId());
            if (lane == null || !command.zoneId().equals(lane.get("zone_id", String.class))) throw Problem.invalid("The selected lane is incompatible with this zone.");
            Database.lock(sql, "load", command.siteId(), command.loadId());
            sql.execute("INSERT INTO loads(site_id,load_id,position) VALUES (?,?,?) ON CONFLICT DO NOTHING", command.siteId(), command.loadId(), command.source());
            var load = sql.fetchOne("SELECT * FROM loads WHERE site_id= ? AND load_id= ? FOR UPDATE", command.siteId(), command.loadId());
            if (!command.source().equals(load.get("position", String.class)) || command.expectedLoadVersion()!=load.get("version", Long.class))
                throw Problem.conflict("LOAD_PRECONDITION", "The load's recorded position or version differs.");
            var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            sql.execute("INSERT INTO simulator_commands(command_id,site_id,movement_id,load_id,lane_id,world_id,payload,payload_hash,state,accepted_at,execute_after,response_delay_ms) VALUES (?,?,?,?,?,?,?::jsonb,?,'ACCEPTED',?::timestamptz,?::timestamptz,?)",
                    id, command.siteId(), command.movementId(), command.loadId(), command.laneId(), command.worldId(), JsonSupport.write(command), hash, now, now.plusNanos(responseDelay>0 ? 0 : 300_000_000), responseDelay);
        });
        if (responseDelay > 0) advance();
        return new Acceptance(status(id), responseDelay);
    }

    public int advance() {
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (sql.fetchOne("SELECT workers_paused OR critical_storage FROM service_control WHERE singleton").get(0,Boolean.class)) return 0;
            var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            var ready = sql.fetch("SELECT c.command_id,c.payload FROM simulator_commands c JOIN lanes l ON l.site_id=c.site_id AND l.lane_id=c.lane_id WHERE c.state IN ('ACCEPTED','EXECUTING') AND c.execute_after<= ?::timestamptz AND NOT l.blocked ORDER BY c.execute_after,c.command_id LIMIT 32 FOR UPDATE OF c SKIP LOCKED", now);
            int completed = 0;
            for (var row : ready) {
                Command command = JsonSupport.MAPPER.readValue(row.get("payload").toString(), Command.class);
                var load = sql.fetchOne("SELECT * FROM loads WHERE site_id= ? AND load_id= ? FOR UPDATE", command.siteId(), command.loadId());
                if (!command.source().equals(load.get("position", String.class)) || command.expectedLoadVersion()!=load.get("version", Long.class)) {
                    sql.execute("UPDATE simulator_commands SET state='REJECTED_BEFORE_EXECUTION',version=version+1 WHERE command_id= ?", command.commandId());
                    continue;
                }
                sql.execute("UPDATE loads SET position= ?,version=version+1 WHERE site_id= ? AND load_id= ?", command.destination(), command.siteId(), command.loadId());
                sql.execute("INSERT INTO execution_ledger(command_id,site_id,movement_id,load_id,source,destination,quantity,before_version,after_version,completed_at) VALUES (?,?,?,?,?,?,?,?,?,?::timestamptz)",
                        command.commandId(), command.siteId(), command.movementId(), command.loadId(), command.source(), command.destination(), command.quantity(), command.expectedLoadVersion(), command.expectedLoadVersion()+1, now);
                sql.execute("UPDATE simulator_commands SET state='COMPLETED',version=version+1,completed_at= ?::timestamptz WHERE command_id= ?", now, command.commandId());
                completed++;
            }
            return completed;
        });
    }

    public JsonNode status(UUID commandId) {
        return Database.json(database, "SELECT jsonb_build_object('commandId',c.command_id,'movementId',c.movement_id,'siteId',c.site_id,'worldId',w.world_id,'journalGeneration',w.journal_generation,'completeHistory',w.complete_history,'state',c.state,'version',c.version,'acceptedAt',c.accepted_at,'completedAt',c.completed_at,'executionSequence',(SELECT sequence FROM execution_ledger e WHERE e.command_id=c.command_id)) FROM simulator_commands c CROSS JOIN simulation_world w WHERE c.command_id= ?", commandId);
    }
    public JsonNode equipment() {
        return Database.json(database, "SELECT jsonb_build_object('worldId',world_id,'journalGeneration',journal_generation,'completeHistory',complete_history,'observedAt',now(),'journalHighWater',(SELECT COALESCE(max(sequence),0) FROM execution_ledger),'lanes',(SELECT jsonb_agg(jsonb_build_object('siteId',site_id,'laneId',lane_id,'zoneId',zone_id,'blocked',blocked,'version',version) ORDER BY site_id,lane_id) FROM lanes)) FROM simulation_world WHERE singleton");
    }
    public void blockLane(String site, String lane, boolean blocked) {
        if (database.execute("UPDATE lanes SET blocked= ?,version=version+1 WHERE site_id= ? AND lane_id= ?", blocked, site, lane) != 1) throw Problem.missing();
    }
    public void fault(String kind, UUID selector, int count, int delay) {
        if (!java.util.Set.of("BEFORE_ACCEPT", "LOST_RESPONSE").contains(kind) || count<1 || count>100 || delay<0 || delay>10000)
            throw Problem.invalid("Unsupported or unbounded deterministic fault.");
        database.execute("INSERT INTO simulation_faults(kind,command_selector,remaining,delay_ms) VALUES (?,?,?,?)", kind, selector, count, delay);
    }
    public JsonNode history(long after, int limit) {
        if (after<0 || limit<1 || limit>100) throw Problem.invalid("Invalid history cursor or limit.");
        return Database.json(database, "SELECT COALESCE(jsonb_agg(to_jsonb(e)), '[]'::jsonb) FROM (SELECT * FROM execution_ledger WHERE sequence> ? ORDER BY sequence LIMIT ?) e", after, limit);
    }
}
