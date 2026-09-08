package dev.cutover.adapter;

import dev.cutover.platform.*;
import dev.cutover.testing.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class MigrationProcessFaultsTest {
    private static DatabaseFixture database;
    private MutableClock clock;
    private MigrationProcessFaults faults;
    private static final class Exit extends RuntimeException {}
    @BeforeAll static void start() { database = new DatabaseFixture("equipment-adapter"); }
    @AfterAll static void stop() { database.close(); }
    @BeforeEach void reset() {
        database.reset(); clock = new MutableClock(Instant.now());
        faults = new MigrationProcessFaults(database.sql(), clock, code -> { assertThat(code).isEqualTo(73); throw new Exit(); });
    }
    private UUID session(Instant created) {
        UUID id = UUID.randomUUID();
        database.sql().execute("INSERT INTO migration_sessions(session_id,site_id,zone_id,source_owner,target_owner,source_epoch,phase,actor,reason,created_at) VALUES (?,'site-a','ambient','legacy-core','execution-service',0,'DRAINING','supervisor','Verify a durable phase boundary',?::timestamptz)", id, OffsetDateTime.ofInstant(created, ZoneOffset.UTC));
        return id;
    }
    private JsonNode request(UUID id) {
        var body = JsonSupport.MAPPER.createObjectNode().put("phase", "DRAINING").put("expectedVersion", 0).put("reason", "Terminate only after the selected migration phase has committed.");
        if (id != null) body.put("sessionId", id.toString()); return body;
    }
    @Test void oneShotConsumptionAndAuditCommitBeforeTheProcessTerminates() {
        UUID session = session(clock.instant()); var body = request(session);
        var armed = faults.arm("scenario-driver", "site-a", "arm", body);
        faults.reached("RECONCILING", session);
        assertThatThrownBy(() -> faults.reached("DRAINING", session)).isInstanceOf(Exit.class);
        assertThat(database.sql().fetchOne("SELECT remaining,fired_session_id,version FROM migration_process_faults").intoArray()).containsExactly(0, session, 2L);
        assertThat(database.sql().fetchOne("SELECT count(*) FROM audit WHERE action='migration-process-fault-fired'").get(0, Integer.class)).isEqualTo(1);
        var restarted = new AtomicInteger(); new MigrationProcessFaults(database.sql(), clock, restarted::set).reached("DRAINING", session);
        assertThat(restarted.get()).isZero(); assertThat(faults.arm("scenario-driver", "site-a", "arm", body)).isEqualTo(armed);
    }
    @Test void anUnselectedExistingSessionCannotConsumeAFutureSessionFault() {
        UUID old = session(clock.instant().minusSeconds(1)); faults.arm("scenario-driver", "site-a", "future", request(null));
        faults.reached("DRAINING", old);
        assertThat(database.sql().fetchOne("SELECT remaining FROM migration_process_faults").get(0, Integer.class)).isEqualTo(1);
        database.sql().execute("UPDATE migration_sessions SET phase='CANCELLED' WHERE session_id=?", old);
        clock.advance(Duration.ofSeconds(1)); UUID next = session(clock.instant());
        assertThatThrownBy(() -> faults.reached("DRAINING", next)).isInstanceOf(Exit.class);
    }
    @Test void clearingUsesTheFaultVersionAndCrossSiteRequestsStayOutsideTheControlSurface() {
        var armed = faults.arm("scenario-driver", "site-a", "clearable", request(null));
        var clear = JsonSupport.MAPPER.createObjectNode().put("expectedVersion", 1).put("reason", "Clear this phase fault before the next migration begins.");
        var result = faults.clear("scenario-driver", "site-a", Database.uuid(armed, "faultId"), "clear", clear);
        assertThat(result.path("state").asString()).isEqualTo("CLEARED");
        assertThatThrownBy(() -> faults.arm("scenario-driver", "site-b", "wrong-site", request(null))).isInstanceOf(Problem.class);
        UUID id = session(clock.instant()); faults.reached("DRAINING", id);
        assertThat(database.sql().fetchOne("SELECT remaining FROM migration_process_faults").get(0, Integer.class)).isZero();
    }
}
