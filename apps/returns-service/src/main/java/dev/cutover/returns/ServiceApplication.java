package dev.cutover.returns;

import dev.cutover.platform.PlatformConfiguration;
import dev.cutover.platform.ClientCredentials;
import dev.cutover.platform.ServiceHttp;
import dev.cutover.platform.messaging.*;
import java.net.URI;
import java.time.Clock;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication @EnableScheduling @Import({PlatformConfiguration.class,MessageConfiguration.class})
public class ServiceApplication {
    public static void main(String[] args){SpringApplication.run(ServiceApplication.class,args);}
    @Bean MessageSubscription messageSubscription(){return new MessageSubscription("cutover.returns-service.inbox",Set.of("equipment-adapter"),Set.of("site-a"));}
    @Bean MessageHandler messageHandler(ReceiptService receipts){return new ReturnsMessages(receipts);}
    @Bean ReceiptService receiptService(DSLContext database,Clock clock){return new ReceiptService(database,clock);}
    @Bean DispatchPort dispatchPort(@Value("${cutover.adapter.url}") URI url,@Value("${cutover.clients.token-uri}") URI tokenUri,@Value("${cutover.clients.secret}") String secret){return new AdapterHttpClient(new ServiceHttp(url,new ClientCredentials(tokenUri,"returns-service",secret)));}
    @Bean ReturnsCoordinator returnsCoordinator(DSLContext database,DispatchPort adapter,ReceiptService receipts,Clock clock){return new ReturnsCoordinator(database,adapter,receipts,clock);}
}
