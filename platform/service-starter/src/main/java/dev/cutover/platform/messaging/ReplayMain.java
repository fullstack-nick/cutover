package dev.cutover.platform.messaging;

import com.rabbitmq.client.*;
import dev.cutover.platform.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

public final class ReplayMain {
    private ReplayMain() {}
    public static void main(String[] args) {
        try {
            if(!"true".equals(System.getenv("CUTOVER_RESTORATION_MODE")) || args.length!=0)throw new IllegalArgumentException("Use the explicit restoration procedure.");
            byte[] input=System.in.readNBytes(8193);if(input.length>8192)throw new IllegalArgumentException("Replay input exceeds its bound.");
            var node=JsonSupport.MAPPER.readTree(input);if(!node.isArray() || node.size()>32)throw new IllegalArgumentException("Replay a bounded checkpoint batch.");
            var events=new ArrayList<CheckpointReplay.Event>();for(var event:node)events.add(JsonSupport.MAPPER.treeToValue(event,CheckpointReplay.Event.class));
            var sources=Map.of("core","legacy-core","adapter","equipment-adapter","execution","execution-service","returns","returns-service","shadow","shadow-scheduler");
            String owner=required("SPRING_RABBITMQ_USERNAME").replaceFirst("^cutover_","");if(!sources.containsKey(owner))throw new IllegalArgumentException("Unknown replay owner.");
            var factory=new ConnectionFactory();factory.setHost(required("SPRING_RABBITMQ_HOST"));factory.setVirtualHost(required("SPRING_RABBITMQ_VIRTUAL_HOST"));
            factory.setUsername(required("SPRING_RABBITMQ_USERNAME"));factory.setPassword(required("SPRING_RABBITMQ_PASSWORD"));
            factory.setConnectionTimeout(3000);factory.setHandshakeTimeout(3000);factory.setAutomaticRecoveryEnabled(false);
            try(var sqlConnection=DriverManager.getConnection(required("CUTOVER_DATABASE_URL"),required("CUTOVER_DATABASE_USER"),required("CUTOVER_DATABASE_PASSWORD"));
                var broker=factory.newConnection("cutover-checkpoint-replay");var channel=broker.createChannel()) {
                var sql=DSL.using(sqlConnection,SQLDialect.POSTGRES);
                if(!("cutover_"+owner+"_runtime").equals(sql.fetchOne("SELECT current_user").get(0,String.class)))throw new IllegalArgumentException("Replay owner differs from the connected runtime role.");
                var returned=new AtomicBoolean();channel.addReturnListener(message->returned.set(true));channel.confirmSelect();
                var replay=new CheckpointReplay(sql,sources.get(owner),(exchange,key,id,body)->{
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(bytes.length>DurableInbox.MAX_MESSAGE_BYTES)throw DeliveryFailure.permanent("EVENT_SIZE_LIMIT");
                    try {
                        returned.set(false);channel.basicPublish(exchange,key,true,new AMQP.BasicProperties.Builder().messageId(id.toString()).contentType("application/json").deliveryMode(2).build(),bytes);
                        channel.waitForConfirmsOrDie(4000);if(returned.get())throw DeliveryFailure.pending("MANDATORY_RETURN");
                    }catch(DeliveryFailure known){throw known;}catch(Exception unavailable){throw DeliveryFailure.pending("REPLAY_CONFIRM_UNAVAILABLE");}
                });
                var confirmed=replay.replay(events);System.out.println("CUTOVER_REPLAY_RESULT="+JsonSupport.write(Map.of("source",sources.get(owner),"confirmed",confirmed,"batchHash",JsonSupport.hash(events))));
            }
        }catch(Exception failure){System.err.println("CUTOVER_REPLAY_RESULT="+JsonSupport.write(Map.of("result","FAILED","code",failure instanceof Problem problem?problem.code():failure.getClass().getSimpleName())));System.exit(2);}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing replay setting: "+name);return value;}
}
