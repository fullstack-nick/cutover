package dev.cutover.adapter;

import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class CommandJournal {
    private final DSLContext database;
    private final EquipmentPort equipment;
    private final EquipmentObservations observations;
    private final Clock clock;
    public CommandJournal(DSLContext database,EquipmentPort equipment,EquipmentObservations observations,Clock clock) {
        this.database=database;this.equipment=equipment;this.observations=observations;this.clock=clock;
    }

    public JsonNode record(String site,String owner,UUID movementId,UUID allocationId,long epoch,String lane,JsonNode movement) {
        if (!Set.of("legacy-core","execution-service","returns-service").contains(owner)) throw new Problem(403,"DISPATCH_IDENTITY","This identity cannot submit physical commands.");
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            var route=Allocations.lockRoute(sql,site,movementId);
            var allocation=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id= ? AND movement_id= ? FOR UPDATE",site,movementId);
            if (!allocationId.equals(allocation.get("allocation_id",UUID.class)) || !JsonSupport.hash(movement).equals(allocation.get("payload_hash",String.class)))
                throw Problem.conflict("IMMUTABLE_MOVEMENT","The command must match its durable movement allocation.");
            var previous=sql.fetchOne("SELECT * FROM command_journal WHERE site_id= ? AND movement_id= ? FOR UPDATE",site,movementId);
            if (previous!=null) {
                JsonNode payload=JsonSupport.read(previous.get("payload").toString());
                if (!owner.equals(previous.get("owner",String.class)) || epoch!=previous.get("epoch",Long.class) || !lane.equals(payload.path("laneId").asString()))
                    throw Problem.conflict("IMMUTABLE_COMMAND","Command ownership, epoch and lane cannot change.");
                if ("COMPLETED".equals(previous.get("state",String.class))) return view(sql,site,movementId);
            }
            Database.requireDurability(sql,false);
            if (!"ASSIGNED".equals(allocation.get("state",String.class)) || !owner.equals(allocation.get("owner",String.class))
                    || epoch!=allocation.get("epoch",Long.class) || !owner.equals(route.get("owner",String.class))
                    || epoch!=route.get("epoch",Long.class) || "RECONCILIATION_REQUIRED".equals(route.get("state",String.class)))
                throw Problem.conflict("STALE_OWNER","The current routing authority does not authorize this dispatch.");
            if (previous!=null) return view(sql,site,movementId);
            JsonNode snapshot=observations.current(sql);
            boolean available=false;
            for (JsonNode candidate:snapshot.path("lanes")) if (site.equals(candidate.path("siteId").asString())
                    && allocation.get("zone_id",String.class).equals(candidate.path("zoneId").asString())
                    && lane.equals(candidate.path("laneId").asString()) && !candidate.path("blocked").asBoolean()) available=true;
            if (!available) throw Problem.conflict("LANE_BLOCKED","The requested compatible lane is unavailable.");
            ObjectNode payload=JsonSupport.MAPPER.createObjectNode();
            payload.put("commandId",movementId.toString());payload.put("allocationId",allocationId.toString());
            for (String key:Set.of("movementId","siteId","loadId","source","destination","zoneId","quantity")) payload.set(key,movement.required(key));
            payload.set("worldId",snapshot.required("worldId"));payload.set("journalGeneration",snapshot.required("journalGeneration"));
            payload.put("expectedLoadVersion",0);payload.put("laneId",lane);
            dev.cutover.platform.Contracts.validate("equipment-command.v1",JsonSupport.write(payload));
            sql.execute("INSERT INTO command_journal(command_id,allocation_id,site_id,movement_id,owner,epoch,payload,payload_hash,state,next_attempt_at,created_at) VALUES (?,?,?,?,?,?,?::jsonb,?,'SEND_PENDING',?::timestamptz,?::timestamptz)",
                    movementId,allocationId,site,movementId,owner,epoch,JsonSupport.write(payload),JsonSupport.hash(payload),now(),now());
            return view(sql,site,movementId);
        });
    }

    /** Network operations happen only after the journal transaction has committed. */
    public int work() {
        if (database.fetchOne("SELECT workers_paused FROM service_control WHERE singleton").get(0,Boolean.class)) return 0;
        var ids=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            var rows=sql.fetch("SELECT command_id FROM command_journal WHERE state NOT IN ('COMPLETED','QUARANTINED','REJECTED_BEFORE_EXECUTION') AND failure_attempts<5 AND next_attempt_at<= ?::timestamptz AND (lease_until IS NULL OR lease_until< ?::timestamptz) ORDER BY next_attempt_at,command_id LIMIT 16 FOR UPDATE SKIP LOCKED",now(),now());
            var selected=rows.getValues("command_id",UUID.class);
            for (UUID id:selected) sql.execute("UPDATE command_journal SET lease_until= ?::timestamptz WHERE command_id= ?",now().plusSeconds(10),id);
            return selected;
        });
        for(UUID id:ids) investigate(id);
        return ids.size();
    }

    private void investigate(UUID id) {
        var row=database.fetchOne("SELECT * FROM command_journal WHERE command_id= ?",id);
        JsonNode command=JsonSupport.read(row.get("payload").toString());
        try {
            EquipmentPort.Reply status=equipment.command(id);
            if (status.status()==404) {
                if (!sameHistory(command,status.body()) || row.get("evidence")!=null) {
                    transition(id,"QUARANTINED",status.body(),"The simulator cannot prove retained absence of a never-accepted command."); return;
                }
                boolean send=database.transactionResult(configuration -> {
                    var sql=DSL.using(configuration);
                    Database.requireDurability(sql,false);
                    var route=Allocations.lockRoute(sql,row.get("site_id",String.class),id);
                    var current=sql.fetchOne("SELECT * FROM command_journal WHERE command_id= ? FOR UPDATE",id);
                    if ("COMPLETED".equals(current.get("state",String.class))) return false;
                    if (!current.get("owner").equals(route.get("owner")) || !current.get("epoch").equals(route.get("epoch")) || "RECONCILIATION_REQUIRED".equals(route.get("state")))
                        throw Problem.conflict("STALE_OWNER","The route changed before the journaled send.");
                    sql.execute("UPDATE command_journal SET state='SEND_PENDING',attempts=attempts+1 WHERE command_id= ?",id);
                    return true;
                });
                if (!send) return;
                status=equipment.send(id,command);
            }
            if (status.status()<200 || status.status()>=300) throw new EquipmentPort.Unavailable("Equipment did not return a usable command state.");
            JsonNode proof=status.body();
            if (!sameHistory(command,proof) || !id.toString().equals(proof.path("commandId").asString())
                    || !command.path("siteId").asString().equals(proof.path("siteId").asString())
                    || !id.toString().equals(proof.path("movementId").asString())) {
                transition(id,"QUARANTINED",proof,"Command identity or physical-world evidence differs.");return;
            }
            String state=switch(proof.path("state").asString()) {
                case "ACCEPTED" -> "ACCEPTED_BY_SIMULATOR";
                case "EXECUTING" -> "EXECUTING";
                case "COMPLETED" -> "COMPLETED";
                case "REJECTED_BEFORE_EXECUTION" -> "REJECTED_BEFORE_EXECUTION";
                default -> "QUARANTINED";
            };
            if (state.equals("COMPLETED") && (proof.path("executionSequence").asLong(0)<1 || proof.path("completedAt").isNull())) state="QUARANTINED";
            transition(id,state,proof,state.equals("QUARANTINED")?"Unrecognized or incomplete completion evidence.":null);
        } catch(EquipmentPort.Unavailable failure) {
            transition(id,"OUTCOME_UNKNOWN",null,"Transport outcome is unknown; investigate the same command identity.");
        } catch(Problem paused) {
            database.execute("UPDATE command_journal SET lease_until=NULL,next_attempt_at= ?::timestamptz,last_error= ? WHERE command_id= ?",now().plusSeconds(2),paused.code(),id);
        }
    }

    private void transition(UUID id,String state,JsonNode proof,String error) {
        database.transaction(configuration -> {
            var sql=DSL.using(configuration);
            var summary=sql.fetchOne("SELECT site_id FROM command_journal WHERE command_id= ?",id);
            String site=summary.get("site_id",String.class);
            Allocations.lockRoute(sql,site,id);
            var allocation=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id= ? AND movement_id= ? FOR UPDATE",site,id);
            var row=sql.fetchOne("SELECT * FROM command_journal WHERE command_id= ? FOR UPDATE",id);
            if ("COMPLETED".equals(row.get("state",String.class))) return;
            boolean changed=!state.equals(row.get("state",String.class));
            int failures=state.equals("OUTCOME_UNKNOWN")?row.get("failure_attempts",Integer.class)+1:0;
            sql.execute("UPDATE command_journal SET state= ?,version=version+ ?,evidence=COALESCE(?::jsonb,evidence),last_error= ?,failure_attempts= ?,lease_until=NULL,next_attempt_at= ?::timestamptz,completed_at=CASE WHEN ? ='COMPLETED' THEN ?::timestamptz ELSE completed_at END WHERE command_id= ?",
                    state,changed?1:0,proof==null?null:JsonSupport.write(proof),error,failures,now().plusNanos(failures>0?(1L<<Math.min(failures-1,4))*1_000_000_000:200_000_000),state,now(),id);
            if (!changed) return;
            long version=allocation.get("version",Long.class)+1;
            sql.execute("UPDATE movement_allocations SET version= ?,state=CASE WHEN ? ='COMPLETED' THEN 'COMPLETED' ELSE state END,completed_at=CASE WHEN ? ='COMPLETED' THEN ?::timestamptz ELSE completed_at END WHERE allocation_id= ?",
                    version,state,state,now(),allocation.get("allocation_id"));
            String type=switch(state) {
                case "COMPLETED" -> "MovementCompleted.v1";
                case "ACCEPTED_BY_SIMULATOR","EXECUTING" -> "CommandAccepted.v1";
                case "REJECTED_BEFORE_EXECUTION" -> "CommandRejected.v1";
                default -> "CommandOutcomeUnknown.v1";
            };
            var payload=(ObjectNode)Allocations.view(sql,site,id);
            payload.set("command",view(sql,site,id));
            Events.append(sql,site,"equipment-adapter","movement",id,version,type,id,payload);
        });
    }

    private boolean sameHistory(JsonNode command,JsonNode response) {
        return response.path("completeHistory").asBoolean(false) && command.path("worldId").equals(response.path("worldId")) && command.path("journalGeneration").equals(response.path("journalGeneration"));
    }
    public JsonNode get(String site,UUID command) { return view(database,site,command); }
    static JsonNode view(DSLContext sql,String site,UUID command) {
        return Database.json(sql,"SELECT jsonb_build_object('commandId',command_id,'allocationId',allocation_id,'movementId',movement_id,'siteId',site_id,'owner',owner,'epoch',epoch,'state',state,'version',version,'attempts',attempts,'failureAttempts',failure_attempts,'payload',payload,'evidence',evidence,'lastError',last_error,'createdAt',created_at,'completedAt',completed_at) FROM command_journal WHERE site_id= ? AND command_id= ?",site,command);
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
}
