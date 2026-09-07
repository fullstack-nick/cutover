package dev.cutover.core;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="cutover.workers-enabled",havingValue="true",matchIfMissing=true)
class LegacyWorker {
    private final LegacyScheduler scheduler;
    private final OrderCancellations cancellations;
    LegacyWorker(LegacyScheduler scheduler,OrderCancellations cancellations){this.scheduler=scheduler;this.cancellations=cancellations;}
    @Scheduled(fixedDelay=150) void poll(){scheduler.poll();}
    @Scheduled(fixedDelay=500) void cancel(){cancellations.poll();}
}
