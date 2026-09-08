package dev.cutover.adapter;

import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.Contracts;
import dev.cutover.platform.Idempotency;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.OperationTrace;
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
        return OperationTrace.call("cutover.command.record",site,movementId,()->recordTraced(site,owner,movementId,allocationId,epoch,lane,movement));
    }
    private JsonNode recordTraced(String site,String owner,UUID movementId,UUID allocationId,long epoch,String lane,JsonNode movement) {
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
        if (!database.fetchOne("SELECT EXISTS(SELECT 1 FROM command_journal WHERE state NOT IN ('COMPLETED','QUARANTINED','REJECTED_BEFORE_EXECUTION') AND failure_attempts<5 AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz))",now(),now()).get(0,Boolean.class)) return 0;
        var ids=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return java.util.List.<UUID>of();
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
        try (var trace=OperationTrace.movement(database,"equipment-adapter",row.get("site_id",String.class),id,"cutover.command.investigate")) {
            investigateTraced(id,row);
        }
    }
    private void investigateTraced(UUID id, Record row) {
        JsonNode command=JsonSupport.read(row.get("payload").toString());
        try {
            EquipmentPort.Reply status=equipment.command(id);
            if (status.status()==404) {
                if (!sameHistory(command,status.body()) || row.get("accepted_ever",Boolean.class)) {
                    transition(id,"QUARANTINED",status.body(),"The simulator cannot prove retained absence of a never-accepted command."); return;
                }
                boolean send=database.transactionResult(configuration -> {
                    var sql=DSL.using(configuration);
                    Database.requireDurability(sql,false);
                    var route=Allocations.lockRoute(sql,row.get("site_id",String.class),id);
                    var allocation=sql.fetchOne("SELECT state FROM movement_allocations WHERE site_id=? AND movement_id=? FOR UPDATE",row.get("site_id"),id);
                    var current=sql.fetchOne("SELECT * FROM command_journal WHERE command_id= ? FOR UPDATE",id);
                    if ("COMPLETED".equals(current.get("state",String.class)) || "CANCELLED".equals(allocation.get(0,String.class))) return false;
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
            if (proof.path("version").asLong(0)<1 || (state.equals("COMPLETED")
                    && (proof.path("executionSequence").asLong(0)<1 || !proof.path("completedAt").isString()))) state="QUARANTINED";
            transition(id,state,proof,state.equals("QUARANTINED")?"Unrecognized or incomplete completion evidence.":null);
        } catch(EquipmentPort.Unavailable failure) {
            transition(id,"OUTCOME_UNKNOWN",null,"Transport outcome is unknown; investigate the same command identity.");
        } catch(Problem paused) {
            database.transaction(configuration->{var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
                sql.execute("UPDATE command_journal SET lease_until=NULL,next_attempt_at= ?::timestamptz,last_error= ? WHERE command_id= ? AND state NOT IN ('COMPLETED','REJECTED_BEFORE_EXECUTION')",now().plusSeconds(2),paused.code(),id);
            });
        }
    }

    private void transition(UUID id,String proposedState,JsonNode proof,String proposedError) {
        database.transaction(configuration -> {
            var sql=DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return;
            var summary=sql.fetchOne("SELECT site_id FROM command_journal WHERE command_id= ?",id);
            String site=summary.get("site_id",String.class);
            Allocations.lockRoute(sql,site,id);
            var allocation=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id= ? AND movement_id= ? FOR UPDATE",site,id);
            var row=sql.fetchOne("SELECT * FROM command_journal WHERE command_id= ? FOR UPDATE",id);
            if ("COMPLETED".equals(row.get("state",String.class)) || "CANCELLED".equals(allocation.get("state",String.class))) return;
            String state=proposedState, error=proposedError;
            boolean validProof=proof!=null && !state.equals("QUARANTINED");
            long evidenceVersion=row.get("evidence_version",Long.class);
            if (validProof && proof.path("version").asLong()<evidenceVersion) {
                sql.execute("UPDATE command_journal SET last_observation=?::jsonb,last_error='STALE_OBSERVATION',lease_until=NULL,next_attempt_at=?::timestamptz WHERE command_id=?",
                        JsonSupport.write(proof),now().plusSeconds(1),id);
                return;
            }
            if (validProof && evidenceVersion>0) {
                JsonNode prior=JsonSupport.read(row.get("evidence").toString());
                if ((proof.path("version").asLong()==evidenceVersion && !JsonSupport.hash(prior).equals(JsonSupport.hash(proof)))
                        || (progress(proof.path("state").asString())<progress(prior.path("state").asString()))) {
                    state="QUARANTINED"; error="CONTRADICTORY_OBSERVATION"; validProof=false;
                }
            }
            boolean changed=!state.equals(row.get("state",String.class));
            int failures=state.equals("OUTCOME_UNKNOWN")?row.get("failure_attempts",Integer.class)+1:0;
            sql.execute("UPDATE command_journal SET state= ?,version=version+ ?,evidence=COALESCE(?::jsonb,evidence),last_observation=COALESCE(?::jsonb,last_observation),evidence_version=?,accepted_ever=accepted_ever OR ?,last_error= ?,failure_attempts= ?,lease_until=NULL,next_attempt_at= ?::timestamptz,completed_at=CASE WHEN ? ='COMPLETED' THEN ?::timestamptz ELSE completed_at END WHERE command_id= ?",
                    state,changed?1:0,validProof?JsonSupport.write(proof):null,proof==null?null:JsonSupport.write(proof),
                    validProof?proof.path("version").asLong():evidenceVersion,validProof,error,failures,
                    now().plusNanos(failures>0?(1L<<Math.min(failures-1,4))*1_000_000_000:200_000_000),state,now(),id);
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

    private static int progress(String state) {
        return switch(state) { case "ACCEPTED" -> 1; case "EXECUTING" -> 2; case "COMPLETED","REJECTED_BEFORE_EXECUTION" -> 3; default -> 0; };
    }

    /** Records investigation authority only. The worker must still obtain evidence before any send. */
    public JsonNode reconcile(String actor,String site,UUID id,String key,JsonNode request) {
        try { Contracts.validate("reconciliation-request.v1",JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Provide an expected version and a reason of 8–500 characters."); }
        String reason=request.path("reason").asString().trim();
        if (reason.length()<8) throw Problem.invalid("Explain the evidence or correction that warrants investigation.");
        return database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"command-investigation",key,Map.of("commandId",id,"request",request),()-> {
                if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Command investigation is paused for the checkpoint.");
                Allocations.lockRoute(sql,site,id);
                var row=sql.fetchOne("SELECT * FROM command_journal WHERE site_id=? AND command_id=? FOR UPDATE",site,id);
                if (row==null) throw Problem.missing();
                long before=row.get("version",Long.class);
                if (before!=request.path("expectedVersion").asLong()) throw Problem.conflict("VERSION_CONFLICT","The command changed; inspect the latest evidence before requesting investigation.");
                if (Set.of("COMPLETED","REJECTED_BEFORE_EXECUTION").contains(row.get("state",String.class)))
                    throw Problem.conflict("COMMAND_TERMINAL","A terminal command cannot be redispatched or reopened.");
                OffsetDateTime lease=row.get("lease_until",OffsetDateTime.class);
                if (lease!=null && lease.isAfter(now())) throw Problem.conflict("INVESTIGATION_IN_FLIGHT","Let the current status investigation settle before recording another.");
                sql.execute("UPDATE command_journal SET state='OUTCOME_UNKNOWN',version=version+1,failure_attempts=0,next_attempt_at=?::timestamptz,lease_until=NULL,last_error='INVESTIGATION_RECORDED' WHERE command_id=?",now(),id);
                JsonNode response=JsonSupport.MAPPER.valueToTree(Map.of("commandId",id,"state","INVESTIGATION_RECORDED","version",before+1));
                sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,'command-investigation',?,?,?,?, 'RECORDED',?::jsonb)",
                        UUID.randomUUID(),site,actor,id.toString(),reason,before,before+1,JsonSupport.write(response));
                return response;
            });
        });
    }

    public JsonNode recoverable(String site) {
        return Database.json(database,"SELECT jsonb_build_object('observedAt',now(),'items',COALESCE(jsonb_agg(jsonb_build_object('commandId',command_id,'siteId',site_id,'owner',owner,'state',state,'version',version,'failureAttempts',failure_attempts,'lastError',last_error,'createdAt',created_at) ORDER BY created_at,command_id),'[]'::jsonb)) FROM (SELECT * FROM command_journal WHERE site_id=? AND (state IN ('OUTCOME_UNKNOWN','QUARANTINED') OR failure_attempts>=5) ORDER BY created_at,command_id LIMIT 100) c",site);
    }

    private boolean sameHistory(JsonNode command,JsonNode response) {
        return response.path("completeHistory").asBoolean(false) && command.path("worldId").equals(response.path("worldId")) && command.path("journalGeneration").equals(response.path("journalGeneration"));
    }
    public JsonNode get(String site,UUID command) { return view(database,site,command); }
    public JsonNode timeline(String site,UUID command) {
        return Database.json(database,"""
                SELECT jsonb_build_object('commandId',c.command_id,'movementId',c.movement_id,'siteId',c.site_id,
                    'observedAt',now(),'state',c.state,'commandVersion',c.version,'journalRecordedAt',c.created_at,
                    'retentionDays',7,'historyComplete',e.count=a.version AND e.first_version=1 AND e.last_version=a.version,
                    'events',coalesce(e.events,'[]'::jsonb))
                FROM command_journal c JOIN movement_allocations a ON a.allocation_id=c.allocation_id
                LEFT JOIN LATERAL (
                    SELECT count(*) AS count,min(aggregate_version) AS first_version,max(aggregate_version) AS last_version,
                        jsonb_agg(jsonb_build_object('id',event_id,'type',event_type,'version',aggregate_version,'at',created_at,
                            'state',envelope#>>'{payload,command,state}') ORDER BY aggregate_version) AS events
                    FROM (SELECT * FROM outbox WHERE site_id=c.site_id AND source='equipment-adapter'
                        AND aggregate_type='movement' AND aggregate_id=c.movement_id ORDER BY aggregate_version DESC LIMIT 200) retained
                ) e ON true WHERE c.site_id=? AND c.command_id=?
                """,site,command);
    }
    static JsonNode view(DSLContext sql,String site,UUID command) {
        return Database.json(sql,"SELECT jsonb_build_object('commandId',command_id,'allocationId',allocation_id,'movementId',movement_id,'siteId',site_id,'owner',owner,'epoch',epoch,'state',state,'version',version,'attempts',attempts,'failureAttempts',failure_attempts,'payload',payload,'evidence',evidence,'lastObservation',last_observation,'evidenceVersion',evidence_version,'acceptedEver',accepted_ever,'lastError',last_error,'createdAt',created_at,'completedAt',completed_at) FROM command_journal WHERE site_id= ? AND command_id= ?",site,command);
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
}
