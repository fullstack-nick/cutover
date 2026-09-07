package dev.cutover.core;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.Idempotency;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.messaging.RetryDelay;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Durable intent -> adapter fence -> one inventory release transaction. No database lock crosses HTTP. */
public final class OrderCancellations {
    private final DSLContext database;
    private final DispatchPort adapter;
    private final Clock clock;
    public OrderCancellations(DSLContext database,DispatchPort adapter,Clock clock) { this.database=database;this.adapter=adapter;this.clock=clock; }

    public JsonNode cancel(String actor,String site,UUID order,String key,JsonNode request) {
        try { Contracts.validate("reconciliation-request.v1",JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Cancellation requires an expected order version and a reason of 8–500 characters."); }
        String reason=request.path("reason").asString().trim();
        if (reason.length()<8) throw Problem.invalid("Explain why the wholly unstarted order should be cancelled.");
        JsonNode intent=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"cancel-order-intent",key,Map.of("orderId",order,"request",request),()-> {
                requireWritable(sql);
                sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
                var row=sql.fetchOne("SELECT * FROM orders WHERE site_id=? AND order_id=? FOR UPDATE",site,order);
                if (row==null) throw Problem.missing();
                long before=row.get("version",Long.class);
                if (before!=request.path("expectedVersion").asLong()) throw Problem.conflict("VERSION_CONFLICT","The order changed; inspect its latest movement evidence before cancelling.");
                if (!Set.of("ACCEPTED","RESERVED").contains(row.get("state",String.class)) || row.get("cancellation_pending",Boolean.class)
                        || sql.fetchExists(sql.selectOne().from("reservations").where("site_id=? AND order_id=? AND state<>'RESERVED'",site,order)))
                    throw Problem.conflict("ORDER_NOT_UNSTARTED","Only a wholly unstarted order without another pending cancellation can be cancelled.");
                UUID id=UUID.randomUUID();
                var movements=JsonSupport.MAPPER.createArrayNode();
                for (var movement:sql.fetch("SELECT movement FROM movement_intents WHERE site_id=? AND order_id=? ORDER BY movement_id",site,order)) movements.add(JsonSupport.read(movement.get(0).toString()));
                if (movements.isEmpty()) throw Problem.conflict("NO_RESERVED_MOVEMENT","A terminal shortage has no active reservations to cancel.");
                var fence=JsonSupport.MAPPER.createObjectNode();
                fence.put("cancellationId",id.toString());fence.put("orderId",order.toString());fence.put("siteId",site);fence.put("actor",actor);fence.put("reason",reason);fence.set("movements",movements);
                sql.execute("INSERT INTO order_cancellations(cancellation_id,site_id,order_id,actor,reason,request,next_attempt_at) VALUES (?,?,?,?,?,?::jsonb,?::timestamptz)",id,site,order,actor,reason,JsonSupport.write(fence),now());
                sql.execute("UPDATE orders SET cancellation_pending=true,version=version+1 WHERE order_id=?",order);
                JsonNode response=JsonSupport.MAPPER.valueToTree(Map.of("cancellationId",id));
                Events.append(sql,site,"legacy-core","order",order,before+1,"OrderCancellationRequested.v1",id,response);
                audit(sql,site,actor,"order-cancellation-request",order,reason,before,before+1,"RECORDED",response);
                return response;
            });
        });
        UUID id=Database.uuid(intent,"cancellationId");
        var retained=database.fetchOne("SELECT state,response FROM order_cancellations WHERE cancellation_id=? AND site_id=?",id,site);
        if (Set.of("CANCELLED","DENIED").contains(retained.get("state",String.class))) return finalResponse(retained);
        // An explicit retry after a transport pause is itself audited. Completed responses never change.
        database.transaction(configuration -> {
            var sql=DSL.using(configuration);requireWritable(sql);
            var paused=sql.fetchOne("UPDATE order_cancellations SET state='PENDING',attempts=0,next_attempt_at=?::timestamptz,last_error=NULL,version=version+1 WHERE cancellation_id=? AND site_id=? AND state='PAUSED' RETURNING version",now(),id,site);
            if (paused!=null) audit(sql,site,actor,"order-cancellation-retry",order,reason,paused.get(0,Long.class)-1,paused.get(0,Long.class),"RECORDED",intent);
        });
        attempt(id);
        var result=database.fetchOne("SELECT state,response FROM order_cancellations WHERE cancellation_id=? AND site_id=?",id,site);
        return finalResponse(result);
    }
    private static JsonNode finalResponse(Record result) {
        String state=result.get("state",String.class);
        if (state.equals("CANCELLED")) return JsonSupport.read(result.get("response").toString());
        if (state.equals("DENIED")) {
            JsonNode denial=JsonSupport.read(result.get("response").toString());
            throw Problem.conflict(denial.path("code").asString(),denial.path("detail").asString());
        }
        throw new Problem(503,"CANCELLATION_PENDING","The cancellation intent is durable but has no final fence result. Inspect the order and retry this same request key after recovery.");
    }

    /** A new browser session can resume a paused intent without knowing its original intake key. */
    public JsonNode retry(String actor,String site,UUID order,UUID id,String key,JsonNode request) {
        try { Contracts.validate("reconciliation-request.v1",JsonSupport.write(request)); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Retry requires the cancellation version and a reason of 8–500 characters."); }
        String reason=request.path("reason").asString().trim();
        if (reason.length()<8) throw Problem.invalid("Explain what was repaired before retrying the cancellation fence.");
        JsonNode recorded=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            return Idempotency.execute(sql,actor,site,"resume-order-cancellation",key,Map.of("orderId",order,"cancellationId",id,"request",request),()-> {
                requireWritable(sql);
                var row=sql.fetchOne("SELECT * FROM order_cancellations WHERE site_id=? AND order_id=? AND cancellation_id=? FOR UPDATE",site,order,id);
                if(row==null)throw Problem.missing();
                long before=row.get("version",Long.class);
                if(before!=request.path("expectedVersion").asLong())throw Problem.conflict("VERSION_CONFLICT","The cancellation changed; refresh its evidence before retrying.");
                if(!"PAUSED".equals(row.get("state",String.class)))throw Problem.conflict("CANCELLATION_NOT_PAUSED","Only an exhausted cancellation can receive a new retry budget.");
                sql.execute("UPDATE order_cancellations SET state='PENDING',version=version+1,attempts=0,last_error=NULL,next_attempt_at=?::timestamptz WHERE cancellation_id=?",now(),id);
                JsonNode response=JsonSupport.MAPPER.valueToTree(Map.of("cancellationId",id,"orderId",order,"state","PENDING","version",before+1));
                audit(sql,site,actor,"order-cancellation-retry",order,reason,before,before+1,"RECORDED",response);
                return response;
            });
        });
        // The response is the recorded retry request, not a promise that the network call succeeded.
        // The regular bounded worker resumes it and GET order supplies its durable outcome.
        return recorded;
    }

    public int poll() {
        var ids=database.fetch("SELECT cancellation_id FROM order_cancellations WHERE state='PENDING' AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz) ORDER BY next_attempt_at,cancellation_id LIMIT 8",now(),now()).getValues(0,UUID.class);
        for(UUID id:ids) attempt(id);return ids.size();
    }
    private void attempt(UUID id) {
        Record claimed=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            if (!writable(sql)) return null;
            var row=sql.fetchOne("SELECT * FROM order_cancellations WHERE cancellation_id=? AND state='PENDING' AND next_attempt_at<=?::timestamptz AND (lease_until IS NULL OR lease_until<?::timestamptz) FOR UPDATE SKIP LOCKED",id,now(),now());
            if(row==null)return null;
            UUID lease=UUID.randomUUID();
            sql.execute("UPDATE order_cancellations SET lease_id=?,lease_until=?::timestamptz,attempts=attempts+1 WHERE cancellation_id=?",lease,now().plusSeconds(10),id);
            row.set(row.field("lease_id",UUID.class),lease);return row;
        });
        if(claimed==null)return;
        try {
            JsonNode request=JsonSupport.read(claimed.get("request").toString());
            JsonNode fence=adapter.cancellation(claimed.get("site_id",String.class),request);
            validateFence(request,fence);
            finish(claimed,fence,null);
        } catch (Problem failure) {
            if (failure.status()==409 && Set.of("MOVEMENT_STARTED","MOVEMENT_STARTED_OR_UNKNOWN","WORLD_MISMATCH","ROUTE_UNCERTAIN").contains(failure.code())) finish(claimed,null,failure.code());
            else defer(claimed,failure.code());
        } catch (RuntimeException unavailable) { defer(claimed,"CANCELLATION_DEPENDENCY_UNAVAILABLE"); }
    }
    private void finish(Record claimed,JsonNode fence,String denial) {
        database.transaction(configuration -> {
            var sql=DSL.using(configuration);requireWritable(sql);
            sql.fetchOne("SELECT * FROM admission WHERE singleton FOR UPDATE");
            UUID id=claimed.get("cancellation_id",UUID.class),orderId=claimed.get("order_id",UUID.class);String site=claimed.get("site_id",String.class);
            var order=sql.fetchOne("SELECT * FROM orders WHERE site_id=? AND order_id=? FOR UPDATE",site,orderId);
            var request=sql.fetchOne("SELECT * FROM order_cancellations WHERE cancellation_id=? FOR UPDATE",id);
            if (!"PENDING".equals(request.get("state",String.class)) || !claimed.get("lease_id").equals(request.get("lease_id"))) return;
            long before=order.get("version",Long.class);
            JsonNode response;
            if (denial==null) {
                var reservations=sql.fetch("SELECT * FROM reservations WHERE site_id=? AND order_id=? ORDER BY sku FOR UPDATE",site,orderId);
                if (reservations.stream().anyMatch(row->!"RESERVED".equals(row.get("state",String.class))))
                    throw Problem.conflict("CANCELLATION_BUSINESS_CONFLICT","The fenced cancellation does not agree with retained reservation state.");
                for (var reservation:reservations) {
                    UUID reservationId=reservation.get("reservation_id",UUID.class);int quantity=reservation.get("quantity",Integer.class);
                    sql.execute("UPDATE stock SET reserved=reserved-?,version=version+1 WHERE site_id=? AND sku=?",quantity,site,reservation.get("sku"));
                    sql.execute("INSERT INTO reservation_releases(reservation_id,site_id,cancellation_id,quantity) VALUES (?,?,?,?)",reservationId,site,id,quantity);
                    sql.execute("UPDATE reservations SET state='RELEASED' WHERE site_id=? AND reservation_id=?",site,reservationId);
                }
                sql.execute("UPDATE movement_intents SET state='CANCELLED' WHERE site_id=? AND order_id=?",site,orderId);
                sql.execute("UPDATE legacy_tasks SET state='CANCELLED',version=version+1,lease_until=NULL,last_error=NULL WHERE site_id=? AND order_id=? AND state<>'COMPLETED'",site,orderId);
                sql.execute("UPDATE orders SET state='CANCELLED',cancellation_pending=false,version=version+1,completed_at=?::timestamptz WHERE order_id=?",now(),orderId);
                sql.execute("UPDATE admission SET active_requests=active_requests-1 WHERE singleton");
                response=JsonSupport.MAPPER.valueToTree(Map.of("cancellationId",id,"orderId",orderId,"state","CANCELLED","version",before+1,"certificate",fence));
            } else {
                sql.execute("UPDATE orders SET cancellation_pending=false,version=version+1 WHERE order_id=?",orderId);
                response=JsonSupport.MAPPER.valueToTree(Map.of("cancellationId",id,"orderId",orderId,"code",denial,"detail","At least one movement is started, investigating or uncertain; no reservations were released."));
            }
            sql.execute("UPDATE order_cancellations SET state=?,version=version+1,response=?::jsonb,lease_until=NULL,lease_id=NULL,last_error=?,finished_at=?::timestamptz WHERE cancellation_id=?",
                    denial==null?"CANCELLED":"DENIED",JsonSupport.write(response),denial,now(),id);
            Events.append(sql,site,"legacy-core","order",orderId,before+1,denial==null?"OrderCancelled.v1":"OrderCancellationDenied.v1",id,response);
            audit(sql,site,request.get("actor",String.class),"order-cancellation-result",orderId,request.get("reason",String.class),before,before+1,denial==null?"CANCELLED":"DENIED",response);
        });
    }
    private void defer(Record claimed,String code) {
        database.transaction(configuration -> {
            var sql=DSL.using(configuration);if(!Database.workersMayWrite(sql))return;
            int attempt=claimed.get("attempts",Integer.class)+1;UUID id=claimed.get("cancellation_id",UUID.class);
            sql.execute("UPDATE order_cancellations SET state=?,version=version+1,last_error=?,next_attempt_at=?::timestamptz,lease_id=NULL,lease_until=NULL WHERE cancellation_id=? AND lease_id=? AND state='PENDING'",
                    attempt>=6?"PAUSED":"PENDING",code,now().plus(RetryDelay.after(id,attempt)),id,claimed.get("lease_id"));
        });
    }
    private static void validateFence(JsonNode request,JsonNode fence) {
        try { Contracts.validate("cancellation-certificate.v1",JsonSupport.write(fence)); }
        catch (IllegalArgumentException invalid) { throw Problem.conflict("INVALID_CANCELLATION_PROOF","The adapter did not return a complete cancellation certificate."); }
        if (!fence.path("state").asString().equals("FENCED") || !fence.path("cancellationId").equals(request.path("cancellationId"))
                || !fence.path("orderId").equals(request.path("orderId")) || !fence.path("siteId").equals(request.path("siteId"))) throw Problem.conflict("INVALID_CANCELLATION_PROOF","The adapter fence identity differs from the pending request.");
        var expected=new HashSet<String>();request.path("movements").forEach(item->expected.add(item.path("movementId").asString()));
        var actual=new HashSet<String>();
        for(JsonNode item:fence.path("movements")) {
            if (!item.path("proof").asString().equals("NEVER_SUBMITTED") || !actual.add(item.path("movementId").asString())) throw Problem.conflict("INVALID_CANCELLATION_PROOF","The fence does not prove each unique movement was never submitted.");
        }
        if (!expected.equals(actual) || !JsonSupport.hash(fence.path("movements")).equals(fence.path("proofHash").asString())) throw Problem.conflict("INVALID_CANCELLATION_PROOF","The complete movement inventory is not covered by the fence.");
    }
    private static boolean writable(DSLContext sql) { return Database.workersMayWrite(sql) && !sql.fetchOne("SELECT critical_storage FROM service_control WHERE singleton").get(0,Boolean.class); }
    private static void requireWritable(DSLContext sql) { if(!writable(sql))throw new Problem(503,"DURABILITY_PAUSED","Cancellation writes are paused while durable state is frozen or storage is critical."); }
    private static void audit(DSLContext sql,String site,String actor,String action,UUID order,String reason,long before,long after,String outcome,JsonNode detail) {
        sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),site,actor,action,order.toString(),reason,before,after,outcome,JsonSupport.write(detail));
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
}
