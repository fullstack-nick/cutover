package dev.cutover.returns;

import dev.cutover.platform.*;
import dev.cutover.platform.messaging.*;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

/** Broker-authenticated adapter observations update only this product's known intents. */
public final class ReturnsMessages implements MessageHandler {
    private static final Set<String> TYPES=Set.of("MovementAssigned.v1","CommandAccepted.v1","MovementCompleted.v1","CommandOutcomeUnknown.v1","CommandRejected.v1");
    private final ReceiptService receipts;
    public ReturnsMessages(ReceiptService receipts){this.receipts=receipts;}
    @Override public void apply(DSLContext sql,Events.Envelope event){
        if(!event.source().equals("equipment-adapter"))throw DeliveryFailure.permanent("UNTRUSTED_EVENT_SOURCE");
        if(event.eventType().equals("ZoneOwnershipChanged.v1"))return;
        var allocation=event.payload();var movement=allocation.path("movement");
        if(!movement.path("product").asString().equals("returns"))return;
        if(!TYPES.contains(event.eventType()))throw DeliveryFailure.permanent("UNSUPPORTED_RETURN_EVENT");
        if(!event.aggregateType().equals("movement") || !allocation.path("owner").asString().equals("returns-service")
            || !allocation.path("siteId").asString().equals(event.siteId()) || !movement.path("siteId").asString().equals(event.siteId())
            || !allocation.path("movementId").asString().equals(event.aggregateId().toString())
            || !movement.path("movementId").asString().equals(event.aggregateId().toString()) || !movement.path("zoneId").asString().equals("returns"))
            throw DeliveryFailure.permanent("RETURN_ASSIGNMENT_IDENTITY");
        try{Contracts.validate("movement.v1",JsonSupport.write(movement));}catch(IllegalArgumentException invalid){throw DeliveryFailure.permanent("MOVEMENT_CONTRACT");}
        if(!allocation.path("epoch").isIntegralNumber() || allocation.path("epoch").asLong()<0)throw DeliveryFailure.permanent("RETURN_ASSIGNMENT_EPOCH");
        UUID allocationId;
        try{allocationId=Database.uuid(allocation,"allocationId");}catch(Problem invalid){throw DeliveryFailure.permanent("RETURN_ALLOCATION_ID");}
        var intent=sql.fetchOne("SELECT receipt_id,payload_hash FROM return_movements WHERE site_id=? AND movement_id=?",event.siteId(),event.aggregateId());
        if(intent==null)throw DeliveryFailure.pending("RECEIPT_INTENT_MISSING");
        if(!intent.get("payload_hash",String.class).equals(JsonSupport.hash(movement)))throw DeliveryFailure.permanent("IMMUTABLE_RETURN_INTENT");
        var task=sql.fetchOne("SELECT allocation_id,epoch FROM return_tasks WHERE site_id=? AND movement_id=?",event.siteId(),event.aggregateId());
        if(task!=null && (!task.get("allocation_id").equals(allocationId) || task.get("epoch",Long.class)!=allocation.path("epoch").asLong()))
            throw DeliveryFailure.permanent("IMMUTABLE_RETURN_ASSIGNMENT");
        if(event.eventType().equals("MovementAssigned.v1")){
            if(!allocation.path("state").asString().equals("ASSIGNED"))throw DeliveryFailure.permanent("RETURN_ASSIGNMENT_STATE");
            if(task!=null)return;
            sql.fetchOne("SELECT receipt_id FROM receipts WHERE site_id=? AND receipt_id=? FOR UPDATE",event.siteId(),intent.get("receipt_id"));
            sql.execute("INSERT INTO return_tasks(site_id,movement_id,allocation_id,epoch) VALUES (?,?,?,?)",event.siteId(),event.aggregateId(),allocationId,allocation.path("epoch").asLong());
            sql.execute("UPDATE return_movements SET state='ASSIGNED' WHERE movement_id=? AND state='REQUESTED'",event.aggregateId());
            sql.execute("UPDATE receipts SET state='SORTING',version=version+1 WHERE receipt_id=? AND state='REGISTERED'",intent.get("receipt_id"));
        }else{
            if(task==null)throw DeliveryFailure.pending("RETURN_ASSIGNMENT_MISSING");
            if(event.eventType().equals("MovementCompleted.v1"))receipts.complete(sql,event.siteId(),event.aggregateId(),allocation.path("command"));
            else if(Set.of("CommandOutcomeUnknown.v1","CommandRejected.v1").contains(event.eventType())){
                sql.fetchOne("SELECT receipt_id FROM receipts WHERE site_id=? AND receipt_id=? FOR UPDATE",event.siteId(),intent.get("receipt_id"));
                int changed=sql.execute("UPDATE return_tasks SET state='RECONCILIATION_REQUIRED',last_error=?,version=version+1,lease_id=NULL,lease_until=NULL WHERE site_id=? AND movement_id=? AND state NOT IN ('COMPLETED','RECONCILIATION_REQUIRED')",event.eventType(),event.siteId(),event.aggregateId());
                if(changed>0)sql.execute("UPDATE receipts SET state='RECONCILIATION_REQUIRED',version=version+1 WHERE receipt_id=? AND state NOT IN ('COMPLETED','RECONCILIATION_REQUIRED')",intent.get("receipt_id"));
            }
        }
    }
}
