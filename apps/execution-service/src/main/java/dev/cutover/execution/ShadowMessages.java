package dev.cutover.execution;

import dev.cutover.platform.Contracts;
import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.DeliveryFailure;
import dev.cutover.platform.messaging.MessageHandler;
import org.jooq.DSLContext;

/** The shadow process stores comparisons in an isolated owner database and never creates execution tasks. */
public final class ShadowMessages implements MessageHandler {
    @Override public void apply(DSLContext sql,Events.Envelope event){
        if(!event.eventType().equals("SchedulingSnapshotRecorded.v1") || !event.aggregateType().equals("scheduling-round"))throw DeliveryFailure.permanent("UNSUPPORTED_OBSERVATION");
        var body=event.payload();var input=body.path("input");
        try{Contracts.validate("scheduling-observation.v1",JsonSupport.write(body));}catch(IllegalArgumentException invalid){throw DeliveryFailure.permanent("OBSERVATION_CONTRACT");}
        if(!event.aggregateId().toString().equals(body.path("roundId").asString()) || !event.siteId().equals(input.path("siteId").asString()))throw DeliveryFailure.permanent("SNAPSHOT_IDENTITY_MISMATCH");
        try{Contracts.validate("scheduling-snapshot.v1",JsonSupport.write(input));}catch(IllegalArgumentException invalid){throw DeliveryFailure.permanent("SNAPSHOT_CONTRACT");}
        String hash=JsonSupport.hash(input);
        if(!hash.equals(body.path("inputHash").asString()))throw DeliveryFailure.permanent("SNAPSHOT_HASH_MISMATCH");
        var legacy=body.path("legacyProposal");var proposal=SchedulingDecision.propose(input);
        var existing=sql.fetchOne("SELECT input_hash,legacy_proposal FROM shadow_comparisons WHERE round_id=? FOR UPDATE",event.aggregateId());
        if(existing!=null){
            if(!existing.get("input_hash").equals(hash) || !JsonSupport.read(existing.get("legacy_proposal").toString()).equals(legacy))throw DeliveryFailure.permanent("SNAPSHOT_ROUND_CONFLICT");
            return;
        }
        sql.fetchOne("SELECT singleton FROM shadow_capacity WHERE singleton FOR UPDATE");
        int bytes=JsonSupport.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(sql.execute("UPDATE shadow_capacity SET retained_count=retained_count+1,retained_bytes=retained_bytes+ ? WHERE singleton AND retained_count<4096 AND retained_bytes+ ? <=67108864",bytes,bytes)!=1)throw DeliveryFailure.pending("COMPARISON_CAPACITY");
        sql.execute("INSERT INTO shadow_comparisons(round_id,site_id,input_hash,input,legacy_proposal,execution_proposal,matches,rule_version) VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?,1)",event.aggregateId(),event.siteId(),hash,JsonSupport.write(input),JsonSupport.write(legacy),JsonSupport.write(proposal),legacy.equals(proposal));
    }
}
