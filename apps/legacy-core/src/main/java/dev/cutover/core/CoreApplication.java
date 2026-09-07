package dev.cutover.core;

import dev.cutover.platform.ClientCredentials;
import dev.cutover.platform.PlatformConfiguration;
import dev.cutover.platform.ServiceHttp;
import java.net.URI;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import dev.cutover.platform.messaging.*;
import java.util.Set;

@SpringBootApplication @EnableScheduling @Import({PlatformConfiguration.class, MessageConfiguration.class})
public class CoreApplication {
    public static void main(String[] args){SpringApplication.run(CoreApplication.class,args);}
    @Bean OrderService orderService(DSLContext database,Clock clock){return new OrderService(database,clock);}
    @Bean DispatchPort dispatchPort(@Value("${cutover.adapter.url}") URI url,@Value("${cutover.clients.token-uri}") URI tokenUri,@Value("${cutover.clients.secret}") String secret){
        return new AdapterHttpClient(new ServiceHttp(url,new ClientCredentials(tokenUri,"legacy-core",secret)));
    }
    @Bean LegacyScheduler legacyScheduler(DSLContext database,DispatchPort port,OrderService orders,Clock clock){return new LegacyScheduler(database,port,orders,clock);}
    @Bean MessageHandler messageHandler(Clock clock) { return new CoreMessages(clock); }
    @Bean MessageSubscription messageSubscription() { return new MessageSubscription("cutover.legacy-core.inbox", Set.of("equipment-adapter"), Set.of("site-a")); }
}
