package dev.cutover.testing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Disposable owner database. Never uses a developer's existing database or Docker volume. */
public final class DatabaseFixture implements AutoCloseable {
    private final PostgreSQLContainer container;
    private final HikariDataSource source;
    private final Flyway flyway;
    public DatabaseFixture(String service) {
        container = new PostgreSQLContainer(System.getProperty("cutover.postgres.image", "postgres:18.6-bookworm"))
                .withLabel("dev.cutover.purpose", "verification");
        container.start();
        var config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl()); config.setUsername(container.getUsername()); config.setPassword(container.getPassword());
        config.setMaximumPoolSize(8); config.setConnectionTimeout(3000);
        source = new HikariDataSource(config);
        flyway = Flyway.configure().dataSource(source).locations("classpath:db/platform", "classpath:db/" + service)
                .cleanDisabled(false).load();
        flyway.migrate();
    }
    public DSLContext sql() { return DSL.using(source, SQLDialect.POSTGRES); }
    public HikariDataSource dataSource() { return source; }
    public PostgreSQLContainer container() { return container; }
    public void reset() { flyway.clean(); flyway.migrate(); }
    @Override public void close() { source.close(); container.stop(); }
}
