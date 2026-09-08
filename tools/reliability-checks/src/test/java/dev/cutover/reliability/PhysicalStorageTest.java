package dev.cutover.reliability;

import dev.cutover.platform.Database;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.StorageBudget;
import dev.cutover.testing.DatabaseFixture;
import java.time.Duration;
import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real, bounded tmpfs pressure. No file is written to an existing demonstration or host volume. */
class PhysicalStorageTest {
    @Test void filesystemPressureRefusesNewWritesAndRetainsAcceptedRecoveryState() throws Exception {
        try(var db=new DatabaseFixture("reliability","latest",402653184L)) {
            var sql=db.sql();
            var initial=StorageBudget.status(sql).path("volume");
            assertThat(initial.path("state").asString()).isEqualTo("HEALTHY");
            assertThat(initial.path("totalBytes").asLong()).isEqualTo(402653184L);
            UUID accepted=UUID.randomUUID();
            sql.transaction(configuration->{var tx=DSL.using(configuration);Database.requireDurability(tx,true);
                tx.execute("INSERT INTO effects(event_id,aggregate_id,version,value) VALUES (?,?,1,1)",accepted,accepted);});
            // Allocate only enough real pages to retain 48 MiB free in this disposable 384 MiB filesystem.
            long fill=(initial.path("availableBytes").asLong()-50331648L)/1048576L;
            assertThat(fill).isBetween(1L,320L);
            var allocated=db.container().execInContainer("sh","-c","dd if=/dev/zero of=/var/lib/postgresql/cutover-pressure-fixture bs=1048576 count="+fill+" status=none");
            assertThat(allocated.getExitCode()).isZero();
            awaitState(db,"CRITICAL");
            assertThat(StorageBudget.status(sql).path("volume").path("availableBytes").asLong()).isLessThan(67108864L);
            for(boolean intake:new boolean[]{true,false}) {
                assertThatThrownBy(()->sql.transaction(configuration->{var tx=DSL.using(configuration);Database.requireDurability(tx,intake);
                    tx.execute("INSERT INTO effects(event_id,aggregate_id,version,value) VALUES (?,?,1,2)",UUID.randomUUID(),UUID.randomUUID());}))
                    .isInstanceOf(Problem.class).hasMessageContaining("headroom");
            }
            assertThat(sql.fetchOne("SELECT count(*) FROM effects").get(0,Integer.class)).isEqualTo(1);
            // Already accepted outcomes do not use the intake/dispatch gate.
            sql.transaction(configuration->{var tx=DSL.using(configuration);assertThat(Database.workersMayWrite(tx)).isTrue();
                tx.execute("UPDATE effects SET value=3 WHERE event_id=?",accepted);});
            assertThat(sql.fetchOne("SELECT value FROM effects WHERE event_id=?",accepted).get(0,Integer.class)).isEqualTo(3);
            assertThat(db.container().execInContainer("rm","--","/var/lib/postgresql/cutover-pressure-fixture").getExitCode()).isZero();
            awaitState(db,"HEALTHY");Database.requireDurability(sql,true);Database.requireDurability(sql,false);
        }
    }

    @Test void runtimeCanReadOnlyTheFixedObservationAndCannotForgeIt() throws Exception {
        try(var db=new DatabaseFixture("reliability")) {
            var sql=db.sql();sql.execute("CREATE ROLE cutover_probe_reader; GRANT USAGE ON SCHEMA cutover_ops TO cutover_probe_reader; GRANT EXECUTE ON FUNCTION cutover_ops.volume_status() TO cutover_probe_reader");
            sql.transaction(configuration->{var tx=DSL.using(configuration);tx.execute("SET LOCAL ROLE cutover_probe_reader");
                assertThat(tx.fetchOne("SELECT cutover_ops.volume_status()->>'state'").get(0,String.class)).isEqualTo("HEALTHY");});
            for(String forbidden:new String[]{"SELECT pg_read_file('/etc/passwd')","CREATE TABLE cutover_ops.forgery(value text)",
                "CREATE OR REPLACE FUNCTION cutover_ops.volume_status() RETURNS jsonb LANGUAGE sql AS 'SELECT ''{}''::jsonb'"}) {
                assertThatThrownBy(()->sql.transaction(configuration->{var tx=DSL.using(configuration);tx.execute("SET LOCAL ROLE cutover_probe_reader");tx.execute(forbidden);}))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class);
            }
            // Stop the actual probe, retain its last report, and observe expiry using real time.
            var stopped=db.container().execInContainer("sh","-c","read -r probe_pid </tmp/volume-probe.pid; kill -TERM \"$probe_pid\"");
            assertThat(stopped.getExitCode()).isZero();awaitState(db,"STALE");
            assertThatThrownBy(()->Database.requireDurability(sql,true)).isInstanceOf(Problem.class);
            assertThat(db.container().execInContainer("sh","-c","printf 'not-json' >/run/cutover-volume/observation.json").getExitCode()).isZero();
            assertThat(StorageBudget.status(sql).path("volume").path("state").asString()).isEqualTo("UNAVAILABLE");
            assertThatThrownBy(()->Database.requireDurability(sql,false)).isInstanceOf(Problem.class);
            assertThat(db.container().execInContainer("rm","--","/run/cutover-volume/observation.json").getExitCode()).isZero();
            assertThat(StorageBudget.status(sql).path("volume").path("state").asString()).isEqualTo("UNAVAILABLE");
        }
    }
    private static void awaitState(DatabaseFixture db,String expected) throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(!expected.equals(StorageBudget.status(db.sql()).path("volume").path("state").asString())) {
            if(System.nanoTime()>deadline)fail("Volume observation did not reach "+expected+": "+JsonSupport.write(StorageBudget.status(db.sql())));
            Thread.sleep(100);
        }
    }
}
