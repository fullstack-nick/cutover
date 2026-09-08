package dev.cutover.platform.messaging;

import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({MessagingController.class,DiagnosticController.class,AuditController.class})
@ConditionalOnProperty(name = "cutover.messaging-enabled", havingValue = "true")
public class MessageConfiguration {
    @Bean MessagingOperations messagingOperations(DSLContext database) { return new MessagingOperations(database); }
    @Bean QuarantineOperations quarantineOperations(DSLContext database,MessageHandler handler,MessageSubscription subscription,Clock clock) {return new QuarantineOperations(database,handler,subscription,clock);}
    @Bean @ConditionalOnMissingBean DeliveryHooks deliveryHooks(DSLContext database,Clock clock,
            @org.springframework.beans.factory.annotation.Value("${cutover.test-controls-enabled:false}") boolean enabled) {
        return enabled?new dev.cutover.platform.control.ProcessFaults(database,clock):DeliveryHooks.NONE;
    }
    @Bean RabbitDelivery rabbitDelivery(RabbitTemplate template, DeliveryHooks hooks) { return new RabbitDelivery(template, hooks); }
    @Bean DurableInbox durableInbox(DSLContext database, MessageHandler handler, Clock clock, MessageSubscription subscription) {
        return new DurableInbox(database, handler, clock, subscription);
    }
    @Bean OutboxRelay outboxRelay(DSLContext database, RabbitDelivery rabbit, Clock clock, DeliveryHooks hooks) { return new OutboxRelay(database, rabbit, clock, hooks); }
    @Bean(initMethod = "start", destroyMethod = "close")
    MessageRuntime messageRuntime(DSLContext database, OutboxRelay relay, DurableInbox inbox, RabbitDelivery rabbit, MessageSubscription subscription, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${cutover.restore-retention-held:false}") boolean restorationHeld) {
        return new MessageRuntime(database, relay, inbox, rabbit, subscription.queue(),new MessageRetention(database,clock,restorationHeld));
    }
}
