package dev.cutover.returns;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="cutover.workers-enabled",havingValue="true",matchIfMissing=true)
class ReturnsWorker {
    private final ReturnsCoordinator coordinator;
    ReturnsWorker(ReturnsCoordinator coordinator){this.coordinator=coordinator;}
    @Scheduled(fixedDelay=150) void poll(){coordinator.poll();}
}
