package dev.cutover.simulator;

import dev.cutover.platform.ProblemHandler;
import dev.cutover.platform.RequestLimits;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication @EnableScheduling @Import({ProblemHandler.class,dev.cutover.platform.SchemaConfiguration.class})
public class SimulatorApplication {
    public static void main(String[] args) { SpringApplication.run(SimulatorApplication.class,args); }
    @Bean SimulatorEngine simulatorEngine(DSLContext database) { return new SimulatorEngine(database,Clock.systemUTC()); }
    @Bean RequestLimits requestLimits() { return new RequestLimits(); }
}
