package cn.mop.persistence;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
final class ExportInboxSchemaVerifier {
 static final String SCRIPT="V31__own_export_inbox.sql";
 static final String SHA256="60ae717f032c61fd7cbed98a0ffd15d9df514c501d143ecadc337e8f56a388dc";
 static final String FUNCTION_SHA256="b74a5ad6bdca4dbd1c1e1323f1c210d4edd097bdbf77c471d162d7ada8701725";
 static void resource()throws Exception{try(var in=ExportInboxSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed export inbox migration");}}
 static void verify(Connection c)throws SQLException{
  requireTrue(c,"SELECT current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
  verifyColumns(c,"mop_export_inbox",Map.ofEntries(Map.entry("job_id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("recipient_id","uuid"),Map.entry("permission_version","bigint"),Map.entry("published_version","bigint"),Map.entry("file_sha256","character varying(64)"),Map.entry("issued_at","timestamp(3) with time zone"),Map.entry("expires_at","timestamp(3) with time zone"),Map.entry("version","bigint"),Map.entry("read_at","timestamp(3) with time zone")),Set.of("read_at"),true);
  verifyRuntimeAcl(c,"mop_export_inbox",Set.of("version","read_at"));
  requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND NOT relispartition AND NOT relrowsecurity AND NOT relforcerowsecurity AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_export_inbox'::regclass");
  requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent='public.mop_export_inbox'::regclass OR inhrelid='public.mop_export_inbox'::regclass) AND NOT EXISTS(SELECT 1 FROM pg_rewrite WHERE ev_class='public.mop_export_inbox'::regclass)");
  requireTrue(c,"SELECT encode(sha256(convert_to(string_agg(conname||'='||conbin::text,chr(10) ORDER BY conname),'UTF8')),'hex')=? FROM pg_constraint WHERE conrelid='public.mop_export_inbox'::regclass AND contype='c'","33c21a92c61574993a39616a542425a77e71a3f849d09f1c739ce8f7f70ff453");
  requireTrue(c,"SELECT encode(sha256(convert_to(string_agg(indexname||'='||indexdef,chr(10) ORDER BY indexname),'UTF8')),'hex')=? FROM pg_indexes WHERE schemaname='public' AND tablename='mop_export_inbox'","e45de2e8227c14f31fc2a76a6dd57d06b9b202dd8bd825727d6042a4ce99dc0f");
  requireTrue(c,"SELECT encode(sha256(convert_to(string_agg(a.attname||'='||pg_get_expr(d.adbin,d.adrelid),chr(10) ORDER BY a.attname),'UTF8')),'hex')=? FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_export_inbox'::regclass","1ad9091432c3df03d9024bcf027e200168617c4775e3de7ce890e2c173c733e5");
  requireTrue(c,"SELECT count(*)=10 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public.mop_export_inbox'::regclass");
  requireConstraint(c,"mop_export_inbox","p",List.of("job_id"),null,List.of(),false);
  requireConstraint(c,"mop_export_inbox","f",List.of("tenant_id","job_id"),"mop_asset_export_job",List.of("tenant_id","id"),false);
  requireConstraint(c,"mop_export_inbox","f",List.of("tenant_id","recipient_id"),"mop_membership",List.of("tenant_id","user_id"),false);
  requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgname='mop_export_inbox_guard' AND tgtype=31 AND tgfoid='public.mop_export_inbox_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_export_inbox'::regclass AND NOT tgisinternal");
  requireTrue(c,"SELECT count(*)=3 AND bool_and(indisvalid AND indisready) FROM pg_index WHERE indrelid='public.mop_export_inbox'::regclass");
  requireTrue(c,"SELECT NOT p.prosecdef AND p.provolatile='v' AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner AND (a.is_grantable OR a.privilege_type<>'EXECUTE' OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime'))) FROM pg_proc p WHERE p.oid='public.mop_export_inbox_guard()'::regprocedure",FUNCTION_SHA256);
 }
}
