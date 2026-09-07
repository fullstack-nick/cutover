package dev.cutover.execution;

import dev.cutover.platform.Events;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.messaging.DurableInbox;
import dev.cutover.testing.DatabaseFixture;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class ExecutionMessagesTest {
    static DatabaseFixture database;
    DurableInbox inbox;
    @BeforeAll static void start(){database=new DatabaseFixture("execution-service");}
    @AfterAll static void stop(){database.close();}
    @BeforeEach void reset(){database.reset();inbox=new DurableInbox(database.sql(),new ExecutionMessages(),Clock.systemUTC(),Set.of("equipment-adapter"),Set.of("site-a"));}
    ObjectNode allocation(UUID movement,String owner){
        var payload=JsonSupport.MAPPER.createObjectNode();payload.put("allocationId",UUID.randomUUID().toString());payload.put("movementId",movement.toString());payload.put("siteId","site-a");payload.put("owner",owner);payload.put("epoch",3);payload.put("state","ASSIGNED");
        var intent=payload.putObject("movement");intent.put("movementId",movement.toString());intent.put("reservationId",movement.toString());intent.put("siteId","site-a");intent.put("product","fulfilment");intent.put("zoneId","ambient");intent.put("loadId",UUID.randomUUID().toString());intent.put("source","ambient-source-01");intent.put("destination","outbound-staging");intent.put("quantity",2);intent.put("priority",5);intent.put("eligibleAt",Instant.now().toString());
        return payload;
    }
    UUID deliver(String source,UUID movement,long version,String type,JsonNode payload){
        var event=new Events.Envelope(UUID.randomUUID(),type,1,Instant.now(),"site-a",source,"movement",movement,version,movement,null,null,payload);
        inbox.receive("cutover."+source+".v1",event.eventId().toString(),JsonSupport.write(event).getBytes(StandardCharsets.UTF_8));return event.eventId();
    }
    int tasks(){return database.sql().fetchOne("SELECT count(*) FROM execution_tasks").get(0,Integer.class);}
    @Test void duplicateAssignmentsCreateOneOwnedTaskAndOtherOwnersRemainObservations(){
        UUID movement=UUID.randomUUID();var payload=allocation(movement,"execution-service");
        deliver("equipment-adapter",movement,1,"MovementAssigned.v1",payload);deliver("equipment-adapter",movement,1,"MovementAssigned.v1",payload);
        UUID legacy=UUID.randomUUID();deliver("equipment-adapter",legacy,1,"MovementAssigned.v1",allocation(legacy,"legacy-core"));
        assertThat(tasks()).isEqualTo(1);assertThat(database.sql().fetchOne("SELECT priority,epoch FROM execution_tasks").intoArray()).containsExactly(500,3L);
        assertThat(database.sql().fetchOne("SELECT count(*) FROM stream_cursor").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void aChangedAssignmentQuarantinesWithoutChangingTheOwnedTask(){
        UUID movement=UUID.randomUUID();var payload=allocation(movement,"execution-service");deliver("equipment-adapter",movement,1,"MovementAssigned.v1",payload);
        var changed=payload.deepCopy();((ObjectNode)changed.path("movement")).put("quantity",3);
        UUID conflict=deliver("equipment-adapter",movement,2,"MovementAssigned.v1",changed);
        assertThat(database.sql().fetchOne("SELECT state FROM inbox WHERE event_id=?",conflict).get(0,String.class)).isEqualTo("QUARANTINED");
        assertThat(database.sql().fetchOne("SELECT movement->>'quantity' FROM execution_tasks").get(0,String.class)).isEqualTo("2");
    }
    @Test void aCompletionMustBelongToTheSameMovementAndOnlyChangesExecutionState(){
        UUID movement=UUID.randomUUID();var payload=allocation(movement,"execution-service");deliver("equipment-adapter",movement,1,"MovementAssigned.v1",payload);
        var completed=payload.deepCopy();completed.put("state","COMPLETED");completed.set("command",JsonSupport.MAPPER.valueToTree(Map.of("commandId",movement,"siteId","site-a","state","COMPLETED","payload",Map.of("quantity",2),"evidence",Map.of("executionSequence",17))));
        deliver("equipment-adapter",movement,2,"MovementCompleted.v1",completed);deliver("equipment-adapter",movement,2,"MovementCompleted.v1",completed);
        assertThat(database.sql().fetchOne("SELECT state,version FROM execution_tasks").intoArray()).containsExactly("COMPLETED",2L);
        assertThat(database.sql().fetchOne("SELECT to_regclass('stock'),to_regclass('reservations')").intoArray()).containsExactly(null,null);
    }
    @Test void ordinaryCorePublicationCannotCreateAnExecutionTask(){
        UUID movement=UUID.randomUUID();deliver("legacy-core",movement,1,"MovementAssigned.v1",allocation(movement,"execution-service"));
        assertThat(tasks()).isZero();assertThat(database.sql().fetchOne("SELECT count(*) FROM delivery_quarantine").get(0,Integer.class)).isEqualTo(1);
    }
}
