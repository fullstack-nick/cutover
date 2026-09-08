package dev.cutover.simulator;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import static dev.cutover.generated.equipment_simulator.Tables.SIMULATOR_COMMANDS;

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
            if (sql.fetchExists(SIMULATOR_COMMANDS, SIMULATOR_COMMANDS.COMMAND_ID.eq(id))) return null;
            return consumeFault(sql,id,Set.of("BEFORE_ACCEPT","LOST_RESPONSE","DELAY_RESPONSE","REJECT_PRECONDITION"));
        });
        if (fault != null && "BEFORE_ACCEPT".equals(fault.get("kind"))) throw new DisconnectBeforeAcceptance();
        if (fault != null && "REJECT_PRECONDITION".equals(fault.get("kind"))) throw Problem.conflict("LOAD_PRECONDITION","A deterministic precondition fault rejected this command before acceptance.");
        boolean lost = fault!=null && "LOST_RESPONSE".equals(fault.get("kind"));
        int responseDelay = fault != null && Set.of("LOST_RESPONSE","DELAY_RESPONSE").contains(fault.get("kind",String.class)) ? fault.get("delay_ms", Integer.class) : 0;
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
                    id, command.siteId(), command.movementId(), command.loadId(), command.laneId(), command.worldId(), JsonSupport.write(command), hash, now, now.plusNanos(lost ? 0 : 300_000_000), responseDelay);
        });
        if (lost) advance();
        return new Acceptance(status(id), responseDelay);
    }

    public int advance() {
        var due = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!database.fetchOne("SELECT EXISTS(SELECT 1 FROM simulator_commands c JOIN lanes l ON l.site_id=c.site_id AND l.lane_id=c.lane_id WHERE c.state IN ('ACCEPTED','EXECUTING') AND c.execute_after<=?::timestamptz AND NOT l.blocked)", due).get(0,Boolean.class)) return 0;
        return database.transactionResult(configuration -> {
            var sql = DSL.using(configuration);
            if (sql.fetchOne("SELECT workers_paused OR critical_storage FROM service_control WHERE singleton FOR SHARE").get(0,Boolean.class)) return 0;
            var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            var ready = sql.fetch("SELECT c.command_id,c.payload,c.state FROM simulator_commands c JOIN lanes l ON l.site_id=c.site_id AND l.lane_id=c.lane_id WHERE c.state IN ('ACCEPTED','EXECUTING') AND c.execute_after<= ?::timestamptz AND NOT l.blocked ORDER BY c.execute_after,c.command_id LIMIT 32 FOR UPDATE OF c SKIP LOCKED", now);
            int completed = 0;
            for (var row : ready) {
                Command command = JsonSupport.MAPPER.readValue(row.get("payload").toString(), Command.class);
                if ("ACCEPTED".equals(row.get("state",String.class))) {
                    var hold=consumeFault(sql,command.commandId(),Set.of("HOLD_EXECUTING"));
                    if (hold!=null) {
                        sql.execute("UPDATE simulator_commands SET state='EXECUTING',version=version+1,execute_after=?::timestamptz WHERE command_id=?",
                                now.plusNanos(hold.get("delay_ms",Integer.class)*1_000_000L),command.commandId());
                        continue;
                    }
                }
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
        return evidenceFault(null,worldEvidence());
    }
    private JsonNode worldEvidence() {
        return Database.json(database, "SELECT jsonb_build_object('worldId',world_id,'journalGeneration',journal_generation,'completeHistory',complete_history,'observedAt',now(),'journalHighWater',(SELECT COALESCE(max(sequence),0) FROM execution_ledger),'lanes',(SELECT jsonb_agg(jsonb_build_object('siteId',site_id,'laneId',lane_id,'zoneId',zone_id,'blocked',blocked,'version',version) ORDER BY site_id,lane_id) FROM lanes)) FROM simulation_world WHERE singleton");
    }
    /** Test faults alter a response, never erase or rewind the independent physical ledger. */
    public JsonNode observedStatus(UUID id) {
        JsonNode actual=status(id);
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            var fault=consumeFault(sql,id,Set.of("STALE_STATUS","DUPLICATE_RESPONSE","HISTORY_GAP","WORLD_MISMATCH"));
            return alterEvidence(actual,fault);
        });
    }
    public JsonNode absence(UUID id) { return evidenceFault(id,worldEvidence()); }
    private JsonNode evidenceFault(UUID id,JsonNode actual) {
        return database.transactionResult(configuration -> alterEvidence(actual,consumeFault(DSL.using(configuration),id,Set.of("HISTORY_GAP","WORLD_MISMATCH"))));
    }
    private JsonNode alterEvidence(JsonNode actual,Record fault) {
        if (fault==null) return actual;
        String kind=fault.get("kind",String.class);
        if (Set.of("STALE_STATUS","DUPLICATE_RESPONSE").contains(kind)) return JsonSupport.read(fault.get("snapshot").toString());
        var altered=(tools.jackson.databind.node.ObjectNode)actual.deepCopy();
        if (kind.equals("HISTORY_GAP")) altered.put("completeHistory",false);
        else if (kind.equals("WORLD_MISMATCH")) altered.put("worldId",fault.get("fault_id",UUID.class).toString());
        return altered;
    }
    public void blockLane(String site, String lane, boolean blocked) {
        if (database.execute("UPDATE lanes SET blocked= ?,version=version+1 WHERE site_id= ? AND lane_id= ?", blocked, site, lane) != 1) throw Problem.missing();
    }
    public UUID fault(String kind, UUID selector, int count, int delay) {
        if (kind==null || !Set.of("BEFORE_ACCEPT","LOST_RESPONSE","DELAY_RESPONSE","HOLD_EXECUTING","STALE_STATUS","DUPLICATE_RESPONSE","HISTORY_GAP","WORLD_MISMATCH","REJECT_PRECONDITION").contains(kind)
                || count<1 || count>100 || delay<0 || delay>(kind.equals("HOLD_EXECUTING")?60000:10000))
            throw Problem.invalid("Unsupported or unbounded deterministic fault.");
        if (kind.equals("HOLD_EXECUTING") && delay<1000) throw Problem.invalid("An executing hold must last at least one second.");
        JsonNode snapshot=null;
        if (Set.of("STALE_STATUS","DUPLICATE_RESPONSE").contains(kind)) {
            if (selector==null) throw Problem.invalid("A repeated status requires an explicit existing command selector.");
            snapshot=status(selector);
        }
        UUID id=UUID.randomUUID();
        var activation=OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        if (Set.of("STALE_STATUS","DUPLICATE_RESPONSE").contains(kind)) activation=activation.plusNanos(delay*1_000_000L);
        database.execute("INSERT INTO simulation_faults(fault_id,kind,command_selector,remaining,delay_ms,snapshot,activate_after) VALUES (?,?,?,?,?,?::jsonb,?::timestamptz)",
                id,kind,selector,count,delay,snapshot==null?null:JsonSupport.write(snapshot),activation);
        return id;
    }
    private Record consumeFault(DSLContext sql,UUID selector,Set<String> kinds) {
        var row=sql.fetchOne("SELECT * FROM simulation_faults WHERE remaining>0 AND activate_after<=?::timestamptz AND kind=ANY(?::text[]) AND (command_selector IS NULL OR command_selector=?) ORDER BY created_at,fault_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),kinds.toArray(String[]::new),selector);
        if (row!=null) sql.execute("UPDATE simulation_faults SET remaining=remaining-1,last_activated_at=?::timestamptz WHERE fault_id=?",OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),row.get("fault_id"));
        return row;
    }
    public JsonNode faults() {
        return Database.json(database,"SELECT COALESCE(jsonb_agg(jsonb_build_object('faultId',fault_id,'kind',kind,'commandId',command_selector,'remaining',remaining,'delayMillis',delay_ms,'activateAfter',activate_after,'createdAt',created_at,'lastActivatedAt',last_activated_at,'clearedAt',cleared_at) ORDER BY created_at,fault_id),'[]'::jsonb) FROM (SELECT * FROM simulation_faults ORDER BY created_at DESC,fault_id LIMIT 100) f");
    }
    public void clearFault(UUID id) {
        if (database.execute("UPDATE simulation_faults SET remaining=0,cleared_at=?::timestamptz WHERE fault_id=?",OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),id)!=1) throw Problem.missing();
    }
    public JsonNode history(long after, int limit) {
        if (after<0 || limit<1 || limit>100) throw Problem.invalid("Invalid history cursor or limit.");
        return Database.json(database, "SELECT COALESCE(jsonb_agg(to_jsonb(e)), '[]'::jsonb) FROM (SELECT * FROM execution_ledger WHERE sequence> ? ORDER BY sequence LIMIT ?) e", after, limit);
    }
    /** Recovery must also account for accepted commands that have not produced an execution-ledger row. */
    public JsonNode recoveryInventory(UUID after,int limit) {
        if(limit<1 || limit>32)throw Problem.invalid("A recovery inventory page must contain 1–32 commands.");
        return Database.json(database,"""
            WITH page AS (
              SELECT command_id,site_id,movement_id,state,payload,version,accepted_at,completed_at,
                (SELECT sequence FROM execution_ledger e WHERE e.command_id=c.command_id) AS execution_sequence
              FROM simulator_commands c WHERE (?::uuid IS NULL OR command_id>?::uuid) ORDER BY command_id LIMIT ?
            )
            SELECT jsonb_build_object('worldId',w.world_id,'journalGeneration',w.journal_generation,'completeHistory',w.complete_history,
              'observedAt',now(),'totalCommands',(SELECT count(*) FROM simulator_commands),
              'journalHighWater',(SELECT coalesce(max(sequence),0) FROM execution_ledger),
              'items',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY command_id),'[]'::jsonb) FROM page p),
              'nextCursor',(SELECT command_id FROM page ORDER BY command_id DESC LIMIT 1))
            FROM simulation_world w WHERE singleton
            """,after,after,limit);
    }
}
