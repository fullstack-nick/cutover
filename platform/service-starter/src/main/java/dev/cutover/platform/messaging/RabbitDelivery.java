package dev.cutover.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public final class RabbitDelivery implements OutboxRelay.Publisher {
    private final RabbitTemplate template;
    private final DeliveryHooks hooks;
    public RabbitDelivery(RabbitTemplate template, DeliveryHooks hooks) {
        this.template = template; this.hooks = hooks;
        template.setMandatory(true);
    }
    @Override public void publish(String exchange, String routingKey, UUID id, String body) {
        try { publishAsync(exchange,routingKey,id,body).get(4,TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw DeliveryFailure.pending("CONFIRM_INTERRUPTED"); }
        catch (DeliveryFailure known) { throw known; }
        catch (Exception unavailable) {
            if(unavailable.getCause() instanceof DeliveryFailure known)throw known;
            throw DeliveryFailure.pending("CONFIRM_UNAVAILABLE");
        }
    }
    @Override public void inBatch(Runnable work) {
        // Keep the bounded burst on one publisher channel while each original retains its own correlation.
        template.invoke(operations->{work.run();return null;});
    }
    @Override public CompletableFuture<Void> publishAsync(String exchange,String routingKey,UUID id,String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > DurableInbox.MAX_MESSAGE_BYTES) throw DeliveryFailure.permanent("EVENT_SIZE_LIMIT");
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(id.toString());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        // Correlation belongs to a publish attempt; the wire event identity remains stable.
        var correlation = new CorrelationData(id + ":" + UUID.randomUUID());
        try {
            template.send(exchange, routingKey, new Message(bytes, properties), correlation);
            return correlation.getFuture().orTimeout(4,TimeUnit.SECONDS).thenApply(confirmation->{
                // Spring populates a mandatory return before completing this event's confirmation future.
                if (correlation.getReturned() != null) throw DeliveryFailure.pending("MANDATORY_RETURN");
                if (!confirmation.ack()) throw DeliveryFailure.pending("PUBLISH_NACK");
                return null;
            });
        } catch (DeliveryFailure known) { throw known; }
        catch (Exception unavailable) { throw DeliveryFailure.pending("CONFIRM_UNAVAILABLE"); }
    }

    /** A bounded pull loop has only one unacknowledged delivery at a time; QoS is not its bound. */
    public int consume(String queue, DurableInbox inbox, int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Consumer batch must be 1..32");
        Integer count = template.execute(channel -> {
            int consumed = 0;
            while (consumed < limit) {
                var delivery = channel.basicGet(queue, false);
                if (delivery == null) break;
                long tag = delivery.getEnvelope().getDeliveryTag();
                try {
                    UUID id = inbox.receive(delivery.getEnvelope().getExchange(), delivery.getProps().getMessageId(), delivery.getBody()).id();
                    hooks.reached("AFTER_EFFECT_BEFORE_ACK", id);
                    channel.basicAck(tag, false);
                    consumed++;
                } catch (RuntimeException failure) {
                    channel.basicNack(tag, false, true);
                    throw failure; // Runtime applies a transport backoff before trying again.
                } catch (Error crash) {
                    // Test crash hooks model abrupt channel loss. A real process halt cannot acknowledge.
                    channel.abort(); throw crash;
                }
            }
            return consumed;
        });
        return count == null ? 0 : count;
    }
}
