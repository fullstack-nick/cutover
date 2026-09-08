package dev.cutover.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration @Import({JwtSecurityConfiguration.class,ProblemHandler.class,SchemaConfiguration.class,dev.cutover.platform.control.TestControlConfiguration.class})
public class PlatformConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean RequestLimits requestLimits() { return new RequestLimits(); }
}
