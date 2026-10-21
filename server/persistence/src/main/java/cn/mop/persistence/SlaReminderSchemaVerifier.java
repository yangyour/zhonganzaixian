package cn.mop.persistence;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
final class SlaReminderSchemaVerifier {
    static final String SCRIPT="V45__sla_reminder_intents.sql",SHA256="d9626559c8a74299a703a094689e3dcf153cc54ec139a685d2cad1c7ebbd7507";
    static void resource()throws Exception{try(var in=SlaReminderSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed SLA reminder migration");}}
    static void verify(Connection c)throws SQLException{
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        for(var table:Map.of("mop_ticket_sla_scan_job",11,"mop_ticket_sla_reminder",13,"mop_sla_notification_intent",8).entrySet()){
            verifyRuntimeAcl(c,table.getKey(),table.getKey().equals("mop_ticket_sla_scan_job")?Set.of("lifecycle_version","status","next_at","lease_owner","lease_until","fencing_token","attempts","last_outcome","observed_at"):Set.of());
            requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public."+table.getKey()+"'::regclass");
            requireTrue(c,"SELECT count(*)="+table.getValue()+" FROM information_schema.columns WHERE table_schema='public' AND table_name='"+table.getKey()+"'");
            requireTrue(c,"SELECT count(*)>0 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public."+table.getKey()+"'::regclass");
        }
        requireTrue(c,"SELECT count(*)=1 AND bool_and(pg_get_constraintdef(oid)='PRIMARY KEY (tenant_id, ticket_id, clock, progress)') FROM pg_constraint WHERE conrelid='public.mop_ticket_sla_reminder'::regclass AND contype='p'");
        requireTrue(c,"SELECT count(*)=1 AND bool_and(condeferrable AND condeferred) FROM pg_constraint WHERE conrelid='public.mop_ticket_sla_reminder'::regclass AND confrelid='public.mop_sla_notification_intent'::regclass AND contype='f'");
        trigger(c,"mop_ticket_sla_scan_job","mop_ticket_sla_scan_guard",31,true);
        trigger(c,"mop_ticket_sla_reminder","mop_ticket_sla_reminder_guard",31,true);
        trigger(c,"mop_sla_notification_intent","mop_sla_notification_intent_guard",31,true);
        trigger(c,"mop_ticket_lifecycle_revision","mop_ticket_sla_scan_enqueue",5,false);
        function(c,"mop_ticket_sla_scan_guard","e8c99694579b193c81ba8e1e720579a2dfcfbd4eeedba0a523883ea430c0ed1d");
        function(c,"mop_ticket_sla_scan_enqueue","3bb6e9a9faa63517e2215623aaa7284fa7eff70c8e93b3dcfa2c9825cecb064b");
        function(c,"mop_ticket_sla_reminder_guard","b24bf84fc0d35779817999a0e8846f6238ed6dd0bc4ed07d7b54f72b7cd26461");
        function(c,"mop_sla_notification_intent_guard","041bca670b91b8b7f88c11b6b23b87b23cecd2cefb5666617d3d210de1dc9ce1");
    }
    private static void trigger(Connection c,String table,String name,int type,boolean only)throws SQLException{requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgname='"+name+"' AND tgtype="+type+" AND tgfoid='public."+name+"()'::regprocedure) FROM pg_trigger WHERE tgrelid='public."+table+"'::regclass AND NOT tgisinternal"+(only?"":" AND tgname='"+name+"'"));}
    private static void function(Connection c,String name,String hash)throws SQLException{requireTrue(c,"SELECT NOT p.prosecdef AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')='"+hash+"' AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid='public."+name+"()'::regprocedure");}
}
