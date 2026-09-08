package dev.cutover.execution;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Database;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.DeliveryFailure;
import dev.cutover.platform.messaging.MessageHandler;
import java.util.Set;
import org.jooq.DSLContext;

/** Only adapter assignment grants authority to create an execution-owned task. */
public final class ExecutionMessages implements MessageHandler {
    private static final Set<String> TYPES=Set.of("MovementAssigned.v1","MovementCancelled.v1","CommandAccepted.v1","MovementCompleted.v1","CommandOutcomeUnknown.v1","CommandRejected.v1","ZoneOwnershipChanged.v1");
    @Override public void apply(DSLContext sql,Events.Envelope event) {
        if(!TYPES.contains(event.eventType()))throw DeliveryFailure.permanent("UNSUPPORTED_EVENT_TYPE");
        if(event.eventType().equals("ZoneOwnershipChanged.v1"))return;
        var allocation=event.payload();
        if(!event.aggregateType().equals("movement") || !event.aggregateId().toString().equals(allocation.path("movementId").asString()))throw DeliveryFailure.permanent("MOVEMENT_ID_MISMATCH");
        if(!allocation.path("movement").path("product").asString().equals("fulfilment") || !allocation.path("owner").asString().equals("execution-service"))return;
        var movement=allocation.required("movement");
        try{Contracts.validate("movement.v1",JsonSupport.write(movement));}catch(IllegalArgumentException invalid){throw DeliveryFailure.permanent("MOVEMENT_CONTRACT");}
        if(!event.siteId().equals(movement.path("siteId").asString()) || !event.siteId().equals(allocation.path("siteId").asString()) || !event.aggregateId().toString().equals(movement.path("movementId").asString()))throw DeliveryFailure.permanent("MOVEMENT_ID_MISMATCH");
        if(!allocation.path("epoch").isIntegralNumber() || allocation.path("epoch").asLong()<0 || (event.eventType().equals("MovementAssigned.v1") && !allocation.path("state").asString().equals("ASSIGNED")))throw DeliveryFailure.permanent("INVALID_TASK_ASSIGNMENT");
        var existing=sql.fetchOne("SELECT allocation_id,payload_hash,epoch FROM execution_tasks WHERE site_id=? AND movement_id=? FOR UPDATE",event.siteId(),event.aggregateId());
        var allocationId=Database.uuid(allocation,"allocationId");long epoch=allocation.path("epoch").asLong();
        if(existing!=null && (!existing.get("allocation_id").equals(allocationId) || !existing.get("payload_hash").equals(JsonSupport.hash(movement)) || existing.get("epoch",Long.class)!=epoch))throw DeliveryFailure.permanent("IMMUTABLE_TASK_ASSIGNMENT");
        if(existing==null && !Set.of("MovementAssigned.v1","MovementCancelled.v1").contains(event.eventType()))throw DeliveryFailure.pending("ASSIGNMENT_MISSING");
        if(event.eventType().equals("MovementAssigned.v1")){
            if(existing!=null)return;
            sql.execute("INSERT INTO execution_tasks(site_id,movement_id,allocation_id,epoch,zone_id,movement,payload_hash,priority,eligible_at) VALUES (?,?,?,?,?,?::jsonb,?,?,?::timestamptz)",event.siteId(),event.aggregateId(),allocationId,epoch,movement.path("zoneId").asString(),JsonSupport.write(movement),JsonSupport.hash(movement),movement.path("priority").asInt()*100,movement.path("eligibleAt").asString());
        }else if(event.eventType().equals("MovementCompleted.v1")){
            var command=allocation.path("command");
            if(!command.path("state").asString().equals("COMPLETED") || command.path("evidence").path("executionSequence").asLong()<1
                    || !command.path("commandId").asString().equals(event.aggregateId().toString()) || !command.path("siteId").asString().equals(event.siteId())
                    || command.path("payload").path("quantity").asInt()!=movement.path("quantity").asInt())throw DeliveryFailure.permanent("UNVERIFIED_COMPLETION");
            sql.execute("UPDATE execution_tasks SET state='COMPLETED',version=version+1,completed_at=now(),lease_id=NULL,lease_until=NULL,last_error=NULL WHERE site_id=? AND movement_id=? AND state NOT IN ('COMPLETED','CANCELLED')",event.siteId(),event.aggregateId());
        }else if(event.eventType().equals("MovementCancelled.v1")){
            sql.execute("UPDATE execution_tasks SET state='CANCELLED',version=version+1,completed_at=now(),lease_id=NULL,lease_until=NULL,last_error=NULL WHERE site_id=? AND movement_id=? AND state NOT IN ('COMPLETED','CANCELLED')",event.siteId(),event.aggregateId());
        }
    }
}
