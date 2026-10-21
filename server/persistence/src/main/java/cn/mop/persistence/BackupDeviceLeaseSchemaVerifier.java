package cn.mop.persistence;
import java.sql.*;import java.security.MessageDigest;import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Read-only catalog verification of explicit V46, never called by service startup. */
final class BackupDeviceLeaseSchemaVerifier {
 static final String SCRIPT="V46__backup_device_coordination_journal.sql",SHA256="97d4dec874c03503326fbb609ea1708fa6601fd257fb664ca74cad7fa2032f98",FUNCTION_SHA256="a4635d0e12d4c11011cb10b27224a30a27bf01640b624a1649a5439f9f46b401";
 static final Map<String,String> CHECK_HASHES=Map.ofEntries(Map.entry("mop_backup_device_lease_event_action_check","57296efb2bac48aa12b1633a3d233841583a11ccd7461b51f62d6d2483cef5fb"),Map.entry("mop_backup_device_lease_event_check","19435cd15578bcd245bdb2b7f150723ebadd119cdb1ed42180a854aadf0d9029"),Map.entry("mop_backup_device_lease_event_check1","e8ab7e8b0af396a2e2f32fed68943cefd2e1e2bbe4d2a49a559e630d8a4bd7fd"),Map.entry("mop_backup_device_lease_event_check2","c237163462f7e7cc5edbe0e694cd4c3cecb337fee60bb94b126e1b85539520b5"),Map.entry("mop_backup_device_lease_event_check3","e64d4c0fe4b9870dd1387fea83d354b11054bdc26d0113b8da9f46b90158109f"),Map.entry("mop_backup_device_lease_event_check4","2341f38bbd854915580887da2d09caadf1929d6854becf27451ceacaa98b7dc0"),Map.entry("mop_backup_device_lease_event_device_execution_authorized_check","d5e6bc5ed36e54306c3d17a2bca2398f6dbd343a8ad022a3a93c33e7af3a4cb8"),Map.entry("mop_backup_device_lease_event_fencing_token_check","36593d24b189fba813f25b2d136ddf481795a4f4499b7b4192eb195a7b45cb04"),Map.entry("mop_backup_device_lease_event_lease_millis_check","a7c58cb93e7b84d746515a2cafdbf9cef534b3348d739c54ce4e6dc3b788fcbb"),Map.entry("mop_backup_device_lease_event_record_source_check","50e4e5345aab28f80c6372e6d55ad166065bfa7436b5f1d0c065af4148956a0a"),Map.entry("mop_backup_device_lease_event_trace_id_check","f4b0fb98c5e3cc64ae24933ef1236fd9d362b4ade2c42a9f7139929a42917c45"),Map.entry("mop_backup_device_lease_event_version_check","4a4bc9e75509adf601102a047a571e173cc3b356c5b082db5c89ed303a6d3899"),Map.entry("mop_backup_device_lease_event_worker_owner_check","d3c6a64b2441dcae211c76ec3cb6e229adcaf24d5101019aaa260cfa9a8455d0"));
 static void resource()throws Exception{try(var in=BackupDeviceLeaseSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed device coordination migration");}}
 static void requireCheckHashes(Map<String,String> observed){if(CHECK_HASHES.size()!=13||!CHECK_HASHES.equals(observed))throw new IllegalStateException("Device coordination constraints changed");}
 static void verify(Connection c)throws SQLException{
  String table="mop_backup_device_lease_event";
  requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
  verifyColumns(c,table,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("asset_id","uuid"),Map.entry("version","bigint"),Map.entry("fencing_token","bigint"),Map.entry("request_id","uuid"),Map.entry("worker_owner","character varying(128)"),Map.entry("lease_millis","integer"),Map.entry("action","character varying(8)"),Map.entry("acquired_at","timestamp(3) with time zone"),Map.entry("observed_at","timestamp(3) with time zone"),Map.entry("lease_until","timestamp(3) with time zone"),Map.entry("released_at","timestamp(3) with time zone"),Map.entry("release_confirmation_reference","character varying(71)"),Map.entry("trace_id","character varying(128)"),Map.entry("record_source","character varying(40)"),Map.entry("device_execution_authorized","boolean")),Set.of("released_at","release_confirmation_reference"),true);
  verifyRuntimeAcl(c,table,Set.of());
  requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND NOT relispartition AND NOT relrowsecurity AND NOT relforcerowsecurity AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_backup_device_lease_event'::regclass");
  requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent='public.mop_backup_device_lease_event'::regclass OR inhrelid='public.mop_backup_device_lease_event'::regclass) AND NOT EXISTS(SELECT 1 FROM pg_rewrite WHERE ev_class='public.mop_backup_device_lease_event'::regclass)");
  requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t CROSS JOIN LATERAL aclexplode(COALESCE(t.relacl,acldefault('r',t.relowner))) a WHERE t.oid='public.mop_backup_device_lease_event'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
  requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t JOIN pg_attribute x ON x.attrelid=t.oid CROSS JOIN LATERAL aclexplode(x.attacl) a WHERE t.oid='public.mop_backup_device_lease_event'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
  requireConstraint(c,table,"p",List.of("tenant_id","asset_id","version"),null,List.of(),false);
  requireConstraint(c,table,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);
  requireTrue(c,"SELECT count(*)=15 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public.mop_backup_device_lease_event'::regclass");
  verifyCheckDefinitions(c);
  requireTrue(c,"SELECT count(*)=4 AND bool_and(indisvalid AND indisready AND indislive) FROM pg_index WHERE indrelid='public.mop_backup_device_lease_event'::regclass");
  for(var key:Map.of("mop_backup_device_request_once","request_id","mop_backup_device_fence_once","fencing_token").entrySet())requireTrue(c,"SELECT i.indisunique AND i.indimmediate AND i.indnkeyatts=3 AND i.indnatts=3 AND pg_get_expr(i.indpred,i.indrelid)='((action)::text = ''ACQUIRE''::text)' AND ARRAY(SELECT a.attname::text FROM unnest(i.indkey) WITH ORDINALITY k(num,pos) JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.num ORDER BY k.pos)=ARRAY['tenant_id','asset_id',?]::text[] FROM pg_index i WHERE i.indexrelid=('public.'||?)::regclass",key.getValue(),key.getKey());
  requireTrue(c,"SELECT pg_get_indexdef(indexrelid)='CREATE INDEX mop_backup_device_request_history ON public.mop_backup_device_lease_event USING btree (tenant_id, asset_id, request_id, version DESC)' FROM pg_index WHERE indexrelid='public.mop_backup_device_request_history'::regclass");
  requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgname='mop_backup_device_lease_insert_guard' AND tgtype=31 AND tgfoid='public.mop_backup_device_lease_insert_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_backup_device_lease_event'::regclass AND NOT tgisinternal");
  requireTrue(c,"SELECT NOT p.prosecdef AND p.provolatile='v' AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner AND (a.is_grantable OR a.privilege_type<>'EXECUTE' OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime'))) FROM pg_proc p WHERE p.oid='public.mop_backup_device_lease_insert_guard()'::regprocedure",FUNCTION_SHA256);
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
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT conname,CASE WHEN set_config('TimeZone','UTC',true)='UTC' AND set_config('DateStyle','ISO, MDY',true)='ISO, MDY' THEN encode(sha256(convert_to(pg_get_constraintdef(oid),'UTF8')),'hex') END FROM pg_constraint WHERE conrelid='public.mop_backup_device_lease_event'::regclass AND contype='c' ORDER BY conname")) {
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
