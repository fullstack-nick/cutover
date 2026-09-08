package dev.cutover.adapter;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** A slow command batch cannot stop equipment freshness or pending allocation. */
@Configuration(proxyBeanMethods=false)
class AdapterScheduling {
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("cutover-equipment-");
        scheduler.setAwaitTerminationSeconds(8);
        return scheduler;
    }
}
