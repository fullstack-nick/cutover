package dev.cutover.platform.messaging;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.slf4j.LoggerFactory;

/** Separate bounded threads keep broker waits away from equipment and business scheduling. */
public final class MessageRuntime implements AutoCloseable {
    private final ScheduledExecutorService workers;
    private final DSLContext database;
    private final OutboxRelay relay;
    private final DurableInbox inbox;
    private final RabbitDelivery rabbit;
    private final String queue;
    private final MessageRetention retention;
    public MessageRuntime(DSLContext database, OutboxRelay relay, DurableInbox inbox, RabbitDelivery rabbit, String queue, MessageRetention retention) {
        this.database = database; this.relay = relay; this.inbox = inbox; this.rabbit = rabbit; this.queue = queue;
        this.retention = retention;
        var sequence = new AtomicInteger();
        this.workers = Executors.newScheduledThreadPool(3, runnable -> {
            var thread = new Thread(runnable, "cutover-delivery-" + sequence.incrementAndGet());
            thread.setDaemon(true); return thread;
        });
    }
    public void start() {
        workers.scheduleWithFixedDelay(new Guard("relay", () -> relay.poll(16)), 100, 50, TimeUnit.MILLISECONDS);
        workers.scheduleWithFixedDelay(new Guard("consumer", () -> {
            if (!database.fetchOne("SELECT workers_paused OR consumer_paused FROM service_control WHERE singleton").get(0, Boolean.class)) rabbit.consume(queue, inbox, 16);
            else rabbit.pauseConsumer();
        }), 100, 50, TimeUnit.MILLISECONDS);
        workers.scheduleWithFixedDelay(new Guard("inbox-retry", () -> inbox.retry(16)), 250, 150, TimeUnit.MILLISECONDS);
        workers.scheduleWithFixedDelay(new Guard("message-retention", () -> retention.compact()), 30000, 30000, TimeUnit.MILLISECONDS);
    }
    @Override public void close() {
        workers.shutdownNow();
        try { workers.awaitTermination(8, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        rabbit.pauseConsumer();
    }
    private static final class Guard implements Runnable {
        private final String operation;
        private final Runnable work;
        private int failures;
        private long nextAttempt;
        Guard(String operation, Runnable work) { this.operation = operation; this.work = work; }
        @Override public void run() {
            if (System.nanoTime() < nextAttempt) return;
            try { work.run(); failures = 0; }
            catch (RuntimeException failure) {
                failures = Math.min(failures + 1, 6);
                nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L << Math.min(failures - 1, 4));
                LoggerFactory.getLogger(MessageRuntime.class).warn("Delivery worker deferred: operation={}, failureType={}, consecutiveFailures={}", operation, failure.getClass().getSimpleName(), failures);
            }
        }
    }
}
