package dev.cutover.execution;

import dev.cutover.platform.PlatformConfiguration;
import dev.cutover.platform.messaging.*;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication @EnableScheduling @Import({PlatformConfiguration.class,MessageConfiguration.class})
public class ServiceApplication {
    public static void main(String[] args){SpringApplication.run(ServiceApplication.class,args);}
    @Bean MessageSubscription messageSubscription(){return new MessageSubscription("cutover.execution-service.inbox",Set.of("equipment-adapter"),Set.of("site-a"));}
    @Bean MessageHandler messageHandler(){return new ExecutionMessages();}
}
