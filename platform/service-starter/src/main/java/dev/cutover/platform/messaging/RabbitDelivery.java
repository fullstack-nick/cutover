package dev.cutover.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import com.rabbitmq.client.GetResponse;
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

    /** Basic-get has no prefetch bound: this explicit batch bounds memory and unacknowledged deliveries. */
    public int consume(String queue, DurableInbox inbox, int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Consumer batch must be 1..32");
        Integer count = template.execute(channel -> {
            var deliveries = new ArrayList<GetResponse>(limit);
            try {
                while (deliveries.size() < limit) {
                    var delivery = channel.basicGet(queue, false);
                    if (delivery == null) break;
                    deliveries.add(delivery);
                }
            } catch (java.io.IOException | RuntimeException unavailable) {
                channel.abort(); throw unavailable;
            }
            if (deliveries.isEmpty()) return 0;
            int acknowledged = 0;
            try {
                var receipts = inbox.receiveBatch(deliveries.stream().map(delivery -> new DurableInbox.Delivery(
                        delivery.getEnvelope().getExchange(), delivery.getProps().getMessageId(), delivery.getBody())).toList());
                for (int index = 0; index < receipts.size(); index++) {
                    hooks.reached("AFTER_EFFECT_BEFORE_ACK", receipts.get(index).id());
                    channel.basicAck(deliveries.get(index).getEnvelope().getDeliveryTag(), false);
                    acknowledged++;
                }
            } catch (RuntimeException failure) {
                // No failed transfer is acknowledged. Already committed peers may redeliver safely.
                try {
                    for (int index = acknowledged; index < deliveries.size(); index++)
                        channel.basicNack(deliveries.get(index).getEnvelope().getDeliveryTag(), false, true);
                } catch (java.io.IOException unavailable) {
                    failure.addSuppressed(unavailable); channel.abort();
                }
                throw failure; // Runtime applies a transport backoff before trying again.
            } catch (Error crash) {
                channel.abort(); throw crash;
            } catch (java.io.IOException unavailable) {
                channel.abort(); throw unavailable;
            }
            return acknowledged;
        });
        return count == null ? 0 : count;
    }
}
