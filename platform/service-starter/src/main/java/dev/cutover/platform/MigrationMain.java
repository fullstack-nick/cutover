package dev.cutover.platform;

import org.flywaydb.core.Flyway;

/** One-shot migration process; runtime application identities never need DDL privileges. */
public final class MigrationMain {
    private MigrationMain() {}
    public static void main(String[] args) {
        var result=Flyway.configure().dataSource(required("CUTOVER_DATABASE_URL"),required("CUTOVER_DATABASE_USER"),required("CUTOVER_DATABASE_PASSWORD"))
                .locations(required("CUTOVER_MIGRATION_LOCATIONS").split(",")).load().migrate();
        System.out.println(JsonSupport.write(java.util.Map.of("operation","migration","migrationsExecuted",result.migrationsExecuted,"success",result.success)));
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Required migration setting is absent: "+name);return value;}
}
