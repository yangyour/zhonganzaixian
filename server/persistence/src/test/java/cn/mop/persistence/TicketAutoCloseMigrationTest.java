package cn.mop.persistence;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
/** Static migration selection and resource checks only, not PostgreSQL execution. */
class TicketAutoCloseMigrationTest {
    @Test void migrationIsExplicitAndOrdered()throws Exception{
        TicketAutoCloseSchemaVerifier.resource();var mode=TestSchemaMigration.requestedMode(new String[]{"--apply-ticket-autoclose"});
        assertEquals(TestSchemaMigration.Mode.APPLY_TICKET_AUTOCLOSE,mode);
        assertTrue(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_LIFECYCLE_APPLIED));
        assertFalse(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_AUTOCLOSE_APPLIED));
        assertThrows(IllegalStateException.class,()->TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.RUNTIME_CONFIGURATION_APPLIED));
        assertFalse(TestSchemaMigration.shouldMigrate(TestSchemaMigration.Mode.READ_ONLY_CHECK,TestSchemaMigration.Plan.TICKET_AUTOCLOSE_APPLIED));
    }
    @Test void dueQueueAndWorkerWritesAreBoundToImmutableSolutionAndFencedLease()throws Exception{
        try(var in=getClass().getResourceAsStream("/db/migration/"+TicketAutoCloseSchemaVerifier.SCRIPT)){
            assertNotNull(in);String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            for(String marker:new String[]{"PRIMARY KEY(tenant_id,ticket_id,solution_version)","source.action<>'SUBMIT_RESOLUTION'","NEW.fencing_token<>OLD.fencing_token+1","job.lease_until<=clock_timestamp()","job.fencing_token IS DISTINCT FROM NEW.worker_fencing_token","NEW.solution_snapshot_hash IS DISTINCT FROM job.solution_hash","AFTER INSERT ON public.mop_ticket_lifecycle_revision","BLOCKED_CONFIG","Worker lifecycle requires current fenced due lease"})assertTrue(sql.contains(marker),marker);
            assertFalse(sql.contains("SECURITY DEFINER"));assertFalse(sql.contains("GRANT DELETE"));assertFalse(sql.contains("UPDATE public.mop_ticket SET"));
        }
    }
}
