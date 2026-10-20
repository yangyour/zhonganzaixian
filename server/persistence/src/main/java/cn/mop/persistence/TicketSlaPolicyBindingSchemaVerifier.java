package cn.mop.persistence;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
final class TicketSlaPolicyBindingSchemaVerifier {
    static final String SCRIPT="V35__ticket_sla_policy_bindings.sql";
    static final String SHA256="f249f843c35eb547da719036db3f35d0168f89cb971a85ddf084191a3c632600";
    static void resource()throws Exception{try(var in=TicketSlaPolicyBindingSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed SLA policy binding migration");}}
    static void verify(Connection c)throws SQLException{
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        verifyRuntimeAcl(c,"mop_ticket_sla_policy_binding",Set.of());
        requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_ticket_sla_policy_binding'::regclass");
        requireTrue(c,"SELECT count(*)=11 FROM information_schema.columns WHERE table_schema='public' AND table_name='mop_ticket_sla_policy_binding'");
        requireTrue(c,"SELECT count(*)>0 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public.mop_ticket_sla_policy_binding'::regclass");
        requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=31 AND tgname='mop_ticket_sla_policy_binding_guard' AND tgfoid='public.mop_ticket_sla_policy_binding_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_ticket_sla_policy_binding'::regclass AND NOT tgisinternal");
        requireTrue(c,"SELECT NOT p.prosecdef AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')='0cee59c28e4afabe595b2def10c2bfb2669f3157948a89f9a73a0c0fa758fa44' AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid='public.mop_ticket_sla_policy_binding_guard()'::regprocedure");
    }
}
