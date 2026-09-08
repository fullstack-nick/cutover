package dev.cutover.adapter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdapterSchedulingTest {
    @Configuration(proxyBeanMethods=false) @EnableScheduling
    static class EnableWorkerScheduling {}

    @Test void slowCommandBatchDoesNotStopFreshObservationsOrAllocation() throws Exception {
        var observations=mock(EquipmentObservations.class);
        var journal=mock(CommandJournal.class);
        var allocations=mock(Allocations.class);
        var batchStarted=new CountDownLatch(1);
        var releaseBatch=new CountDownLatch(1);
        var refreshedWhileBlocked=new CountDownLatch(2);
        var allocatedWhileBlocked=new CountDownLatch(1);
        var commandCalls=new AtomicInteger();
        when(journal.work()).thenAnswer(invocation->{
            commandCalls.incrementAndGet();batchStarted.countDown();
            if(!releaseBatch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Release the held command batch.");
            return 0;
        });
        doAnswer(invocation->{if(batchStarted.getCount()==0 && releaseBatch.getCount()==1)refreshedWhileBlocked.countDown();return null;}).when(observations).refresh();
        when(allocations.releasePending()).thenAnswer(invocation->{if(batchStarted.getCount()==0 && releaseBatch.getCount()==1)allocatedWhileBlocked.countDown();return 0;});
        var context=new AnnotationConfigApplicationContext();
        try {
            context.register(EnableWorkerScheduling.class,AdapterScheduling.class);
            context.registerBean(EquipmentObservations.class,()->observations);
            context.registerBean(CommandJournal.class,()->journal);
            context.registerBean(Allocations.class,()->allocations);
            context.registerBean(AdapterWorker.class);
            context.refresh();
            assertThat(batchStarted.await(3,TimeUnit.SECONDS)).as("A real scheduled command batch starts").isTrue();
            assertThat(refreshedWhileBlocked.await(3,TimeUnit.SECONDS)).as("Fresh observations continue during a blocked command batch").isTrue();
            assertThat(allocatedWhileBlocked.await(1,TimeUnit.SECONDS)).as("Pending allocations have their own scheduling opportunity").isTrue();
            assertThat(commandCalls).as("A fixed-delay command method never overlaps itself").hasValue(1);
        } finally {
            releaseBatch.countDown();context.close();
        }
    }
}
