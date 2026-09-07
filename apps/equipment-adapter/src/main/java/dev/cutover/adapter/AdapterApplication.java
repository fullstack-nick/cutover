package dev.cutover.adapter;

import dev.cutover.platform.PlatformConfiguration;
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
    @Bean Allocations allocations(DSLContext sql) { return new Allocations(sql); }
    @Bean EquipmentPort equipmentPort(@Value("${cutover.equipment.url}") URI url,@Value("${cutover.equipment.key-store}") Path keys,
                                      @Value("${cutover.equipment.trust-store}") Path trusts,@Value("${cutover.equipment.store-password}") String password) {
        return new MutualTlsEquipmentClient(url,keys,trusts,password);
    }
    @Bean EquipmentObservations observations(DSLContext sql,EquipmentPort port,Clock clock) { return new EquipmentObservations(sql,port,clock); }
    @Bean CommandJournal journal(DSLContext sql,EquipmentPort port,EquipmentObservations observations,Clock clock) { return new CommandJournal(sql,port,observations,clock); }
    @Bean CancellationGate cancellationGate(DSLContext sql,EquipmentObservations observations,Clock clock) { return new CancellationGate(sql,observations,clock); }
    @Bean MessageHandler messageHandler() { return new AdapterMessages(); }
    @Bean MessageSubscription messageSubscription() { return new MessageSubscription("cutover.equipment-adapter.inbox", Set.of("legacy-core", "returns-service"), Set.of("site-a")); }
}
