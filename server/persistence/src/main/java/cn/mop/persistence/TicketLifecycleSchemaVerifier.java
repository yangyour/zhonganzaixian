package cn.mop.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Reviewed candidate resource; pinning does not assert execution on any database. */
final class TicketLifecycleSchemaVerifier {
    static final String SCRIPT="V42__ticket_lifecycle_revisions.sql";
    static final String SHA256="01923cef9be16d7445b8aa4ada7ee564ad0210a21bad59edca17eb48e9a908e9";
    static void resource() throws Exception {
        try(var in=TicketLifecycleSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){
            if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))
                throw new IllegalStateException("Unreviewed ticket lifecycle migration");
        }
    }
    static void verify(Connection c) throws SQLException {
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        for(String table:Set.of("mop_ticket_resolution_snapshot","mop_ticket_lifecycle_revision")){
            verifyRuntimeAcl(c,table,Set.of());
            requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public."+table+"'::regclass");
            requireTrue(c,"SELECT count(*)>0 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public."+table+"'::regclass");
        }
        requireTrue(c,"SELECT count(*)=8 FROM information_schema.columns WHERE table_schema='public' AND table_name='mop_ticket_resolution_snapshot'");
        requireTrue(c,"SELECT count(*)=(CASE WHEN to_regclass('public.mop_ticket_autoclose_job') IS NULL THEN 21 ELSE 23 END) FROM information_schema.columns WHERE table_schema='public' AND table_name='mop_ticket_lifecycle_revision'");
        requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgname='mop_ticket_lifecycle_guard' AND tgtype=31 AND tgfoid='public.mop_ticket_lifecycle_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_ticket_lifecycle_revision'::regclass AND tgname='mop_ticket_lifecycle_guard' AND NOT tgisinternal");
        requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgname='mop_ticket_resolution_immutable' AND tgtype=27 AND tgfoid='public.mop_ticket_resolution_immutable()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_ticket_resolution_snapshot'::regclass AND NOT tgisinternal");
        requireTrue(c,"SELECT count(*)=(CASE WHEN to_regclass('public.mop_ticket_sla_scan_job') IS NOT NULL THEN 5 WHEN to_regclass('public.mop_ticket_autoclose_job') IS NULL THEN 2 ELSE 4 END) FROM pg_trigger WHERE tgrelid='public.mop_ticket_lifecycle_revision'::regclass AND NOT tgisinternal");
        try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT to_regclass('public.mop_ticket_autoclose_job') IS NOT NULL")){rows.next();if(rows.getBoolean(1))TicketAutoCloseSchemaVerifier.lifecycleExtras(c);}
        try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT to_regclass('public.mop_ticket_sla_policy_binding') IS NOT NULL")){rows.next();if(rows.getBoolean(1))TicketSlaPolicyBindingSchemaVerifier.verify(c);}
        try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT to_regclass('public.mop_ticket_sla_scan_job') IS NOT NULL")){rows.next();if(rows.getBoolean(1))SlaReminderSchemaVerifier.verify(c);}
        verifyRuntimeAcl(c,"mop_ticket_workflow_authority",Set.of());
        requireTrue(c,"SELECT count(*)=3 FROM information_schema.columns WHERE table_schema='public' AND table_name='mop_ticket_workflow_authority'");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM public.mop_ticket_workflow_event e LEFT JOIN public.mop_ticket_workflow_authority a USING(tenant_id,ticket_id) WHERE a.model IS DISTINCT FROM 'WORKFLOW_V33') AND NOT EXISTS(SELECT 1 FROM public.mop_ticket_lifecycle_revision e LEFT JOIN public.mop_ticket_workflow_authority a USING(tenant_id,ticket_id) WHERE a.model IS DISTINCT FROM 'REVISION_V42')");
        for(String table:Set.of("mop_ticket_workflow_event","mop_ticket_lifecycle_revision")) requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=7 AND tgfoid='public.mop_ticket_workflow_authority_claim()'::regprocedure) FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND tgname='mop_ticket_workflow_authority_claim' AND NOT tgisinternal",table);
        function(c,"mop_ticket_workflow_authority_immutable","87bb4efcfd0646d71f4cf63554bd36666cf6305e3131d92519ee56dedefb8294");
        function(c,"mop_ticket_workflow_authority_claim","f02025d783f5e41e9b302a5353222ed688198c7fa1dc9761df63c007eb184da8");
        function(c,"mop_ticket_lifecycle_guard","a9f6f99f4aa4760d0d8d6fa7c76ba95fa4e89a02e919b46935900edf151bd6b3");
        function(c,"mop_ticket_resolution_immutable","bcb49a5acc0097b3cc5d71dbb8b3bbdb3c168ddf76a421bfabcfe478e30fdf87");
    }
    private static void function(Connection c,String name,String hash) throws SQLException {
        requireTrue(c,"SELECT NOT p.prosecdef AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')='"+hash+"' AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid='public."+name+"()'::regprocedure");
    }
}
