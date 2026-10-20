package cn.mop.persistence;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Resource/selection checks only. No claim that PostgreSQL applied or exercised this candidate. */
class TicketLifecycleMigrationTest {
    @Test void candidateRequiresExplicitModeAndCompletePreviousHistory() throws Exception {
        TicketLifecycleSchemaVerifier.resource();
        var mode=TestSchemaMigration.requestedMode(new String[]{"--apply-ticket-lifecycle"});
        assertEquals(TestSchemaMigration.Mode.APPLY_TICKET_LIFECYCLE,mode);
        assertTrue(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.RUNTIME_CONFIGURATION_APPLIED));
        assertFalse(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_LIFECYCLE_APPLIED));
        assertFalse(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.READ_ONLY_CHECK,TestSchemaMigration.Plan.TICKET_LIFECYCLE_APPLIED));
        assertThrows(IllegalStateException.class,()->TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.EXPORT_INBOX_APPLIED));
    }
    @Test void storageIsAdditiveAppendOnlyAndTenantScoped() throws Exception {
        try(var in=getClass().getResourceAsStream("/db/migration/"+TicketLifecycleSchemaVerifier.SCRIPT)){
            assertNotNull(in);String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(sql.contains("current_database() <> 'mop_test'"));
            assertFalse(sql.contains("ALTER TABLE public.mop_ticket "));
            assertTrue(sql.contains("PRIMARY KEY(tenant_id,ticket_id,version)"));
            assertTrue(sql.contains("FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id)"));
            assertTrue(sql.contains("pg_advisory_xact_lock"));
            assertTrue(sql.contains("NEW.version<>prior.version+1"));
            assertTrue(sql.contains("NEW.response_due_at IS DISTINCT FROM prior.response_due_at"));
            assertTrue(sql.contains("NEW.recovery_due_at IS DISTINCT FROM prior.recovery_due_at"));
            assertTrue(sql.contains("GRANT SELECT,INSERT ON"));
            assertFalse(sql.contains("GRANT UPDATE"));
            assertTrue(sql.contains("actor_permission_version IS NOT NULL"));
            assertTrue(sql.contains("Candidate resource pin does not mean this migration has been executed"));
        }
    }
    @Test void frozenRegistrationCannotBecomeAnUpdatedLifecycleProjection() throws Exception {
        try(var in=getClass().getResourceAsStream("/db/migration/V4__durable_customer_report_registration.sql")){
            assertNotNull(in);String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(sql.contains("CHECK(state IS NULL)"));
            assertTrue(sql.contains("CHECK(version=1)"));
            assertTrue(sql.contains("CHECK(status='PENDING')"));
        }
    }
}
