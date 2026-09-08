package dev.cutover.execution;

import dev.cutover.platform.PlatformConfiguration;
import dev.cutover.platform.ClientCredentials;
import dev.cutover.platform.ServiceHttp;
import java.net.URI;
import java.time.Clock;
import org.jooq.DSLContext;
import dev.cutover.platform.messaging.*;
import java.util.Set;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication @EnableScheduling @Import({PlatformConfiguration.class,MessageConfiguration.class})
public class ServiceApplication {
    public static void main(String[] args){SpringApplication.run(ServiceApplication.class,args);}
    @Bean MessageSubscription messageSubscription(@Value("${cutover.shadow-mode:false}") boolean shadow){
        return shadow?new MessageSubscription("cutover.shadow-scheduler.inbox",Set.of("legacy-core"),Set.of("site-a"),Map.of("legacy-core","cutover.observation.v1"))
                :new MessageSubscription("cutover.execution-service.inbox",Set.of("equipment-adapter"),Set.of("site-a"));
    }
    @Bean MessageHandler messageHandler(@Value("${cutover.shadow-mode:false}") boolean shadow){return shadow?new ShadowMessages():new ExecutionMessages();}
    @Bean DispatchPort dispatchPort(@Value("${cutover.adapter.url}") URI url,@Value("${cutover.clients.token-uri}") URI tokenUri,@Value("${cutover.clients.id}") String id,@Value("${cutover.clients.secret}") String secret){return new AdapterHttpClient(new ServiceHttp(url,new ClientCredentials(tokenUri,id,secret)));}
    @Bean ExecutionScheduler executionScheduler(DSLContext database,DispatchPort adapter,Clock clock){return new ExecutionScheduler(database,adapter,clock);}
}
