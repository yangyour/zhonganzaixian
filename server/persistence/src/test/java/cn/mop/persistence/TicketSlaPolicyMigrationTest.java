package cn.mop.persistence;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class TicketSlaPolicyMigrationTest {
    @Test void candidateIsExplicitAndSequential()throws Exception{
        TicketSlaPolicyBindingSchemaVerifier.resource();var mode=TestSchemaMigration.requestedMode(new String[]{"--apply-ticket-sla-policy"});assertEquals(TestSchemaMigration.Mode.APPLY_TICKET_SLA_POLICY,mode);
        assertTrue(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_AUTOCLOSE_APPLIED));assertFalse(TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_SLA_POLICY_APPLIED));assertThrows(IllegalStateException.class,()->TestSchemaMigration.shouldMigrate(mode,TestSchemaMigration.Plan.TICKET_LIFECYCLE_APPLIED));
    }
    @Test void bindingCannotOverwriteOpeningOrUseNullPolicy()throws Exception{
        try(var in=getClass().getResourceAsStream("/db/migration/"+TicketSlaPolicyBindingSchemaVerifier.SCRIPT)){assertNotNull(in);String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            for(String marker:new String[]{"PRIMARY KEY(tenant_id,ticket_id)","CHECK((policy_json->>'clock_policy'=clock_policy) IS TRUE)","Existing frozen opening policy cannot be replaced","publication.value_json IS DISTINCT FROM NEW.policy_json","Current engineer lifecycle capability required","SLA policy bindings are immutable","mop.runtime.configuration|"})assertTrue(sql.contains(marker),marker);
            assertFalse(sql.contains("UPDATE public.mop_ticket_lifecycle_revision"));assertFalse(sql.contains("SECURITY DEFINER"));assertFalse(sql.contains("GRANT UPDATE"));
        }
    }
}
