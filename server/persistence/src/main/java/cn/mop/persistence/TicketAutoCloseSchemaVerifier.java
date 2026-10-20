package cn.mop.persistence;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
final class TicketAutoCloseSchemaVerifier {
    static final String SCRIPT="V34__ticket_autoclose_jobs.sql";
    static final String SHA256="0b3bf01db63bcae757c4b80ba9f10979c342ae95dd339121ce91a979553e47e4";
    static void resource()throws Exception{try(var in=TicketAutoCloseSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed auto-close migration");}}
    static void verify(Connection c)throws SQLException{
        TicketLifecycleSchemaVerifier.verify(c);
        verifyRuntimeAcl(c,"mop_ticket_autoclose_job",Set.of("status","lease_owner","lease_until","fencing_token","attempts","completed_at","last_error"));
        requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_ticket_autoclose_job'::regclass");
        requireTrue(c,"SELECT count(*)=13 FROM information_schema.columns WHERE table_schema='public' AND table_name='mop_ticket_autoclose_job'");
        requireTrue(c,"SELECT count(*)>0 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public.mop_ticket_autoclose_job'::regclass");
        trigger(c,"mop_ticket_autoclose_job","mop_ticket_autoclose_guard",31);
        function(c,"mop_ticket_autoclose_guard","82d4ec56a5eada4c9d3eee3a9441889dd7ca1007f517243dde74c07c5e0f4f4f");
        lifecycleExtras(c);
    }
    static void lifecycleExtras(Connection c)throws SQLException{
        trigger(c,"mop_ticket_lifecycle_revision","mop_ticket_autoclose_revision_guard",7);
        trigger(c,"mop_ticket_lifecycle_revision","mop_ticket_autoclose_enqueue",5);
        function(c,"mop_ticket_autoclose_revision_guard","b370818b8f76143c5ec7a38c7bb0073c2a1f1407a5b01999e4d21810513c19bb");
        function(c,"mop_ticket_autoclose_enqueue","aa1dcfb4a1f519de4ff35e8c8cb433594b8373b0b622d428a09783d4f2c7b3a5");
        requireTrue(c,"SELECT count(*)=1 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public.mop_ticket_lifecycle_revision'::regclass AND conname='mop_ticket_lifecycle_worker_fence'");
    }
    private static void trigger(Connection c,String table,String name,int type)throws SQLException{requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype="+type+" AND tgfoid='public."+name+"()'::regprocedure) FROM pg_trigger WHERE tgrelid='public."+table+"'::regclass AND tgname='"+name+"' AND NOT tgisinternal");}
    private static void function(Connection c,String name,String hash)throws SQLException{requireTrue(c,"SELECT NOT p.prosecdef AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')='"+hash+"' AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid='public."+name+"()'::regprocedure");}
}
