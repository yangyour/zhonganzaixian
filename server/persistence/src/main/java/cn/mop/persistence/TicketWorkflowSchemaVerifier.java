package cn.mop.persistence;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Candidate V33 verifier; execution evidence, not this class, determines whether it has been applied. */
final class TicketWorkflowSchemaVerifier {
    static final String SCRIPT="V33__ticket_lifecycle_and_durable_clocks.sql";
    static final String SHA256="a33326b73d23d112b50ece07dc59938722cbc544e5c8b32f7d8db143ab6f35e7";
    static void resource()throws Exception{try(var in=TicketWorkflowSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Ticket workflow migration differs from reviewed candidate");}}
    static void verify(Connection c)throws SQLException{
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        for(String table:List.of("mop_ticket_workflow_event","mop_ticket_workflow_outbox","mop_ticket_workflow_clock","mop_ticket_workflow_clock_receipt")){
            verifyRuntimeAcl(c,table,Set.of());
            requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)","public."+table);
            requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal","public."+table);
        }
        requireTrue(c,"SELECT count(*)=2 AND bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog']) FROM pg_proc WHERE oid IN ('public.mop_ticket_workflow_guard()'::regprocedure,'public.mop_ticket_workflow_immutable()'::regprocedure)");
        requireTrue(c,"SELECT count(*)>=25 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid IN ('public.mop_ticket_workflow_event'::regclass,'public.mop_ticket_workflow_outbox'::regclass,'public.mop_ticket_workflow_clock'::regclass,'public.mop_ticket_workflow_clock_receipt'::regclass)");
    }
}
