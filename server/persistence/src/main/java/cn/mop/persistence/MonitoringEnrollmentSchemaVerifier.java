package cn.mop.persistence;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;
final class MonitoringEnrollmentSchemaVerifier {
 static final String SCRIPT="V54__monitoring_enrollment_journal.sql",SHA256="447bc4441943dbd3df4ead06915b4c05697dffb8c30cde6524009575c2ab3dc2";
 static byte[] sql()throws Exception{try(var in=MonitoringEnrollmentSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null)throw new IllegalStateException();return in.readAllBytes();}}
 static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
 static void resource()throws Exception{if(!SHA256.equals(hash(sql())))throw new IllegalStateException("Monitoring enrollment candidate changed without review");}
 static void verify(Connection c)throws SQLException{
  for (var table : List.of("mop_monitoring_enrollment", "mop_monitoring_enrollment_revision")) {
   // Revision history remains insert-only; root id permission exists solely for locking.
   Set<String> allowedUpdates = table.equals("mop_monitoring_enrollment") ? Set.of("id") : Set.of();
   verifyRuntimeAcl(c, table, allowedUpdates);
   requireTrue(c, "SELECT relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') "
       + "FROM pg_class WHERE oid=?::regclass", "public." + table);
   requireTrue(c, "SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=31 AND NOT tgdeferrable "
       + "AND tgfoid='public.mop_monitoring_enrollment_guard()'::regprocedure) FROM pg_trigger "
       + "WHERE tgrelid=?::regclass AND NOT tgisinternal", "public." + table);
  }
  requireConstraint(c,"mop_monitoring_enrollment","p",List.of("tenant_id","id"),null,List.of(),false);
  requireConstraint(c,"mop_monitoring_enrollment_revision","p",List.of("tenant_id","id","version"),null,List.of(),false);
  requireConstraint(c,"mop_monitoring_enrollment_revision","f",List.of("tenant_id","id"),"mop_monitoring_enrollment",List.of("tenant_id","id"),false);
  try{resource();String source=new String(sql(),StandardCharsets.UTF_8);int start=source.indexOf("AS $$",source.indexOf("CREATE FUNCTION"))+5;
   requireTrue(c,"SELECT NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(prosrc,'UTF8')),'hex')=? FROM pg_proc WHERE oid='public.mop_monitoring_enrollment_guard()'::regprocedure",hash(source.substring(start,source.indexOf("$$;",start)).getBytes(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new SQLException("Monitoring enrollment schema unavailable");}
 }
}
