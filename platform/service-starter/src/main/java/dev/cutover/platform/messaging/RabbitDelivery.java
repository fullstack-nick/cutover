package dev.cutover.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public final class RabbitDelivery implements OutboxRelay.Publisher, AutoCloseable {
    private final RabbitTemplate template;
    private final DeliveryHooks hooks;
    private InboxConsumer consumer;
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

    /** One long-lived subscription; broker prefetch bounds buffered plus processing deliveries together. */
    public synchronized int consume(String queue, DurableInbox inbox, int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Consumer batch must be 1..32");
        try {
            if (consumer != null && (!consumer.usable() || !consumer.queue.equals(queue) || consumer.limit != limit)) pauseConsumer();
            if (consumer == null) {
                var channel = template.getConnectionFactory().createConnection().createChannel(false);
                consumer = new InboxConsumer(channel, queue, limit);
                channel.basicQos(limit);
                channel.basicConsume(queue, false, consumer);
            }
            var current = consumer;
            var deliveries = new ArrayList<Delivery>(limit);
            var first = current.deliveries.poll(50, TimeUnit.MILLISECONDS);
            if (!current.usable()) throw DeliveryFailure.pending("CONSUMER_UNAVAILABLE");
            if (first == null) return 0;
            deliveries.add(first);
            // Collect an immediately arriving burst for at most 50 ms, never wait for a full batch.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
            while (deliveries.size() < limit) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                var next = current.deliveries.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) break;
                deliveries.add(next);
            }
            if (!current.usable()) throw DeliveryFailure.pending("CONSUMER_UNAVAILABLE");
            var receipts = inbox.receiveBatch(deliveries.stream().map(delivery -> new DurableInbox.Delivery(
                    delivery.getEnvelope().getExchange(), delivery.getProperties().getMessageId(), delivery.getBody())).toList());
            for (int index = 0; index < receipts.size(); index++) {
                hooks.reached("AFTER_EFFECT_BEFORE_ACK", receipts.get(index).id());
                current.getChannel().basicAck(deliveries.get(index).getEnvelope().getDeliveryTag(), false);
            }
            return receipts.size();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); pauseConsumer();
            throw DeliveryFailure.pending("CONSUMER_INTERRUPTED");
        } catch (IOException unavailable) {
            pauseConsumer(); throw DeliveryFailure.pending("CONSUMER_UNAVAILABLE");
        } catch (RuntimeException | Error failure) {
            // Closing this exact channel requeues every unacknowledged delivery, including buffered peers.
            // Committed effects whose acknowledgements were lost safely redeliver with their original IDs.
            pauseConsumer(); throw failure;
        }
    }
    public synchronized void pauseConsumer() {
        if (consumer == null) return;
        var previous = consumer; consumer = null; previous.stopped = true;
        try { previous.getChannel().abort(); } catch (IOException | RuntimeException alreadyClosed) { /* Broker retains unacknowledged originals. */ }
        finally { previous.deliveries.clear(); }
    }
    @Override public void close() { pauseConsumer(); }

    private static final class InboxConsumer extends DefaultConsumer {
        private final String queue;
        private final int limit;
        private final ArrayBlockingQueue<Delivery> deliveries;
        private volatile boolean stopped;
        InboxConsumer(Channel channel, String queue, int limit) {
            super(channel); this.queue = queue; this.limit = limit; this.deliveries = new ArrayBlockingQueue<>(limit);
        }
        boolean usable() { return !stopped && getChannel().isOpen(); }
        @Override public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
            if (stopped) return;
            if (body.length > DurableInbox.MAX_MESSAGE_BYTES || !deliveries.offer(new Delivery(envelope, properties, body))) stopped = true;
        }
        @Override public void handleCancel(String tag) { stopped = true; }
        @Override public void handleShutdownSignal(String tag, ShutdownSignalException signal) { stopped = true; }
    }
}
