package dev.cutover.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnExpression("${cutover.workers-enabled:true} && !${cutover.shadow-mode:false}")
class ExecutionWorker {
    private final ExecutionScheduler scheduler;
    ExecutionWorker(ExecutionScheduler scheduler){this.scheduler=scheduler;}
    @Scheduled(fixedDelay=150) void poll(){scheduler.poll();}
}
