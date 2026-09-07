package dev.cutover.core;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component @ConditionalOnProperty(name="cutover.messaging-enabled",havingValue="true")
final class ShadowObservationWorker implements AutoCloseable {
    private final java.util.concurrent.ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(runnable->{var thread=new Thread(runnable,"cutover-shadow-relay");thread.setDaemon(true);return thread;});
    private long nextAttempt;private int failures;
    ShadowObservationWorker(ShadowObservations observations){worker.scheduleWithFixedDelay(()->{
        if(System.nanoTime()<nextAttempt)return;
        try{observations.relay();failures=0;}catch(RuntimeException unavailable){failures=Math.min(failures+1,6);nextAttempt=System.nanoTime()+TimeUnit.SECONDS.toNanos(1L<<Math.min(failures-1,4));org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shadow relay deferred: failureType={}",unavailable.getClass().getSimpleName());}
    },100,500,TimeUnit.MILLISECONDS);}
    @Override public void close(){worker.shutdownNow();}
}
