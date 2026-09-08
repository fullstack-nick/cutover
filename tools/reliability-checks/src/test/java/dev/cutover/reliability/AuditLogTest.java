package dev.cutover.reliability;

import dev.cutover.platform.AuditLog;
import dev.cutover.platform.Problem;
import dev.cutover.testing.DatabaseFixture;
import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class AuditLogTest {
    static DatabaseFixture database;
    @BeforeAll static void start(){database=new DatabaseFixture("reliability");}
    @AfterAll static void stop(){if(database!=null)database.close();}
    @BeforeEach void seed(){
        database.reset();
        for(int n=1;n<=4;n++)database.sql().execute("""
                INSERT INTO audit(audit_id,site_id,actor,action,resource_id,reason,before_version,after_version,outcome,detail,occurred_at)
                VALUES (?::uuid,?,'fictional-supervisor','command-investigation',?,'Inspect retained physical history.',3,4,'RECORDED',
                    '{"privateDiagnostic":"internal-only"}'::jsonb,'2026-09-08T10:00:00Z')
                """,id(n),n==4?"site-b":"site-a",n==2?"second-resource":"first-resource");
    }
    UUID id(int value){return UUID.fromString("00000000-0000-0000-0000-00000000000"+value);}
    @Test void tiedTimestampsPageWithoutDuplicatesAndForeignCursorsNeverRevealOtherSites(){
        var first=AuditLog.page(database.sql(),"site-a","adapter",1,null,null);
        assertThat(first.path("items").get(0).path("id").asString()).isEqualTo(id(3).toString());
        var second=AuditLog.page(database.sql(),"site-a","adapter",1,UUID.fromString(first.path("nextCursor").asString()),null);
        assertThat(second.path("items").get(0).path("id").asString()).isEqualTo(id(2).toString());
        var third=AuditLog.page(database.sql(),"site-a","adapter",1,UUID.fromString(second.path("nextCursor").asString()),null);
        assertThat(third.path("items").get(0).path("id").asString()).isEqualTo(id(1).toString());assertThat(third.path("nextCursor").isNull()).isTrue();
        assertThat(AuditLog.page(database.sql(),"site-a","adapter",25,id(4),null).path("items").size()).isZero();
        assertThat(AuditLog.page(database.sql(),"site-a","adapter",25,UUID.randomUUID(),null).path("items").size()).isZero();
    }
    @Test void scopedProjectionIsReadOnlyBoundedAndOmitsInternalDetails(){
        database.sql().transaction(configuration->{
            var sql=DSL.using(configuration);sql.execute("SET TRANSACTION READ ONLY");
            var page=AuditLog.page(sql,"site-a","adapter",25,null,"first-resource");
            assertThat(page.path("items").size()).isEqualTo(2);assertThat(page.toString()).doesNotContain("privateDiagnostic","internal-only","site-b");
            assertThat(page.path("items").get(0).path("reason").asString()).isEqualTo("Inspect retained physical history.");
            assertThat(page.path("items").get(0).path("afterVersion").asLong()).isEqualTo(4);
            assertThat(sql.fetchOne("SELECT pg_current_xact_id_if_assigned()::text").get(0)).isNull();
        });
        assertThatThrownBy(()->AuditLog.page(database.sql(),"site-a","adapter",101,null,null)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->AuditLog.page(database.sql(),"site-a","adapter",25,null,"x".repeat(129))).isInstanceOf(Problem.class);
    }
}
