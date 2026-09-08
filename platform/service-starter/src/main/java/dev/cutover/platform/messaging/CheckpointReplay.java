package dev.cutover.platform.messaging;

import dev.cutover.platform.*;
import java.util.*;
import org.jooq.DSLContext;

/** Replays checkpoint-qualified bytes using their original identities, without rewriting the outbox. */
public final class CheckpointReplay {
    public record Event(UUID eventId,String envelopeSha256) {}
    private final DSLContext database;private final String source;private final OutboxRelay.Publisher publisher;
    public CheckpointReplay(DSLContext database,String source,OutboxRelay.Publisher publisher){this.database=database;this.source=source;this.publisher=publisher;}
    public List<UUID> replay(List<Event> events) {
        if(events==null || events.isEmpty() || events.size()>32 || new HashSet<>(events.stream().map(Event::eventId).toList()).size()!=events.size())throw Problem.invalid("Replay 1–32 distinct checkpoint event identities.");
        var control=database.fetchOne("SELECT intake_paused AND dispatch_paused FROM service_control WHERE singleton");
        if(!control.get(0,Boolean.class))throw Problem.conflict("REPLAY_REQUIRES_HOLD","Checkpoint replay requires closed intake and dispatch.");
        var rows=new ArrayList<org.jooq.Record>();
        for(var event:events){
            if(event.eventId()==null || event.envelopeSha256()==null || !event.envelopeSha256().matches("[a-f0-9]{64}"))throw Problem.invalid("Replay requires checkpoint event checksums.");
            var row=database.fetchOne("SELECT event_id,source,event_type,envelope::text AS body,encode(sha256(convert_to(envelope::text,'UTF8')),'hex') AS hash FROM outbox WHERE event_id=?",event.eventId());
            if(row==null || !source.equals(row.get("source")) || !event.envelopeSha256().equals(row.get("hash")))throw Problem.conflict("REPLAY_CHECKPOINT_MISMATCH","Retained owner bytes do not match the selected checkpoint event.");
            rows.add(row);
        }
        var confirmed=new ArrayList<UUID>();
        for(var row:rows){UUID id=row.get("event_id",UUID.class);publisher.publish("cutover."+source+".v1",row.get("event_type",String.class),id,row.get("body",String.class));confirmed.add(id);}
        return List.copyOf(confirmed);
    }
}
