package dev.cutover.platform.control;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import dev.cutover.platform.JsonSupport;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit local verification process, launched with an existing pod's restricted broker identity. */
public final class BrokerProbeMain {
    private BrokerProbeMain() {}
    public static void main(String[] ignored) {
        if(!"true".equals(System.getenv("CUTOVER_TEST_CONTROLS_ENABLED")))throw new IllegalStateException("Local verification controls are disabled.");
        try {
            byte[] input=System.in.readNBytes(90001);
            if(input.length>90000)throw new IllegalArgumentException("Probe input exceeds its limit.");
            var request=JsonSupport.MAPPER.readTree(input);
            String exchange=request.path("exchange").asString(),key=request.path("routingKey").asString();
            if(!Set.of("cutover.legacy-core.v1","cutover.equipment-adapter.v1","cutover.execution-service.v1","cutover.returns-service.v1","cutover.shadow-scheduler.v1","cutover.observation.v1").contains(exchange)
                    || !key.matches("[A-Za-z][A-Za-z0-9.]{0,79}"))throw new IllegalArgumentException("Unknown probe destination.");
            UUID id=UUID.fromString(request.path("messageId").asString());
            byte[] body=Base64.getDecoder().decode(request.path("bodyBase64").asString());
            if(body.length>65536)throw new IllegalArgumentException("Message exceeds the broker bound.");
            int repeat=request.path("repeat").asInt(1);
            if(repeat<1||repeat>10001)throw new IllegalArgumentException("Use at most 10,001 copies of one retained event.");
            var factory=new ConnectionFactory();
            factory.setHost(System.getenv("SPRING_RABBITMQ_HOST"));factory.setVirtualHost(System.getenv("SPRING_RABBITMQ_VIRTUAL_HOST"));
            factory.setUsername(System.getenv("SPRING_RABBITMQ_USERNAME"));factory.setPassword(System.getenv("SPRING_RABBITMQ_PASSWORD"));
            factory.setConnectionTimeout(3000);factory.setHandshakeTimeout(3000);factory.setAutomaticRecoveryEnabled(false);
            try(var connection=factory.newConnection("cutover-explicit-probe");var channel=connection.createChannel()) {
                var returned=new AtomicBoolean();channel.addReturnListener(message->returned.set(true));channel.confirmSelect();
                int confirmed=0;
                while(confirmed<repeat){
                    int batch=Math.min(64,repeat-confirmed);
                    for(int index=0;index<batch;index++)channel.basicPublish(exchange,key,true,new AMQP.BasicProperties.Builder().messageId(id.toString()).contentType("application/json").deliveryMode(2).build(),body);
                    if(!channel.waitForConfirms(5000)){
                        System.out.println(JsonSupport.write(Map.of("messageId",id,"result","NACKED","fullyConfirmedCopies",confirmed,"attemptedCopies",confirmed+batch,"nackedBatchCopies",batch)));System.exit(2);
                    }
                    if(returned.get())break;
                    confirmed+=batch;
                }
                System.out.println(JsonSupport.write(Map.of("messageId",id,"result",returned.get()?"RETURNED":"CONFIRMED","fullyConfirmedCopies",confirmed,"requestedCopies",repeat)));
                if(returned.get())System.exit(2);
            }
        } catch(Exception failure) {
            // Do not print the exception: client diagnostics can contain connection credentials.
            System.out.println(JsonSupport.write(Map.of("result","PUBLISH_FAILED","failureType",failure.getClass().getSimpleName())));
            System.exit(2);
        }
    }
}
