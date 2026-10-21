package cn.mop.persistence;
import java.sql.*;import java.security.MessageDigest;import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Read-only catalog verification of explicit V47, never called by service startup. */
final class BackupRunSchemaVerifier {
 static final String SCRIPT="V47__backup_run_event_journal.sql",SHA256="5ca80f0715369d45b3a7606fb411e962576a15d9f49b8921a70a2c1d0af12f0c",FUNCTION_SHA256="e71a226915afda207d19aaa84011cc2fa012238c01a76bf979e2727334b20621";
 static final Map<String,String> CHECK_HASHES=Map.ofEntries(Map.entry("mop_backup_run_event_action_check","3993b88f033c837b8ff2ebe14325a76a5db8a21b1cb4ae1803a8cf929356d4a1"),Map.entry("mop_backup_run_event_attempt_number_check","8654aab92f1b9074088b500103fd8af528dda639be5644564a485f40d14583d4"),Map.entry("mop_backup_run_event_check","62e07acd351517fd75d0cc099c14a11a739810bf1ceedf93f9689e1e93d6ede7"),Map.entry("mop_backup_run_event_check1","d3058646e40eba769c78d4ff2dddd61d2d268f0c2d5b9b88743c573ef3e4ee66"),Map.entry("mop_backup_run_event_check2","ccf8d7792a02adc47abec7c1292df350056ca78b78464f032c964e07febda606"),Map.entry("mop_backup_run_event_check3","957bd20baddc2dfcf58cd5cb23bb639a1f6cc4698fdc31ded49b5dd512bc4224"),Map.entry("mop_backup_run_event_check4","034f3dbd1d1263f769d993643ae001796505c38a9b01d4a8df28729250ed153f"),Map.entry("mop_backup_run_event_check5","85e61e2e5bf4254c8737d5d536f8ccc9af72452e7c2408c80408093f69bba67e"),Map.entry("mop_backup_run_event_check6","24f85eddd794466813e6ef68f1196770e80d31a00d91058f4a190a4cb693deb1"),Map.entry("mop_backup_run_event_command_sha256_check","ba235625a1ad8d49af48b47a355c308c92dcaab6b61905a1e976630e384abdf5"),Map.entry("mop_backup_run_event_device_execution_authorized_check","d5e6bc5ed36e54306c3d17a2bca2398f6dbd343a8ad022a3a93c33e7af3a4cb8"),Map.entry("mop_backup_run_event_record_source_check","43e942515c1cf14db076476b6a665d2cd07a62f8dd8a5ab4565bc40f798e005b"),Map.entry("mop_backup_run_event_snapshot_format_check","463f7679882a0842dba8b50a6913fd468e9afe55e9a65c9a114d6272857a74df"),Map.entry("mop_backup_run_event_state_check","db0f6f40f7ee0e0a34bc6c3aa40d784acb0973ba06e5e3f68db53c3ceb7a53d5"),Map.entry("mop_backup_run_event_trace_id_check","f4b0fb98c5e3cc64ae24933ef1236fd9d362b4ade2c42a9f7139929a42917c45"),Map.entry("mop_backup_run_event_version_check","c32bff184723672b0b4e28a45f2045192fb38ae0d237e648a826b3a50f03ceab"));
 static void resource()throws Exception{try(var in=BackupRunSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed backup run migration");}}
 static void requireCheckHashes(Map<String,String> observed){if(CHECK_HASHES.size()!=16||!CHECK_HASHES.equals(observed))throw new IllegalStateException("Backup run constraints changed");}
 static void verify(Connection c)throws SQLException{
  String table="mop_backup_run_event";
  requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
  verifyColumns(c,table,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("run_id","uuid"),Map.entry("asset_id","uuid"),Map.entry("version","bigint"),Map.entry("command_id","uuid"),Map.entry("command_sha256","character varying(64)"),Map.entry("action","character varying(16)"),Map.entry("state","character varying(16)"),Map.entry("queued_at","timestamp(3) with time zone"),Map.entry("observed_at","timestamp(3) with time zone"),Map.entry("attempt_number","integer"),Map.entry("lease_request_id","uuid"),Map.entry("lease_event_version","bigint"),Map.entry("lease_owner","character varying(128)"),Map.entry("fencing_token","bigint"),Map.entry("verification_reference","character varying(71)"),Map.entry("snapshot","bytea"),Map.entry("snapshot_sha256","character varying(64)"),Map.entry("snapshot_format","character varying(32)"),Map.entry("trace_id","character varying(128)"),Map.entry("record_source","character varying(40)"),Map.entry("device_execution_authorized","boolean")),Set.of("lease_request_id","lease_event_version","lease_owner","fencing_token","verification_reference"),true);
  verifyRuntimeAcl(c,table,Set.of());
  requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND NOT relispartition AND NOT relrowsecurity AND NOT relforcerowsecurity AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_backup_run_event'::regclass");
  requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent='public.mop_backup_run_event'::regclass OR inhrelid='public.mop_backup_run_event'::regclass) AND NOT EXISTS(SELECT 1 FROM pg_rewrite WHERE ev_class='public.mop_backup_run_event'::regclass)");
  requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t CROSS JOIN LATERAL aclexplode(COALESCE(t.relacl,acldefault('r',t.relowner))) a WHERE t.oid='public.mop_backup_run_event'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
  requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t JOIN pg_attribute x ON x.attrelid=t.oid CROSS JOIN LATERAL aclexplode(x.attacl) a WHERE t.oid='public.mop_backup_run_event'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
  requireConstraint(c,table,"p",List.of("tenant_id","run_id","version"),null,List.of(),false);
  requireConstraint(c,table,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);
  requireConstraint(c,table,"u",List.of("tenant_id","run_id","command_id"),null,List.of(),false);
  requireConstraint(c,table,"f",List.of("tenant_id","asset_id","lease_event_version"),"mop_backup_device_lease_event",List.of("tenant_id","asset_id","version"),false);
  requireTrue(c,"SELECT count(*)=20 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public.mop_backup_run_event'::regclass");
  verifyCheckDefinitions(c);
  requireTrue(c,"SELECT count(*)=4 AND bool_and(indisvalid AND indisready AND indislive) FROM pg_index WHERE indrelid='public.mop_backup_run_event'::regclass");
  requireTrue(c,"SELECT i.indisunique AND i.indimmediate AND i.indnkeyatts=3 AND i.indnatts=3 AND pg_get_expr(i.indpred,i.indrelid)='((action)::text = ''START''::text)' AND ARRAY(SELECT a.attname::text FROM unnest(i.indkey) WITH ORDINALITY k(num,pos) JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.num ORDER BY k.pos)=ARRAY['tenant_id','asset_id','lease_request_id']::text[] FROM pg_index i WHERE i.indexrelid='public.mop_backup_attempt_lease_once'::regclass");
  requireTrue(c,"SELECT pg_get_indexdef(indexrelid)='CREATE INDEX mop_backup_run_asset_history ON public.mop_backup_run_event USING btree (tenant_id, asset_id, run_id, version DESC)' FROM pg_index WHERE indexrelid='public.mop_backup_run_asset_history'::regclass");
  requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgname='mop_backup_run_event_guard' AND tgtype=31 AND tgfoid='public.mop_backup_run_event_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_backup_run_event'::regclass AND NOT tgisinternal");
  requireTrue(c,"SELECT NOT p.prosecdef AND p.provolatile='v' AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner AND (a.is_grantable OR a.privilege_type<>'EXECUTE' OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime'))) FROM pg_proc p WHERE p.oid='public.mop_backup_run_event_guard()'::regprocedure",FUNCTION_SHA256);
 }
    static void verifyCheckDefinitions(Connection c)throws SQLException {
        String timezone,dateStyle;
        try(var q=c.createStatement();var rows=q.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")) {
            if(!rows.next())throw new IllegalStateException("Missing schema session settings");
            timezone=rows.getString(1);dateStyle=rows.getString(2);
        }
        try {
            Map<String,String> definitions=new TreeMap<>();
            // Evaluate local settings in the same statement, including autocommit callers.
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT conname,CASE WHEN set_config('TimeZone','UTC',true)='UTC' AND set_config('DateStyle','ISO, MDY',true)='ISO, MDY' THEN encode(sha256(convert_to(pg_get_constraintdef(oid),'UTF8')),'hex') END FROM pg_constraint WHERE conrelid='public.mop_backup_run_event'::regclass AND contype='c' ORDER BY conname")) {
                while(rows.next())definitions.put(rows.getString(1),rows.getString(2));
            }
            requireCheckHashes(definitions);
        } finally {
            try(var restore=c.prepareStatement("SELECT set_config('TimeZone',?,true),set_config('DateStyle',?,true)")) {
                restore.setString(1,timezone);restore.setString(2,dateStyle);restore.execute();
            }
        }
    }

}
