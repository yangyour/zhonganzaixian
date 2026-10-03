package cn.mop.persistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Read-only inspection of the independently applied contact import structure, never automatic DDL. */
final class ContactImportSchemaVerifier {
    private ContactImportSchemaVerifier(){}
    static void resource()throws Exception{
        try(var input=ContactImportSchemaVerifier.class.getResourceAsStream("/db/migration/V18__durable_contact_import_jobs.sql")){
            if(input==null)throw new IllegalStateException("Missing contact import migration");byte[] bytes=input.readAllBytes();
            if(!CONTACT_IMPORT_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed contact import resource");
            String sql=new String(bytes,StandardCharsets.UTF_8);
            for(var entry:Map.of("mop_contact_import_transition_guard",CONTACT_IMPORT_TRANSITION_SHA256,"mop_contact_import_completion_guard",CONTACT_IMPORT_COMPLETION_SHA256).entrySet()){
                int start=sql.indexOf("CREATE FUNCTION public."+entry.getKey()+"()"),marker=start<0?-1:sql.indexOf(" AS $$",start),end=marker<0?-1:sql.indexOf("$$;",marker+6);
                if(marker<0||end<0||!entry.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed contact import guard body");
            }
        }
    }
    static void fixResource()throws Exception{
        try(var input=ContactImportSchemaVerifier.class.getResourceAsStream("/db/migration/V19__contact_import_generated_hash_guard.sql")){
            if(input==null)throw new IllegalStateException("Missing contact import guard fix");byte[] bytes=input.readAllBytes();if(!CONTACT_IMPORT_FIX_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed contact import guard fix");String sql=new String(bytes,StandardCharsets.UTF_8);int marker=sql.indexOf(" AS $$"),end=marker<0?-1:sql.indexOf("$$;",marker+6);if(marker<0||end<0||!CONTACT_IMPORT_FIXED_TRANSITION_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed fixed contact import transition body");
        }
    }
    static void verify(Connection c,boolean generatedGuardFixed)throws SQLException{
        String job="mop_contact_import_job",result="mop_contact_import_result";
        verifyColumns(c,job,Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("created_by","uuid"),Map.entry("permission_version","bigint"),Map.entry("row_count","integer"),Map.entry("payload_bytes","bytea"),Map.entry("input_sha256","character(64)"),Map.entry("source_format","character varying(40)"),Map.entry("trace_id","character varying(128)"),Map.entry("state","character varying(16)"),Map.entry("attempts","bigint"),Map.entry("fencing_token","bigint"),Map.entry("lease_owner","character varying(64)"),Map.entry("lease_started_at","timestamp(3) with time zone"),Map.entry("lease_until","timestamp(3) with time zone"),Map.entry("next_at","timestamp(3) with time zone"),Map.entry("completed_rows","integer"),Map.entry("error_code","character varying(40)"),Map.entry("version","bigint"),Map.entry("created_at","timestamp(3) with time zone"),Map.entry("updated_at","timestamp(3) with time zone")),Set.of("lease_owner","lease_started_at","lease_until","next_at","completed_rows","error_code"),true);
        verifyColumns(c,result,Map.of("tenant_id","uuid","job_id","uuid","row_number","integer","row_count","integer","contact_id","uuid","contact_version","bigint","recorded_at","timestamp(3) with time zone"),Set.of(),true);
        verifyRuntimeAcl(c,job,Set.of("state","attempts","fencing_token","lease_owner","lease_started_at","lease_until","next_at","completed_rows","error_code","version","updated_at"));verifyRuntimeAcl(c,result,Set.of());
        for(String table:List.of(job,result)){
            requireTrue(c,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);
            requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);
        }
        requireConstraint(c,job,"p",List.of("id"),null,List.of(),false);
        requireConstraint(c,job,"u",List.of("tenant_id","id"),null,List.of(),false);requireConstraint(c,job,"u",List.of("tenant_id","id","row_count"),null,List.of(),false);
        requireConstraint(c,job,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);requireConstraint(c,job,"f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,result,"p",List.of("tenant_id","job_id","row_number"),null,List.of(),false);requireConstraint(c,result,"u",List.of("tenant_id","contact_id"),null,List.of(),false);
        requireConstraint(c,result,"f",List.of("tenant_id","job_id","row_count"),job,List.of("tenant_id","id","row_count"),false);requireConstraint(c,result,"f",List.of("tenant_id","contact_id","contact_version"),"mop_customer_contact_revision",List.of("tenant_id","contact_id","version"),false);
        for(var entry:Map.of(job,15,result,3).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",entry.getKey());
        requireTrue(c,"SELECT attgenerated='s' AND attnotnull FROM pg_attribute WHERE attrelid='public.mop_contact_import_job'::regclass AND attname='input_sha256' AND NOT attisdropped");
        requireTrue(c,"SELECT replace(regexp_replace(pg_get_expr(d.adbin,d.adrelid),'[[:space:]]','','g'),'pg_catalog.','') IN ('encode(sha256(payload_bytes),''hex''::text)','(encode(sha256(payload_bytes),''hex''::text))::character(64)') FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum WHERE d.adrelid='public.mop_contact_import_job'::regclass AND a.attname='input_sha256'");
        for(String name:List.of("mop_contact_import_owner","mop_contact_import_pending","mop_contact_import_expired"))requireTrue(c,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname=? AND c.relnamespace='public'::regnamespace",name);
        for(var entry:Map.of("mop_contact_import_transition_guard",generatedGuardFixed?CONTACT_IMPORT_FIXED_TRANSITION_SHA256:CONTACT_IMPORT_TRANSITION_SHA256,"mop_contact_import_completion_guard",CONTACT_IMPORT_COMPLETION_SHA256).entrySet())requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.'||?||'()')",entry.getValue(),entry.getKey());
        requireTrue(c,"SELECT count(*)=2 FROM pg_trigger WHERE tgrelid='public.mop_contact_import_job'::regclass AND NOT tgisinternal");requireTrue(c,"SELECT count(*)=1 FROM pg_trigger WHERE tgrelid='public.mop_contact_import_result'::regclass AND NOT tgisinternal");
        requireTrue(c,"SELECT tgenabled='O' AND tgtype=23 AND tgfoid=to_regprocedure('public.mop_contact_import_transition_guard()') AND NOT tgdeferrable AND NOT tginitdeferred FROM pg_trigger WHERE tgrelid='public.mop_contact_import_job'::regclass AND tgname='mop_contact_import_transition'");
        requireTrue(c,"SELECT tgenabled='O' AND tgtype=21 AND tgfoid=to_regprocedure('public.mop_contact_import_completion_guard()') AND tgdeferrable AND tginitdeferred FROM pg_trigger WHERE tgrelid='public.mop_contact_import_job'::regclass AND tgname='mop_contact_import_job_complete'");
        requireTrue(c,"SELECT tgenabled='O' AND tgtype=5 AND tgfoid=to_regprocedure('public.mop_contact_import_completion_guard()') AND tgdeferrable AND tginitdeferred FROM pg_trigger WHERE tgrelid='public.mop_contact_import_result'::regclass AND tgname='mop_contact_import_result_complete'");
    }
}
