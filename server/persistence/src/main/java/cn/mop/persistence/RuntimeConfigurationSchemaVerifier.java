package cn.mop.persistence;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;
final class RuntimeConfigurationSchemaVerifier {
 static final String SCRIPT="V32__runtime_configuration_publications.sql";
 static final String SHA256="657c0d50e47b3da715265a563af857287337fc923774fd98b4a6b40b8cad0c82";
 static void resource()throws Exception{try(var in=RuntimeConfigurationSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed runtime configuration migration");}}
 static void verify(Connection c)throws SQLException{
  requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
  verifyRuntimeAcl(c,"mop_runtime_configuration_release",Set.of());
  requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_runtime_configuration_release'::regclass");
  requireTrue(c,"SELECT count(*)=20 FROM information_schema.columns WHERE table_schema='public' AND table_name='mop_runtime_configuration_release'");
  requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgname='mop_runtime_configuration_guard' AND tgtype=31 AND tgfoid='public.mop_runtime_configuration_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_runtime_configuration_release'::regclass AND NOT tgisinternal");
  requireTrue(c,"SELECT NOT p.prosecdef AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')='5e804409a7eccae8d05a13945ca62e9b04a1e7877aa095385b98f943396a7a01' AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid='public.mop_runtime_configuration_guard()'::regprocedure");
  requireTrue(c,"SELECT count(*)>=13 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid='public.mop_runtime_configuration_release'::regclass");
 }
}
