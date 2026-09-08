package dev.cutover.adapter;

import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Slow owner evidence reads cannot occupy the equipment polling scheduler. */
@Component
@ConditionalOnProperty(name="cutover.workers-enabled", havingValue="true", matchIfMissing=true)
class MigrationWorker implements SmartLifecycle {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MigrationWorker.class);
    private final MigrationSessions migrations;
    private ScheduledExecutorService worker;
    private volatile boolean running;

    MigrationWorker(MigrationSessions migrations) { this.migrations = migrations; }
    @Override public synchronized void start() {
        if (running) return;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "migration-evidence-worker"); thread.setDaemon(true); return thread;
        });
        running = true;
        worker.scheduleWithFixedDelay(() -> {
            try { migrations.poll(); }
            catch (Exception failed) { LOG.warn("Migration step unavailable; durable phase and lease are retained ({}).", failed.getClass().getSimpleName()); }
        }, 1, 1, TimeUnit.SECONDS);
    }
    @Override public synchronized void stop() {
        running = false;
        if (worker != null) worker.shutdownNow();
    }
    @Override public boolean isRunning() { return running; }
}
