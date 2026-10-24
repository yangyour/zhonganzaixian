package cn.mop.persistence;

import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Explicit verification of unverified immutable storage, not source authorization or processing. */
final class RawMonitoringEventSchemaVerifier {
    static final String SCRIPT="V29__immutable_raw_monitoring_events.sql";
    static final String SHA256="2f67b28791d2533934153227a98d6e09aec4e16c7405d4408e9956a9d853e8c3";
    static final String FUNCTION_SHA256="67ec7f320ae54fe58afbbf1534ba1779ba0432bc51749870e07666fb8730159b";
    /** Exact reviewed PostgreSQL17 forms; restored casts/grouping differ, literal values remain identical. */
    static final String ORIGINAL_CHECK_CATALOGUE = "162c421f9552d45f7210ffccc62bb888d0acf1518ac7bca952709bdc7797f39b";
    static final String RESTORED_CHECK_CATALOGUE = "0367287efb710751452bd8c6cca1cdd97cc39203ab1bffb2261f8f9666afd283";
    private RawMonitoringEventSchemaVerifier(){}
    static void resource()throws Exception{
        try(var in=RawMonitoringEventSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){
            if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed raw event migration");
        }
    }
    static void verify(Connection c)throws SQLException{
        requireTrue(c,"SELECT current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        String table="mop_raw_monitoring_event";
        verifyColumns(c,table,Map.ofEntries(Map.entry("id","uuid"),Map.entry("pool_id","uuid"),Map.entry("source_event_id","text"),Map.entry("source_phase","character varying(16)"),Map.entry("tenant_id","uuid"),Map.entry("asset_id","uuid"),Map.entry("occurred_epoch_second","bigint"),Map.entry("occurred_nano","integer"),Map.entry("received_epoch_second","bigint"),Map.entry("received_nano","integer"),Map.entry("payload","bytea"),Map.entry("payload_sha256","text"),Map.entry("content_fingerprint","text"),Map.entry("source_authorization_status","character varying(16)"),Map.entry("storage_status","character varying(16)")),Set.of(),true);
        verifyRuntimeAcl(c,table,Set.of());
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent='public.mop_raw_monitoring_event'::regclass OR inhrelid='public.mop_raw_monitoring_event'::regclass) AND NOT EXISTS(SELECT 1 FROM pg_rewrite WHERE ev_class='public.mop_raw_monitoring_event'::regclass)");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t CROSS JOIN LATERAL aclexplode(COALESCE(t.relacl,acldefault('r',t.relowner))) a WHERE t.oid='public.mop_raw_monitoring_event'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t JOIN pg_attribute x ON x.attrelid=t.oid CROSS JOIN LATERAL aclexplode(x.attacl) a WHERE t.oid='public.mop_raw_monitoring_event'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
        requireTrue(c,"SELECT encode(sha256(convert_to(string_agg(a.attname||'='||pg_get_expr(d.adbin,d.adrelid),chr(10) ORDER BY a.attname),'UTF8')),'hex')=? FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_raw_monitoring_event'::regclass","531cba20eebf32bbff37c8e58bf3d3858dd46b7d35ddb8cf33b99ac813bebeb1");

        requireTrue(c,"SELECT relkind='r' AND NOT relispartition AND relpersistence='p' AND NOT relrowsecurity AND NOT relforcerowsecurity AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_raw_monitoring_event'::regclass");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.mop_raw_monitoring_event'::regclass AND NOT convalidated)");
        requireConstraint(c,table,"p",List.of("id"),null,List.of(),false);
        requireConstraint(c,table,"u",List.of("pool_id","source_event_id","source_phase"),null,List.of(),false);
        requireConstraint(c,table,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(c,table,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);
        requireTrue(c,"SELECT count(*)=11 FROM pg_constraint WHERE conrelid='public.mop_raw_monitoring_event'::regclass AND contype='c'");
        requireTrue(c,"SELECT count(*)=0 FROM pg_trigger WHERE tgrelid='public.mop_raw_monitoring_event'::regclass AND NOT tgisinternal");
        // Exact PostgreSQL 17 catalogue forms; corrupt literals and all unreviewed definitions still fail.
        verifyCheckCatalogue(c);
        requireTrue(c,"SELECT encode(sha256(convert_to(string_agg(a.attname||'='||pg_get_expr(d.adbin,d.adrelid),chr(10) ORDER BY a.attname),'UTF8')),'hex')=? FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_raw_monitoring_event'::regclass AND a.attgenerated='s'","08bfc2cc48b35103a0287df83cddc07773666ae42e2c9cb4edbf6742df0e6fca");
        requireTrue(c,"SELECT NOT p.prosecdef AND p.proisstrict AND p.provolatile='i' AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner AND (a.is_grantable OR a.privilege_type<>'EXECUTE' OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime'))) FROM pg_proc p WHERE p.oid=to_regprocedure('public.mop_raw_event_fingerprint(uuid,text,text,uuid,uuid,bigint,integer,bytea)')",FUNCTION_SHA256);
    }
    /** No SQL/literal normalization: only the two exact catalogues verified against the frozen source are accepted. */
    private static void verifyCheckCatalogue(Connection connection) throws SQLException {
        requireTrue(connection,
                "SELECT encode(sha256(convert_to(string_agg(conname||'='||pg_get_constraintdef(oid),chr(10) "
                        + "ORDER BY conname),'UTF8')),'hex') IN (?,?) FROM pg_constraint "
                        + "WHERE conrelid='public.mop_raw_monitoring_event'::regclass AND contype='c'",
                ORIGINAL_CHECK_CATALOGUE, RESTORED_CHECK_CATALOGUE);
    }

}
