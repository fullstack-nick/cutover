package dev.cutover.platform;

import java.util.ArrayList;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/** Readiness describes installed capabilities; admission pressure must not hide recovery read APIs. */
public final class SchemaReadiness implements HealthIndicator, SmartInitializingSingleton {
    private final DSLContext database;
    private final Set<String> required;
    public SchemaReadiness(DSLContext database, Set<String> required) {
        if(required.isEmpty() || required.size()>32 || required.stream().anyMatch(value->!value.matches("[0-9]{1,6}")))
            throw new IllegalArgumentException("Declare bounded required database migration versions.");
        this.database=database;this.required=Set.copyOf(required);
    }
    @Override public Health health() {
        try {
            var applied=database.fetch("SELECT version,success FROM public.flyway_schema_history");
            var missing=new ArrayList<>(required);
            for(var row:applied) {
                if(!row.get("success",Boolean.class))return Health.down().withDetail("capability","failed-migration").build();
                missing.remove(row.get("version",String.class));
            }
            var routine=database.fetchOne("SELECT to_regprocedure('cutover_ops.volume_status()') IS NOT NULL AND coalesce(has_function_privilege(current_user,to_regprocedure('cutover_ops.volume_status()'),'EXECUTE'),false)").get(0,Boolean.class);
            if(!routine)missing.add("local-volume-observation");
            return missing.isEmpty()?Health.up().build():Health.down().withDetail("missingCapabilities",missing).build();
        } catch(RuntimeException unavailable) {
            return Health.down().withDetail("capability","database-schema-unavailable").build();
        }
    }
    @Override public void afterSingletonsInstantiated() {
        var readiness=health();
        if(!Status.UP.equals(readiness.getStatus()))
            throw new IllegalStateException("Required owner database capabilities are unavailable; apply the documented migrations and volume-observation provisioning before starting this image. "+readiness.getDetails());
    }
}
