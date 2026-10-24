package cn.mop.persistence;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Candidate V53 structural checks, never an implicit migration. */
final class ReportPublicationSchemaVerifier {
 static final String SCRIPT="V53__report_publication_journal.sql";
 static final String SHA256="42ecf93f54017057db3caf27ac6b4670084866466c3ed1d9d329c362c0ab95a7";
 static final List<String> TABLES=List.of("mop_report_publication_document","mop_report_publication_artifact","mop_report_publication_revision","mop_report_publication_read","mop_report_publication_delivery_intent");
 static byte[] sql()throws Exception{try(var in=ReportPublicationSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null)throw new IllegalStateException("Missing report candidate");return in.readAllBytes();}}
 static void resource()throws Exception{if(!SHA256.equals(hash(sql())))throw new IllegalStateException("Report candidate changed without review");}
 static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 static void verify(Connection c)throws SQLException{
  for(var table:TABLES){verifyRuntimeAcl(c,table,Set.of());requireTrue(c,"SELECT relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
   requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=31 AND NOT tgdeferrable AND tgfoid='public.mop_report_publication_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid=?::regclass AND NOT tgisinternal","public."+table);}
  requireConstraint(c,TABLES.get(0),"p",List.of("tenant_id","id"),null,List.of(),false);
  requireConstraint(c,TABLES.get(0),"f",List.of("tenant_id","source_report_id"),"mop_inspection_quarterly_report",List.of("tenant_id","id"),false);
  requireConstraint(c,TABLES.get(1),"p",List.of("tenant_id","document_id","format","render_version"),null,List.of(),false);
  requireConstraint(c,TABLES.get(2),"p",List.of("tenant_id","document_id","version"),null,List.of(),false);
  requireConstraint(c,TABLES.get(3),"p",List.of("tenant_id","document_id","customer_id"),null,List.of(),false);
  requireConstraint(c,TABLES.get(4),"p",List.of("tenant_id","id"),null,List.of(),false);
  for(var table:TABLES.subList(1,5))requireConstraint(c,table,"f",List.of("tenant_id","document_id"),TABLES.get(0),List.of("tenant_id","id"),false);
  try{resource();String source=new String(sql(),StandardCharsets.UTF_8);int start=source.indexOf("AS $$",source.indexOf("CREATE FUNCTION"))+5;String body=source.substring(start,source.indexOf("$$;",start));
   requireTrue(c,"SELECT NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(prosrc,'UTF8')),'hex')=? FROM pg_proc WHERE oid='public.mop_report_publication_guard()'::regprocedure",hash(body.getBytes(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new SQLException("Report publication guard unavailable");}
 }
}
