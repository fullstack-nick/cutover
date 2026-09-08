package dev.cutover.platform;

import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchemaConfiguration {
    @Bean SchemaReadiness schemaReadiness(DSLContext database,@Value("${cutover.schema.required-versions:107}") Set<String> required) {
        return new SchemaReadiness(database,required);
    }
}
