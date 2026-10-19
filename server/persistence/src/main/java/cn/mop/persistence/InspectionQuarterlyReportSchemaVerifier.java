package cn.mop.persistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Explicit V41 candidate verifier, never applies migrations. */
final class InspectionQuarterlyReportSchemaVerifier {
    static final String SHA256="54b9bd1d0995a248191e8f8f7dafe35fafd7d4ec15699a2ca8a49a7a025c7aa2";
    static final String FUNCTION_SHA256="6d709f6ce727b3a6b518b64c311aa47d79d4720c4c30df3415bcb984aff4a43d";
    // PostgreSQL 17 definitions captured by an independent rollback-only DDL probe.
    // Exact complete map, normalized under UTC / ISO, MDY. This is not migration evidence.
    static final Map<String,String> CHECK_HASHES=Map.ofEntries(
        Map.entry("mop_inspection_quarterly_report_configuration_source_check","0bf134a78c0b69d23b954158c60b6b673d7229200a9f24638b38c17ea1fade85"),
        Map.entry("mop_inspection_quarterly_report_document_check","7387444c95b575612763d929f32d3c9ad2594c7cd2038decd3ddda4d89726da0"),
        Map.entry("mop_inspection_quarterly_report_document_format_check","8ea5134252016eeb2a54ea2d0767e949964538764011b51df761d0b026d7610f"),
        Map.entry("mop_inspection_quarterly_report_evidence_access_granted_check","380d9cc999484ee907ac703e09abaf3c74cf8cbd6cebeb02461b4c40ac5ad4c6"),
        Map.entry("mop_inspection_quarterly_report_generation_status_check","1a496116f93485e0f0b1f975bd0cea84d4bcce41b488f65fc07e6835590fee0b"),
        Map.entry("mop_inspection_quarterly_report_label_check","088821050686a1897982aa6bf9937259cbb3aa58b88a3618fdecb2a63576f9e1"),
        Map.entry("mop_inspection_quarterly_report_quarter_check","d273ade36f407ff3aab2f5638aa44ded01b22c3cd008862c5c05b329da3b34b4"),
        Map.entry("mop_inspection_quarterly_report_recorded_at_check","03e64fe781185389b309b9483affb45e644aa7a948a2bd2684f56445e24d92e4"),
        Map.entry("mop_inspection_quarterly_report_report_publishable_check","f658ff4ffe4c28d694074155db41ea3d8c85aa6e8ec546d3d172e5facbb86868"),
        Map.entry("mop_inspection_quarterly_report_report_published_check","63592836885d86d1f4fe1cbd036f8d6cbf0711867068a2912dd29054f54b5171"),
        Map.entry("mop_inspection_quarterly_report_report_sha256_check","0e2544ab2784d3a7270e31106202093a98a7d0e490bc73264fb0622028824b98"),
        Map.entry("mop_inspection_quarterly_report_source_document_sha256_check","5b1ae61fc975b34dc5e3016f9fccd29d8edab88555c5e6e69f8fcf8ee05c2bd6"),
        Map.entry("mop_inspection_quarterly_report_source_manifest_sha256_check","971f18e2f96b815ee73d4386f327204d59c284a0fb7afcb8b4c04664a823df7d"),
        Map.entry("mop_inspection_quarterly_report_version_check","825b9ee5370012ffc0fe68ccbd7a5f671f6a64eb27b5a4ed268f95bcf37fcb1c"),
        Map.entry("mop_inspection_quarterly_report_year_check","183fd395056513d32cfe35e4a964caafa2c7da60c1b83242269af64ec71d990c"));
    static final String DOCUMENT_EXPRESSION_SHA256="1a1153bc90898e4d44bad1a43e4fc77801e63eeeccd0d49c8913d2d06a12b365";
    static void requireCheckHashes(Map<String,String> observed) {
        if(CHECK_HASHES.size()!=15 || !CHECK_HASHES.equals(observed))
            throw new IllegalStateException("Quarterly report CHECK definitions differ from reviewed schema");
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
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT conname,CASE WHEN set_config('TimeZone','UTC',true)='UTC' AND set_config('DateStyle','ISO, MDY',true)='ISO, MDY' THEN encode(sha256(convert_to(pg_get_constraintdef(oid),'UTF8')),'hex') END FROM pg_constraint WHERE conrelid='public.mop_inspection_quarterly_report'::regclass AND contype='c' ORDER BY conname")) {
                while(rows.next())definitions.put(rows.getString(1),rows.getString(2));
            }
            requireCheckHashes(definitions);
        } finally {
            try(var restore=c.prepareStatement("SELECT set_config('TimeZone',?,true),set_config('DateStyle',?,true)")) {
                restore.setString(1,timezone);restore.setString(2,dateStyle);restore.execute();
            }
        }
    }
    static void resource()throws Exception {
        try(var in=InspectionQuarterlyReportSchemaVerifier.class.getResourceAsStream("/db/migration/V41__immutable_inspection_quarterly_reports.sql")){
            if(in==null)throw new IllegalStateException("Missing quarterly report migration");byte[] bytes=in.readAllBytes();
            if(!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed report migration");
            String sql=new String(bytes,StandardCharsets.UTF_8);int start=sql.indexOf(" AS $$")+6,end=sql.indexOf("$$;",start);
            if(start<6||end<start||!FUNCTION_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(start,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed report guard");
        }
    }
    static void verify(Connection c)throws SQLException {
        String table="mop_inspection_quarterly_report";
        requireTrue(c,"SELECT count(*)=2 AND bool_and(base_role='padmin') FROM public.mop_role_capability_limit WHERE capability IN ('inspection_quarterly_reports:read','inspection_quarterly_reports:write')");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM public.mop_project_role WHERE builtin AND base_role='padmin' AND NOT(permissions @> ARRAY['inspection_quarterly_reports:read','inspection_quarterly_reports:write']::text[]))");
        verifyColumns(c,table,Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("created_by","uuid"),Map.entry("label","character varying(200)"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("version","bigint"),Map.entry("source_collection_id","uuid"),Map.entry("source_document_sha256","character varying(64)"),Map.entry("source_manifest_sha256","character varying(64)"),Map.entry("year","integer"),Map.entry("quarter","integer"),Map.entry("report_sha256","character varying(64)"),Map.entry("document","bytea"),Map.entry("document_sha256","character varying(64)"),Map.entry("document_format","character varying(48)"),Map.entry("configuration_source","character varying(40)"),Map.entry("generation_status","character varying(16)"),Map.entry("report_published","boolean"),Map.entry("report_publishable","boolean"),Map.entry("evidence_access_granted","boolean")),Set.of(),true);
        verifyRuntimeAcl(c,table,Set.of());
        requireTrue(c,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);
        requireConstraint(c,table,"p",List.of("id"),null,List.of(),false);
        requireConstraint(c,table,"u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(c,table,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(c,table,"f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,table,"f",List.of("tenant_id","source_collection_id"),"mop_inspection_source_collection",List.of("tenant_id","id"),false);
        requireTrue(c,"SELECT count(*)=15 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",table);
        verifyCheckDefinitions(c);
        requireTrue(c,"SELECT a.attgenerated='s' AND encode(sha256(convert_to(pg_get_expr(d.adbin,d.adrelid),'UTF8')),'hex')=? FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_inspection_quarterly_report'::regclass AND a.attname='document_sha256' AND NOT a.attisdropped",DOCUMENT_EXPRESSION_SHA256);
        requireTrue(c,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid WHERE idx.relname='mop_inspection_quarterly_report_order' AND idx.relnamespace='public'::regnamespace");
        requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.mop_inspection_quarterly_report_guard()')",FUNCTION_SHA256);
        requireTrue(c,"SELECT count(*)=1 FROM pg_trigger WHERE tgrelid='public.mop_inspection_quarterly_report'::regclass AND NOT tgisinternal");
        requireTrue(c,"SELECT tgenabled='O' AND tgtype=31 AND NOT tgdeferrable AND NOT tginitdeferred AND tgfoid='public.mop_inspection_quarterly_report_guard()'::regprocedure FROM pg_trigger WHERE tgrelid='public.mop_inspection_quarterly_report'::regclass AND tgname='mop_inspection_quarterly_report_guard'");
    }
}
