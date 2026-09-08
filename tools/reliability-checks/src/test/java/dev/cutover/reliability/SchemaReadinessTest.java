package dev.cutover.reliability;

import dev.cutover.platform.SchemaReadiness;
import dev.cutover.testing.DatabaseFixture;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import static org.assertj.core.api.Assertions.*;

class SchemaReadinessTest {
    @Test void installedCapabilitiesPermitAdditiveSchemasButMissingOrFailedMigrationsBlockStartup() {
        try(var db=new DatabaseFixture("reliability")) {
            var supported=new SchemaReadiness(db.sql(),Set.of("107"));
            assertThat(supported.health().getStatus()).isEqualTo(Status.UP);supported.afterSingletonsInstantiated();
            var unsupported=new SchemaReadiness(db.sql(),Set.of("107","117"));
            assertThat(unsupported.health().getStatus()).isEqualTo(Status.DOWN);
            assertThatThrownBy(unsupported::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class).hasMessageContaining("117");
            db.sql().execute("UPDATE flyway_schema_history SET success=false WHERE version='107'");
            assertThat(supported.health().getStatus()).isEqualTo(Status.DOWN);
            assertThatThrownBy(supported::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class).hasMessageContaining("failed-migration");
        }
    }
    @Test void missingVolumeCapabilityBlocksStartupWithoutDependingOnBrokerOrDiskAdmissionState() {
        try(var db=new DatabaseFixture("reliability")) {
            var supported=new SchemaReadiness(db.sql(),Set.of("107"));
            db.sql().execute("UPDATE service_control SET critical_storage=true");
            assertThat(supported.health().getStatus()).isEqualTo(Status.UP);
            db.sql().execute("DROP SCHEMA cutover_ops CASCADE");
            assertThat(supported.health().getStatus()).isEqualTo(Status.DOWN);
            assertThatThrownBy(supported::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class).hasMessageContaining("local-volume-observation");
        }
    }
}
