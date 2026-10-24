package cn.mop.persistence;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;
final class MonitoringObjectBindingSchemaVerifier {
 static final String SCRIPT="V55__monitoring_object_mapping_journal.sql",SHA256="03bf7bcc280c0054ad8e52b1d2ff2aa8cafff921a282f2edef04a890faa1b01b";
 static byte[] sql()throws Exception{try(var in=MonitoringObjectBindingSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null)throw new IllegalStateException();return in.readAllBytes();}}
 static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
 static void resource()throws Exception{if(!SHA256.equals(hash(sql())))throw new IllegalStateException("Monitoring mapping candidate changed without review");}
 static void verify(Connection c)throws SQLException{
  for(var table:List.of("mop_monitoring_object_binding","mop_monitoring_object_binding_revision")){
   verifyRuntimeAcl(c,table,Set.of());requireTrue(c,"SELECT relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
   requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=31 AND NOT tgdeferrable AND tgfoid='public.mop_monitoring_object_binding_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid=?::regclass AND NOT tgisinternal","public."+table);
  }
  requireConstraint(c,"mop_monitoring_object_binding","p",List.of("tenant_id","id"),null,List.of(),false);
  requireConstraint(c,"mop_monitoring_object_binding_revision","p",List.of("tenant_id","id","version"),null,List.of(),false);
  requireConstraint(c,"mop_monitoring_object_binding_revision","f",List.of("tenant_id","id"),"mop_monitoring_object_binding",List.of("tenant_id","id"),false);
  requireConstraint(c,"mop_monitoring_object_binding","u",List.of("tenant_id","pool_id","object_type","external_id"),null,List.of(),false);
  for(String column:List.of("draft_asset_id","active_asset_id")){
   requireConstraint(c,"mop_monitoring_object_binding_revision","f",List.of("tenant_id",column),"mop_asset",List.of("tenant_id","id"),false);
   requireTrue(c,"SELECT attgenerated='s' AND atttypid='uuid'::regtype FROM pg_attribute WHERE attrelid='public.mop_monitoring_object_binding_revision'::regclass AND attname=? AND NOT attisdropped",column);
  }
  try{resource();String source=new String(sql(),StandardCharsets.UTF_8);int start=source.indexOf("AS $$",source.indexOf("CREATE FUNCTION"))+5;
   requireTrue(c,"SELECT NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(prosrc,'UTF8')),'hex')=? FROM pg_proc WHERE oid='public.mop_monitoring_object_binding_guard()'::regprocedure",hash(source.substring(start,source.indexOf("$$;",start)).getBytes(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new SQLException("Monitoring mapping schema unavailable");}
 }
}
