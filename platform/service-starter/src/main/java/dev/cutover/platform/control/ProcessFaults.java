package dev.cutover.platform.control;

import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.DeliveryHooks;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** One-shot process loss at a committed boundary; enabled only in the explicit local test profile. */
public final class ProcessFaults implements DeliveryHooks {
    private final DSLContext database;
    private final Clock clock;
    private final IntConsumer terminate;
    public ProcessFaults(DSLContext database,Clock clock) { this(database,clock,code->Runtime.getRuntime().halt(code)); }
    /** Tests supply a throwing terminator; runtime always uses the fixed process exit above. */
    public ProcessFaults(DSLContext database,Clock clock,IntConsumer terminate) { this.database=database;this.clock=clock;this.terminate=terminate; }
    @Override public void reached(String checkpoint,UUID eventId) {
        if (eventId==null || !Set.of("AFTER_BUSINESS_COMMIT","AFTER_BROKER_CONFIRM","AFTER_EFFECT_BEFORE_ACK").contains(checkpoint)) return;
        // An ordinary delivery must not dirty the worker-control row merely to discover no fault.
        // Read on every hook (no cache); armed faults still recheck their exact event under locks.
        if (!database.fetchOne("SELECT EXISTS(SELECT FROM process_faults WHERE checkpoint=? AND remaining=1)",checkpoint).get(0,Boolean.class)) return;
        boolean fire=database.transactionResult(configuration -> {
            var sql=DSL.using(configuration);
            if (!Database.workersMayWrite(sql)) return false;
            boolean receiving=checkpoint.equals("AFTER_EFFECT_BEFORE_ACK");
            String table=receiving?"inbox":"outbox", time=receiving?"received_at":"created_at";
            var row=sql.fetchOne("SELECT f.* FROM process_faults f JOIN "+table+" e ON e.site_id=f.site_id WHERE e.event_id=? AND f.checkpoint=? AND f.remaining=1 AND (f.event_selector=? OR (f.event_selector IS NULL AND e."+time+">=f.armed_at)) AND (f.event_type IS NULL OR f.event_type=e.envelope->>'eventType') FOR UPDATE OF f SKIP LOCKED",eventId,checkpoint,eventId);
            if (row==null) return false;
            long before=row.get("version",Long.class);
            sql.execute("UPDATE process_faults SET remaining=0,fired_at=?::timestamptz,fired_event_id=?,version=version+1 WHERE fault_id=?",OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),eventId,row.get("fault_id"));
            sql.execute("INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail) VALUES (?,?,?,'process-fault-fired',?,?,?,?, 'FIRED',?::jsonb)",
                    UUID.randomUUID(),row.get("site_id"),row.get("actor"),row.get("fault_id").toString(),row.get("reason"),before,before+1,JsonSupport.write(Map.of("checkpoint",checkpoint,"eventId",eventId)));
            return true;
        });
        // No transaction or lock survives this line. Consumption commits first to prevent a restart loop.
        if (fire) terminate.accept(73);
    }
}
