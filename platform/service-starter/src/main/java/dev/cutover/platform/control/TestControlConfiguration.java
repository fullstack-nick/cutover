package dev.cutover.platform.control;

import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@ConditionalOnProperty(name="cutover.test-controls-enabled",havingValue="true")
@Import(TestControlController.class)
public class TestControlConfiguration {
    @Bean RuntimeControls runtimeControls(DSLContext database) { return new RuntimeControls(database); }
}
