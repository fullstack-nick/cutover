package dev.cutover.adapter;

import dev.cutover.platform.PlatformConfiguration;
import dev.cutover.platform.ClientCredentials;
import dev.cutover.platform.ServiceHttp;
import java.net.URI;
import java.nio.file.Path;
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
public class AdapterApplication {
    public static void main(String[] args) { SpringApplication.run(AdapterApplication.class,args); }
    @Bean Allocations allocations(DSLContext sql,Clock clock) { return new Allocations(sql,clock); }
    @Bean EquipmentPort equipmentPort(@Value("${cutover.equipment.url}") URI url,@Value("${cutover.equipment.key-store}") Path keys,
                                      @Value("${cutover.equipment.trust-store}") Path trusts,@Value("${cutover.equipment.store-password}") String password) {
        return new MutualTlsEquipmentClient(url,keys,trusts,password);
    }
    @Bean EquipmentObservations observations(DSLContext sql,EquipmentPort port,Clock clock) { return new EquipmentObservations(sql,port,clock); }
    @Bean CommandJournal journal(DSLContext sql,EquipmentPort port,EquipmentObservations observations,Clock clock) { return new CommandJournal(sql,port,observations,clock); }
    @Bean CancellationGate cancellationGate(DSLContext sql,EquipmentObservations observations,Clock clock) { return new CancellationGate(sql,observations,clock); }
    @Bean OwnerEvidencePort ownerEvidence(@Value("${cutover.owners.core-url}") URI core,@Value("${cutover.owners.execution-url}") URI execution,
                                         @Value("${cutover.clients.token-uri}") URI tokenUri,@Value("${cutover.clients.secret}") String secret) {
        var credentials=new ClientCredentials(tokenUri,"equipment-adapter",secret);
        return new HttpOwnerEvidence(new ServiceHttp(core,credentials),new ServiceHttp(execution,credentials));
    }
    @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="cutover.test-controls-enabled",havingValue="true")
    MigrationProcessFaults migrationFaults(DSLContext sql,Clock clock) {return new MigrationProcessFaults(sql,clock);}
    @Bean MigrationSessions migrationSessions(DSLContext sql,OwnerEvidencePort owners,EquipmentObservations observations,Clock clock,
                                            org.springframework.beans.factory.ObjectProvider<MigrationProcessFaults> faults) {
        var hook=faults.getIfAvailable();
        return new MigrationSessions(sql,owners,observations,clock,hook==null?(phase,id)->{}:hook);
    }
    @Bean MessageHandler messageHandler(Clock clock) { return new AdapterMessages(clock); }
    @Bean MessageSubscription messageSubscription() { return new MessageSubscription("cutover.equipment-adapter.inbox", Set.of("legacy-core", "returns-service"), Set.of("site-a")); }
}
