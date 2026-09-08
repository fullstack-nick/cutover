package dev.cutover.platform;

import org.flywaydb.core.Flyway;

/** One-shot migration process; runtime application identities never need DDL privileges. */
public final class MigrationMain {
    private MigrationMain() {}
    public static void main(String[] args) {
        var configuration=Flyway.configure().dataSource(required("CUTOVER_DATABASE_URL"),required("CUTOVER_DATABASE_USER"),required("CUTOVER_DATABASE_PASSWORD"))
                .locations(required("CUTOVER_MIGRATION_LOCATIONS").split(","));
        String target=System.getenv().getOrDefault("CUTOVER_MIGRATION_TARGET","latest");
        if(!target.equals("latest") && !target.matches("[1-9][0-9]{0,6}"))throw new IllegalArgumentException("Invalid explicit migration target.");
        configuration.target(target);var flyway=configuration.load();
        if(!target.equals("latest") && flyway.info().current()!=null && flyway.info().current().getVersion().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion(target))>0)throw new IllegalStateException("Migration targets cannot perform a schema downgrade.");
        var result=flyway.migrate();
        System.out.println(JsonSupport.write(java.util.Map.of("operation","migration","migrationsExecuted",result.migrationsExecuted,"success",result.success)));
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Required migration setting is absent: "+name);return value;}
}
