package dev.cutover.adapter;

import dev.cutover.platform.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Owner-local recovery evidence. Scanning never dispatches, creates business intent, or clears the restore hold. */
public final class RestoreReconciliation {
    @FunctionalInterface public interface History { JsonNode read(long after,int limit); }
    @FunctionalInterface public interface Inventory { JsonNode read(UUID after,int limit); }
    public record Start(UUID restoreId,String checkpointName,String manifestSha256,UUID worldId,UUID journalGeneration,
                        Instant checkpointAt,long highWater,String actor,String reason) {}
    private final DSLContext database;private final EquipmentPort equipment;private final History history;private final Inventory inventory;private final Clock clock;
    public RestoreReconciliation(DSLContext database,EquipmentPort equipment,History history,Inventory inventory,Clock clock) {
        this.database=database;this.equipment=equipment;this.history=history;this.inventory=inventory;this.clock=clock;
    }
    public JsonNode begin(Start request) {
        if(request==null || request.restoreId()==null || request.worldId()==null || request.journalGeneration()==null || request.checkpointAt()==null
            || request.checkpointName()==null || request.manifestSha256()==null || request.actor()==null || request.reason()==null
            || !request.checkpointName().matches("[a-z0-9][a-z0-9-]{2,63}") || !request.manifestSha256().matches("[a-f0-9]{64}")
            || request.highWater()<0 || request.checkpointAt().isAfter(clock.instant()) || !request.actor().matches("[a-z][a-z0-9-]{2,63}")
            || request.reason().strip().length()<8 || request.reason().length()>500)throw Problem.invalid("Use a verified checkpoint identity, physical world, timestamp and bounded recovery reason.");
        String hash=JsonSupport.hash(request);
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);
            var control=sql.fetchOne("SELECT * FROM service_control WHERE singleton FOR UPDATE");
            var old=sql.fetchOne("SELECT request_hash FROM restore_sessions WHERE restore_id=?",request.restoreId());
            if(old!=null){if(!hash.equals(old.get(0)))throw Problem.conflict("RESTORE_IDENTITY_CONFLICT","The original checkpoint request cannot change.");return view(sql,request.restoreId());}
            if(!control.get("workers_paused",Boolean.class) || !control.get("dispatch_paused",Boolean.class) || !control.get("intake_paused",Boolean.class))
                throw Problem.conflict("RESTORE_REQUIRES_FREEZE","Start restoration evidence only while all adapter writes, intake and dispatch are frozen.");
            if(sql.fetchOne("SELECT count(*) FROM restore_sessions").get(0,Integer.class)>=16 || sql.fetchOne("SELECT EXISTS(SELECT 1 FROM restore_sessions WHERE state<>'RELEASED')").get(0,Boolean.class))
                throw Problem.conflict("RESTORE_ALREADY_HELD","An unresolved restoration cannot be replaced by another session.");
            if(sql.fetchOne("SELECT EXISTS(SELECT 1 FROM migration_sessions WHERE phase IN ('DRAINING','RECONCILING','READY_TO_SWITCH','OBSERVING','REVERSING'))").get(0,Boolean.class))
                throw Problem.conflict("RESTORE_MIGRATION_UNSETTLED","This restoration procedure requires a checkpoint with settled migration sessions.");
            sql.execute("INSERT INTO restore_sessions(restore_id,checkpoint_name,manifest_sha256,request_hash,world_id,journal_generation,checkpoint_at,checkpoint_high_water,observed_high_water,actor,reason,routes_hash,started_at) VALUES (?,?,?,?,?,?,?::timestamptz,?,0,?,?,?,?::timestamptz)",request.restoreId(),request.checkpointName(),request.manifestSha256(),hash,request.worldId(),request.journalGeneration(),OffsetDateTime.ofInstant(request.checkpointAt(),ZoneOffset.UTC),request.highWater(),request.actor(),request.reason(),routesHash(sql),now());
            sql.execute("UPDATE service_control SET restoration_required=true,version=version+1 WHERE singleton");
            audit(sql,request.restoreId(),request.actor(),"restore-held",request.reason(),"HELD",Map.of("manifestSha256",request.manifestSha256(),"checkpointHighWater",request.highWater(),"beforeVersion",0L,"afterVersion",1L));
            return view(sql,request.restoreId());
        });
    }
    public JsonNode scan(UUID id) {
        var before=database.fetchOne("SELECT * FROM restore_sessions WHERE restore_id=?",id);if(before==null)throw Problem.missing();
        if(Set.of("QUARANTINED","VERIFIED","RELEASED").contains(before.get("state",String.class)))return view(database,id);
        long cursor=before.get("scan_cursor",Long.class);JsonNode world=equipment.equipment();
        String error=worldError(before,world);
        JsonNode page=error==null?history.read(cursor,100):JsonSupport.MAPPER.createArrayNode();
        if(error==null && (!page.isArray() || page.size()>100))error="PHYSICAL_HISTORY_PROTOCOL";
        final String worldFailure=error;
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);var session=lock(sql,id,before.get("version",Long.class));
            if(worldFailure!=null)return quarantine(sql,session,worldFailure);
            if(!routesHash(sql).equals(session.get("routes_hash")))return quarantine(sql,session,"RESTORED_ROUTES_CHANGED");
            long high=world.path("journalHighWater").asLong();
            if(high<cursor || high<session.get("checkpoint_high_water",Long.class))return quarantine(sql,session,"PHYSICAL_HISTORY_REWOUND");
            var selected=new ArrayList<JsonNode>();var identities=new HashSet<UUID>();long next=cursor,bytes=0;
            for(var item:page){
                long sequence=item.path("sequence").asLong(-1);
                if(sequence>high)break; // Completion after the observed frontier belongs to the next page scan.
                try{
                    if(!item.path("sequence").isIntegralNumber() || sequence<=next || !identities.add(Database.uuid(item,"command_id"))
                        || !Database.uuid(item,"command_id").equals(Database.uuid(item,"movement_id")) || !Set.of("site-a","site-b").contains(item.path("site_id").asString())
                        || !item.path("quantity").isIntegralNumber() || item.path("quantity").asLong()<1 || !item.path("before_version").isIntegralNumber() || !item.path("after_version").isIntegralNumber() || item.path("before_version").asLong(-1)<0
                        || item.path("after_version").asLong(-1)!=item.path("before_version").asLong()+1) return quarantine(sql,session,"PHYSICAL_HISTORY_PROTOCOL");
                    Database.uuid(item,"load_id");OffsetDateTime.parse(item.path("completed_at").asString());
                }catch(RuntimeException invalid){return quarantine(sql,session,"PHYSICAL_HISTORY_PROTOCOL");}
                next=sequence;selected.add(item);bytes+=JsonSupport.write(item).getBytes(StandardCharsets.UTF_8).length+512;
            }
            if(selected.isEmpty() && cursor<high)return quarantine(sql,session,"PHYSICAL_HISTORY_GAP");
            if(session.get("scanned_count",Integer.class)+selected.size()>20000 || session.get("retained_bytes",Long.class)+bytes>33554432)
                return quarantine(sql,session,"RESTORE_EVIDENCE_CAPACITY");
            for(var item:selected)if(sql.fetchOne("SELECT EXISTS(SELECT 1 FROM restore_physical_findings WHERE restore_id=? AND (command_id=? OR execution_sequence=?))",id,Database.uuid(item,"command_id"),item.path("sequence").asLong()).get(0,Boolean.class))return quarantine(sql,session,"PHYSICAL_HISTORY_CONFLICT");
            int unresolved=0;
            for(var item:selected){
                String state=compare(sql,session,item);if(!state.equals("MATCHED"))unresolved++;
                sql.execute("INSERT INTO restore_physical_findings(restore_id,command_id,site_id,execution_sequence,state,physical_evidence,evidence_hash,observed_at) VALUES (?,?,?,?,?,?::jsonb,?,?::timestamptz)",id,Database.uuid(item,"command_id"),item.path("site_id").asString(),item.path("sequence").asLong(),state,JsonSupport.write(item),JsonSupport.hash(item),now());
            }
            int totalUnresolved=session.get("unresolved_count",Integer.class)+unresolved;
            boolean scanned=next==high;
            String phase=scanned && totalUnresolved>0?"QUARANTINED":"SCANNING";
            sql.execute("UPDATE restore_sessions SET scan_cursor=?,observed_high_water=?,scanned_count=scanned_count+?,unresolved_count=?,retained_bytes=retained_bytes+?,state=?,last_error=?,version=version+1 WHERE restore_id=?",next,high,selected.size(),totalUnresolved,bytes,phase,scanned?(totalUnresolved>0?"MISSING_OR_CONFLICTING_BUSINESS_CONTEXT":"COMMAND_STATUS_PROOFS_REQUIRED"):null,id);
            if(phase.equals("QUARANTINED"))audit(sql,id,session.get("actor",String.class),"restore-quarantined",session.get("reason",String.class),phase,Map.of("unresolved",totalUnresolved,"physicalHighWater",high,"beforeVersion",session.get("version"),"afterVersion",session.get("version",Long.class)+1));
            return view(sql,id);
        });
    }
    /** Each page binds immutable command intent, including commands with no physical completion yet. */
    public JsonNode scanInventory(UUID id) {
        var before=session(id);
        if(terminal(before) || before.get("inventory_complete",Boolean.class))return view(database,id);
        UUID cursor=before.get("inventory_cursor",UUID.class);var page=inventory.read(cursor,32);
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);var row=lock(sql,id,before.get("version",Long.class));
            String error=worldError(row,page);if(error!=null)return quarantine(sql,row,error);
            if(!routesHash(sql).equals(row.get("routes_hash")))return quarantine(sql,row,"RESTORED_ROUTES_CHANGED");
            var items=page.path("items");int total=page.path("totalCommands").asInt(-1),count=row.get("inventory_count",Integer.class);
            if(!page.path("totalCommands").isIntegralNumber() || total<0 || total>20000 || !items.isArray() || items.size()>32 || count+items.size()>total)
                return quarantine(sql,row,"PHYSICAL_INVENTORY_PROTOCOL");
            if(row.get("inventory_total")!=null && total!=row.get("inventory_total",Integer.class))return quarantine(sql,row,"PHYSICAL_INVENTORY_CHANGED");
            if(items.isEmpty() && count<total)return quarantine(sql,row,"PHYSICAL_INVENTORY_GAP");
            String previous=cursor==null?"":cursor.toString();long bytes=0;
            for(var item:items){
                try {
                    String current=Database.uuid(item,"command_id").toString();
                    if(current.compareTo(previous)<=0 || !current.equals(Database.uuid(item,"movement_id").toString())
                        || !Set.of("site-a","site-b").contains(item.path("site_id").asString())
                        || !Set.of("ACCEPTED","EXECUTING","COMPLETED","REJECTED_BEFORE_EXECUTION").contains(item.path("state").asString())
                        || !item.path("version").isIntegralNumber() || item.path("version").asLong()<1 || !item.path("payload").isObject())return quarantine(sql,row,"PHYSICAL_INVENTORY_PROTOCOL");
                    Contracts.validate("equipment-command.v1",JsonSupport.write(item.path("payload")));
                    OffsetDateTime.parse(item.path("accepted_at").asString());previous=current;
                }catch(RuntimeException invalid){return quarantine(sql,row,"PHYSICAL_INVENTORY_PROTOCOL");}
                bytes+=JsonSupport.write(item).getBytes(StandardCharsets.UTF_8).length+512;
            }
            if((items.isEmpty() && !page.path("nextCursor").isNull()) || (!items.isEmpty() && !previous.equals(page.path("nextCursor").asString())))
                return quarantine(sql,row,"PHYSICAL_INVENTORY_PROTOCOL");
            if(row.get("retained_bytes",Long.class)+bytes>33554432)return quarantine(sql,row,"RESTORE_EVIDENCE_CAPACITY");
            int unresolved=row.get("inventory_unresolved",Integer.class);
            for(var item:items){
                String state=compareInventory(sql,row,item);if(!state.equals("MATCHED"))unresolved++;
                sql.execute("INSERT INTO restore_inventory_findings(restore_id,command_id,site_id,state,simulator_state,evidence,evidence_hash,observed_at) VALUES (?,?,?,?,?,?::jsonb,?,?::timestamptz)",id,Database.uuid(item,"command_id"),item.path("site_id").asString(),state,item.path("state").asString(),JsonSupport.write(item),JsonSupport.hash(item),now());
            }
            boolean done=count+items.size()==total;
            sql.execute("UPDATE restore_sessions SET inventory_cursor=?::uuid,inventory_total=?,inventory_count=inventory_count+?,inventory_unresolved=?,inventory_complete=?,retained_bytes=retained_bytes+?,state='SCANNING',version=version+1 WHERE restore_id=?",items.isEmpty()?cursor:UUID.fromString(previous),total,items.size(),unresolved,done,bytes,id);
            if(done && unresolved>0)return quarantine(sql,sql.fetchOne("SELECT * FROM restore_sessions WHERE restore_id=?",id),"MISSING_OR_CONFLICTING_COMMAND_CONTEXT");
            return view(sql,id);
        });
    }
    /** Missing inventory entries need individual, same-world 404 proofs. A timeout is never absence. */
    public JsonNode proveAbsence(UUID id) {
        var before=session(id);if(terminal(before))return view(database,id);
        if(!before.get("inventory_complete",Boolean.class))throw Problem.conflict("RESTORE_INVENTORY_INCOMPLETE","Scan the complete physical command inventory first.");
        var candidates=database.fetch("SELECT c.command_id FROM command_journal c WHERE NOT EXISTS(SELECT 1 FROM restore_inventory_findings f WHERE f.restore_id=? AND f.command_id=c.command_id) AND NOT EXISTS(SELECT 1 FROM restore_absence_proofs p WHERE p.restore_id=? AND p.command_id=c.command_id) ORDER BY c.command_id LIMIT 16",id,id);
        if(candidates.isEmpty())return view(database,id);
        var proofs=new LinkedHashMap<UUID,EquipmentPort.Reply>();for(var candidate:candidates){UUID command=candidate.get(0,UUID.class);proofs.put(command,equipment.command(command));}
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);var row=lock(sql,id,before.get("version",Long.class));long bytes=0;
            for(var entry:proofs.entrySet()){
                var command=sql.fetchOne("SELECT * FROM command_journal WHERE command_id=?",entry.getKey());var reply=entry.getValue();
                if(reply.status()!=404)return quarantine(sql,row,reply.status()==200?"PHYSICAL_INVENTORY_CHANGED":"COMMAND_ABSENCE_UNAVAILABLE");
                String error=worldError(row,reply.body());if(error!=null)return quarantine(sql,row,error);
                if(command.get("accepted_ever",Boolean.class) || Set.of("COMPLETED","QUARANTINED").contains(command.get("state",String.class)))return quarantine(sql,row,"ACCEPTED_COMMAND_MISSING");
                var proof=JsonSupport.MAPPER.createObjectNode().put("commandId",entry.getKey().toString()).put("status",404);proof.set("world",reply.body());
                bytes+=JsonSupport.write(proof).getBytes(StandardCharsets.UTF_8).length+256;
                if(row.get("retained_bytes",Long.class)+bytes>33554432)return quarantine(sql,row,"RESTORE_EVIDENCE_CAPACITY");
                sql.execute("INSERT INTO restore_absence_proofs(restore_id,command_id,evidence,evidence_hash,observed_at) VALUES (?,?,?::jsonb,?,?::timestamptz)",id,entry.getKey(),JsonSupport.write(proof),JsonSupport.hash(proof),now());
            }
            sql.execute("UPDATE restore_sessions SET retained_bytes=retained_bytes+?,version=version+1 WHERE restore_id=?",bytes,id);return view(sql,id);
        });
    }
    public JsonNode verify(UUID id) {
        var before=session(id);if(terminal(before))return view(database,id);var frontier=inventory.read(null,1);
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);var row=lock(sql,id,before.get("version",Long.class));
            String error=frontierError(row,frontier);if(error!=null){if(error.equals("RESTORE_SCAN_REQUIRED"))throw Problem.conflict(error,"Scan the current physical frontier before verification.");return quarantine(sql,row,error);}
            if(!routesHash(sql).equals(row.get("routes_hash")))return quarantine(sql,row,"RESTORED_ROUTES_CHANGED");
            if(!commandsReconciled(sql,id))throw Problem.conflict("RESTORE_COMMANDS_UNSETTLED","Every restored command needs consistent original status or a retained absence proof.");
            String hash=verificationHash(sql,id);
            sql.execute("UPDATE restore_sessions SET state='VERIFIED',verification_hash=?,verified_at=?::timestamptz,last_error=NULL,version=version+1 WHERE restore_id=?",hash,now(),id);
            audit(sql,id,row.get("actor",String.class),"restore-verified",row.get("reason",String.class),"VERIFIED",Map.of("proofHash",hash,"beforeVersion",row.get("version"),"afterVersion",row.get("version",Long.class)+1));return view(sql,id);
        });
    }
    /** The normal dispatch flags remain closed; the local restoration procedure reopens them after replay settles. */
    public JsonNode release(UUID id,long version,String actor,String reason) {
        if(actor==null || !actor.matches("[a-z][a-z0-9-]{2,63}") || reason==null || reason.strip().length()<8 || reason.length()>500 || version<1)throw Problem.invalid("A version, recovery actor and bounded reason are required.");
        String requestHash=JsonSupport.hash(Map.of("id",id,"version",version,"actor",actor,"reason",reason));var before=session(id);
        if(before.get("state").equals("RELEASED")){if(!requestHash.equals(before.get("release_request_hash")))throw Problem.conflict("RESTORE_RELEASE_CONFLICT","The recorded release request cannot change.");return view(database,id);}
        if(!before.get("state").equals("VERIFIED"))throw Problem.conflict("RESTORE_NOT_VERIFIED","Only a verified restoration can release dispatch.");
        var frontier=inventory.read(null,1);
        return database.transactionResult(configuration->{var sql=DSL.using(configuration);
            // Lock exclusively before workersMayWrite takes its shared freeze lock: no lock upgrade.
            sql.fetchOne("SELECT * FROM service_control WHERE singleton FOR UPDATE");var row=lock(sql,id,version);
            if(!row.get("state").equals("VERIFIED"))throw Problem.conflict("RESTORE_NOT_VERIFIED","The recovery state changed before release.");
            String error=frontierError(row,frontier);
            if("RESTORE_SCAN_REQUIRED".equals(error) || (error==null && !commandsReconciled(sql,id))) {
                sql.execute("UPDATE restore_sessions SET state='SCANNING',verification_hash=NULL,verified_at=NULL,last_error='RESTORE_SCAN_REQUIRED',version=version+1 WHERE restore_id=?",id);
                audit(sql,id,actor,"restore-verification-expired",reason,"DISPATCH_HELD",Map.of("beforeVersion",version,"afterVersion",version+1));return view(sql,id);
            }
            if(error!=null || !routesHash(sql).equals(row.get("routes_hash")) || !commandsReconciled(sql,id) || !verificationHash(sql,id).equals(row.get("verification_hash")))
                return quarantine(sql,row,error==null?"RESTORE_PROOF_CHANGED":error);
            sql.execute("UPDATE service_control SET restoration_required=false,version=version+1 WHERE singleton");
            sql.execute("UPDATE restore_sessions SET state='RELEASED',released_at=?::timestamptz,release_request_hash=?,version=version+1 WHERE restore_id=?",now(),requestHash,id);
            audit(sql,id,actor,"restore-released",reason,"RELEASED",Map.of("beforeVersion",version,"afterVersion",version+1,"proofHash",row.get("verification_hash")));return view(sql,id);
        });
    }
    private String compareInventory(DSLContext sql,Record session,JsonNode item) {
        UUID id=Database.uuid(item,"command_id");var command=sql.fetchOne("SELECT * FROM command_journal WHERE command_id=? AND site_id=?",id,item.path("site_id").asString());
        var allocation=sql.fetchOne("SELECT * FROM movement_allocations WHERE movement_id=? AND site_id=?",id,item.path("site_id").asString());
        if(command==null || allocation==null)return "MISSING_BUSINESS_CONTEXT";
        var payload=JsonSupport.read(command.get("payload").toString());var movement=JsonSupport.read(allocation.get("movement").toString());
        if(!payload.equals(item.path("payload")) || !JsonSupport.hash(payload).equals(command.get("payload_hash")) || !JsonSupport.hash(movement).equals(allocation.get("payload_hash"))
            || !command.get("allocation_id").equals(allocation.get("allocation_id")) || !command.get("allocation_id").toString().equals(payload.path("allocationId").asString())
            || !Objects.equals(command.get("owner"),allocation.get("owner")) || !Objects.equals(command.get("epoch"),allocation.get("epoch"))
            || !id.toString().equals(payload.path("commandId").asString()) || !id.toString().equals(payload.path("movementId").asString())
            || !session.get("world_id").toString().equals(payload.path("worldId").asString()) || !session.get("journal_generation").toString().equals(payload.path("journalGeneration").asString()))return "INTENT_CONFLICT";
        for(String field:List.of("movementId","siteId","loadId","source","destination","quantity","zoneId"))if(!movement.path(field).equals(payload.path(field)))return "INTENT_CONFLICT";
        if(allocation.get("state").equals("CANCELLED") && !item.path("state").asString().equals("REJECTED_BEFORE_EXECUTION"))return "INTENT_CONFLICT";
        if(item.path("state").asString().equals("COMPLETED") && (!item.path("execution_sequence").isIntegralNumber() || item.path("execution_sequence").asLong()<1))return "INTENT_CONFLICT";
        if(!item.path("state").asString().equals("COMPLETED") && !item.path("execution_sequence").isNull())return "INTENT_CONFLICT";
        return "MATCHED";
    }
    private boolean commandsReconciled(DSLContext sql,UUID id) {
        return !sql.fetchOne("""
            SELECT EXISTS(
              SELECT 1 FROM command_journal c
              LEFT JOIN restore_inventory_findings f ON f.restore_id=? AND f.command_id=c.command_id
              LEFT JOIN restore_physical_findings p ON p.restore_id=? AND p.command_id=c.command_id
              LEFT JOIN restore_absence_proofs a ON a.restore_id=? AND a.command_id=c.command_id
              WHERE c.state='QUARANTINED' OR
                (f.command_id IS NULL AND (a.command_id IS NULL OR c.accepted_ever OR c.state='COMPLETED')) OR
                (f.command_id IS NOT NULL AND (f.state<>'MATCHED' OR NOT c.accepted_ever OR
                  (p.command_id IS NOT NULL AND (p.state<>'MATCHED' OR c.state<>'COMPLETED' OR (c.evidence->>'executionSequence')::bigint IS DISTINCT FROM p.execution_sequence)) OR
                  (p.command_id IS NULL AND (c.state NOT IN ('ACCEPTED_BY_SIMULATOR','EXECUTING','REJECTED_BEFORE_EXECUTION') OR
                    (f.simulator_state='COMPLETED') OR (f.simulator_state='REJECTED_BEFORE_EXECUTION' AND c.state<>'REJECTED_BEFORE_EXECUTION') OR
                    (f.simulator_state<>'REJECTED_BEFORE_EXECUTION' AND c.state='REJECTED_BEFORE_EXECUTION')))))
            )
            """,id,id,id).get(0,Boolean.class);
    }
    private String frontierError(Record row,JsonNode frontier) {
        String error=worldError(row,frontier);if(error!=null)return error;
        if(!row.get("inventory_complete",Boolean.class) || row.get("unresolved_count",Integer.class)>0 || row.get("inventory_unresolved",Integer.class)>0)return "RESTORE_SCAN_REQUIRED";
        if(!frontier.path("totalCommands").isIntegralNumber() || frontier.path("totalCommands").asLong(-1)!=row.get("inventory_total",Integer.class))return "PHYSICAL_INVENTORY_CHANGED";
        if(frontier.path("journalHighWater").asLong()!=row.get("scan_cursor",Long.class))return "RESTORE_SCAN_REQUIRED";return null;
    }
    private static String verificationHash(DSLContext sql,UUID id) {
        return JsonSupport.hash(Database.json(sql,"""
            SELECT jsonb_build_object(
              'commands',(SELECT coalesce(jsonb_agg(encode(sha256(convert_to(to_jsonb(c)::text,'UTF8')),'hex') ORDER BY command_id),'[]'::jsonb) FROM (SELECT command_id,allocation_id,movement_id,site_id,owner,epoch,payload,payload_hash FROM command_journal) c),
              'allocations',(SELECT coalesce(jsonb_agg(encode(sha256(convert_to(to_jsonb(a)::text,'UTF8')),'hex') ORDER BY allocation_id),'[]'::jsonb) FROM (SELECT allocation_id,movement_id,site_id,owner,epoch,movement,payload_hash FROM movement_allocations) a),
              'physical',(SELECT coalesce(jsonb_agg(evidence_hash ORDER BY command_id),'[]'::jsonb) FROM restore_physical_findings WHERE restore_id=?),
              'inventory',(SELECT coalesce(jsonb_agg(evidence_hash ORDER BY command_id),'[]'::jsonb) FROM restore_inventory_findings WHERE restore_id=?),
              'absence',(SELECT coalesce(jsonb_agg(evidence_hash ORDER BY command_id),'[]'::jsonb) FROM restore_absence_proofs WHERE restore_id=?))
            """,id,id,id));
    }
    private Record session(UUID id){var row=database.fetchOne("SELECT * FROM restore_sessions WHERE restore_id=?",id);if(row==null)throw Problem.missing();return row;}
    private static boolean terminal(Record row){return Set.of("QUARANTINED","VERIFIED","RELEASED").contains(row.get("state",String.class));}
    private String compare(DSLContext sql,Record session,JsonNode physical) {
        UUID id=Database.uuid(physical,"command_id");String site=physical.path("site_id").asString();
        var command=sql.fetchOne("SELECT * FROM command_journal WHERE site_id=? AND command_id=?",site,id);
        var allocation=sql.fetchOne("SELECT * FROM movement_allocations WHERE site_id=? AND movement_id=?",site,id);
        if(command==null || allocation==null)return "MISSING_BUSINESS_CONTEXT";
        JsonNode payload=JsonSupport.read(command.get("payload").toString()),movement=JsonSupport.read(allocation.get("movement").toString());
        if(!JsonSupport.hash(payload).equals(command.get("payload_hash")) || !JsonSupport.hash(movement).equals(allocation.get("payload_hash"))
            || !command.get("allocation_id").equals(allocation.get("allocation_id")) || !Objects.equals(command.get("owner"),allocation.get("owner"))
            || !id.equals(command.get("movement_id")) || !command.get("allocation_id").toString().equals(payload.path("allocationId").asString())
            || !Objects.equals(command.get("epoch"),allocation.get("epoch")) || allocation.get("state").equals("CANCELLED") || command.get("state").equals("REJECTED_BEFORE_EXECUTION")
            || !session.get("world_id").toString().equals(payload.path("worldId").asString()) || !session.get("journal_generation").toString().equals(payload.path("journalGeneration").asString())
            || payload.path("expectedLoadVersion").asLong(-1)!=physical.path("before_version").asLong())return "INTENT_CONFLICT";
        for(var field:Map.of("commandId","command_id","movementId","movement_id","siteId","site_id","loadId","load_id","source","source","destination","destination","quantity","quantity").entrySet()) {
            if(!payload.path(field.getKey()).equals(physical.path(field.getValue())))return "INTENT_CONFLICT";
            if(!field.getKey().equals("commandId") && !movement.path(field.getKey()).equals(payload.path(field.getKey())))return "INTENT_CONFLICT";
        }
        if(command.get("state").equals("COMPLETED")) {
            if(command.get("evidence")==null)return "INTENT_CONFLICT";
            JsonNode evidence=JsonSupport.read(command.get("evidence").toString());
            if(evidence.path("executionSequence").asLong(-1)!=physical.path("sequence").asLong())return "INTENT_CONFLICT";
        }
        return "MATCHED";
    }
    private String worldError(Record session,JsonNode world) {
        if(!world.path("completeHistory").asBoolean(false))return "PHYSICAL_HISTORY_UNAVAILABLE";
        if(!session.get("world_id").toString().equals(world.path("worldId").asString()) || !session.get("journal_generation").toString().equals(world.path("journalGeneration").asString()))return "PHYSICAL_WORLD_CHANGED";
        if(!world.path("journalHighWater").isIntegralNumber() || world.path("journalHighWater").asLong()<0)return "PHYSICAL_HISTORY_PROTOCOL";
        if(world.path("journalHighWater").asLong()<session.get("checkpoint_high_water",Long.class))return "PHYSICAL_HISTORY_REWOUND";
        try{var observed=OffsetDateTime.parse(world.path("observedAt").asString()).toInstant();if(observed.isAfter(clock.instant().plusSeconds(1)) || observed.isBefore(clock.instant().minusSeconds(5)))return "PHYSICAL_OBSERVATION_STALE";}
        catch(RuntimeException invalid){return "PHYSICAL_HISTORY_PROTOCOL";}return null;
    }
    private Record lock(DSLContext sql,UUID id,long version) {
        if(!Database.workersMayWrite(sql))throw new Problem(503,"WORKERS_PAUSED","Restoration evidence is paused for the checkpoint.");
        if(!sql.fetchOne("SELECT restoration_required FROM service_control WHERE singleton").get(0,Boolean.class))throw Problem.conflict("RESTORE_HOLD_MISSING","Restore evidence requires the durable dispatch hold.");
        var row=sql.fetchOne("SELECT * FROM restore_sessions WHERE restore_id=? FOR UPDATE",id);
        if(row==null)throw Problem.missing();if(row.get("version",Long.class)!=version)throw Problem.conflict("RESTORE_VERSION_CONFLICT","Another recovery step advanced this session.");return row;
    }
    private JsonNode quarantine(DSLContext sql,Record session,String error) {
        UUID id=session.get("restore_id",UUID.class);
        sql.execute("UPDATE restore_sessions SET state='QUARANTINED',last_error=?,version=version+1 WHERE restore_id=?",error,id);
        audit(sql,id,session.get("actor",String.class),"restore-quarantined",session.get("reason",String.class),"QUARANTINED",Map.of("reason",error,"beforeVersion",session.get("version"),"afterVersion",session.get("version",Long.class)+1));return view(sql,id);
    }
    private static String routesHash(DSLContext sql) {return JsonSupport.hash(Database.json(sql,"SELECT jsonb_agg(to_jsonb(r) ORDER BY site_id,zone_id) FROM zone_routes r;"));}
    private void audit(DSLContext sql,UUID id,String actor,String action,String reason,String outcome,Object detail) {
        var versions=(Map<?,?>)detail;
        sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,outcome,before_version,after_version,detail,occurred_at) VALUES (?,'site-a',?,?,?,?,?,?,?,?::jsonb,?::timestamptz)",UUID.randomUUID(),actor,action,id.toString(),reason,outcome,versions.get("beforeVersion"),versions.get("afterVersion"),JsonSupport.write(detail),now());
    }
    public JsonNode get(UUID id){return view(database,id);}
    private static JsonNode view(DSLContext sql,UUID id){return Database.json(sql,"SELECT jsonb_build_object('restoreId',restore_id,'checkpointName',checkpoint_name,'state',state,'version',version,'checkpointAt',checkpoint_at,'checkpointHighWater',checkpoint_high_water,'observedHighWater',observed_high_water,'scanCursor',scan_cursor,'scannedCount',scanned_count,'unresolvedCount',unresolved_count,'inventoryCount',inventory_count,'inventoryTotal',inventory_total,'inventoryComplete',inventory_complete,'inventoryUnresolved',inventory_unresolved,'absenceCount',(SELECT count(*) FROM restore_absence_proofs p WHERE p.restore_id=s.restore_id),'proofHash',verification_hash,'lastError',last_error,'startedAt',started_at,'verifiedAt',verified_at,'releasedAt',released_at) FROM restore_sessions s WHERE restore_id=?",id);}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
