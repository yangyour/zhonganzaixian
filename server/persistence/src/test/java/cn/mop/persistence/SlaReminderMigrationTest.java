package cn.mop.persistence;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class SlaReminderMigrationTest {
    @Test void explicitOrderedCandidateCannotSkipPolicySchema()throws Exception{
        SlaReminderSchemaVerifier.resource();var mode=TestSchemaMigration.requestedMode(new String[]{"--apply-sla-reminders"});assertEquals(TestSchemaMigration.Mode.APPLY_SLA_REMINDERS,mode);
        assertTrue(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_SLA_POLICY_APPLIED));assertFalse(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.SLA_REMINDERS_APPLIED));assertThrows(IllegalStateException.class,()->TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_AUTOCLOSE_APPLIED));
        assertFalse(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.READ_ONLY_CHECK,TestSchemaMigration.Plan.SLA_REMINDERS_APPLIED));
    }
    @Test void reminderAndIntentAreAtomicImmutableScopedEvidence()throws Exception{
        try(var in=getClass().getResourceAsStream("/db/migration/"+SlaReminderSchemaVerifier.SCRIPT)){assertNotNull(in);String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            for(String marker:new String[]{"PRIMARY KEY(tenant_id,ticket_id,clock,progress)","DEFERRABLE INITIALLY DEFERRED","job.fencing_token<>NEW.worker_fencing_token","latest.state NOT IN ('NEW','IN_PROGRESS','WAITING_VENDOR','REOPENED')","Notification intent immutable","SLA reminder evidence immutable","NEW.payload ?& ARRAY","Lifecycle event must invalidate old SLA lease","elapsed_ms::numeric>=budget_ms::numeric*progress"})assertTrue(sql.contains(marker),marker);
            assertFalse(sql.contains("SECURITY DEFINER"));assertFalse(sql.contains("GRANT DELETE"));assertFalse(sql.contains("DELIVERED"));
        }
    }
}
