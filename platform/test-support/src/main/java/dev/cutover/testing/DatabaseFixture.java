package dev.cutover.testing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Disposable owner database. Never uses a developer's existing database or Docker volume. */
public final class DatabaseFixture implements AutoCloseable {
    private final PostgreSQLContainer container;
    private final HikariDataSource source;
    private final Flyway flyway;
    public DatabaseFixture(String service) {
        this(service,"latest");
    }
    public DatabaseFixture(String service,String target) {
        this(service,target,null);
    }
    public DatabaseFixture(String service,String target,Long volumeBytes) {
        container = new PostgreSQLContainer(System.getProperty("cutover.postgres.image", "postgres:18.6-bookworm"))
                .withLabel("dev.cutover.project", "cutover").withLabel("dev.cutover.purpose", "verification")
                .withCopyFileToContainer(MountableFile.forClasspathResource("ops/volume-probe.sh"),"/tmp/volume-probe.sh");
        if (volumeBytes != null) {
            if (volumeBytes < 268435456L || volumeBytes > 536870912L) throw new IllegalArgumentException("Use a bounded disposable 256–512 MiB test filesystem.");
            container.withTmpFs(Map.of("/var/lib/postgresql","rw,size="+volumeBytes));
        }
        container.start();
        var config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl()); config.setUsername(container.getUsername()); config.setPassword(container.getPassword());
        config.setMaximumPoolSize(8); config.setConnectionTimeout(3000);
        source = new HikariDataSource(config);
        flyway = Flyway.configure().dataSource(source).locations("classpath:db/platform", "classpath:db/" + service)
                .target(target).cleanDisabled(false).load();
        flyway.migrate();
        try (var definition=DatabaseFixture.class.getResourceAsStream("/ops/volume-status.sql")) {
            sql().execute(new String(definition.readAllBytes(),StandardCharsets.UTF_8));
            var result=container.execInContainer("sh","-c","nohup sh /tmp/volume-probe.sh >/tmp/volume-probe.log 2>&1 & echo $! >/tmp/volume-probe.pid");
            if(result.getExitCode()!=0)throw new IllegalStateException("The disposable volume probe did not start.");
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
            while(!"HEALTHY".equals(sql().fetchOne("SELECT cutover_ops.volume_status()->>'state'").get(0,String.class))) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("The disposable database filesystem has no fresh headroom observation.");
                Thread.sleep(50);
            }
        } catch(Exception failure) { source.close();container.stop();throw new IllegalStateException("Disposable volume-probe setup failed.",failure); }
    }
    public DSLContext sql() { return DSL.using(source, SQLDialect.POSTGRES); }
    public HikariDataSource dataSource() { return source; }
    public PostgreSQLContainer container() { return container; }
    public void reset() { flyway.clean(); flyway.migrate(); }
    @Override public void close() { source.close(); container.stop(); }
}
