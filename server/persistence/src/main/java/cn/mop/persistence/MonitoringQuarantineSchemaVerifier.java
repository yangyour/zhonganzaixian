package cn.mop.persistence;

import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Explicit verification of unverified immutable storage, not source authorization or processing. */
final class MonitoringQuarantineSchemaVerifier {
    static final String SCRIPT="V30__immutable_monitoring_quarantine.sql";
    static final String SHA256="2c13cd68b1d7aff016644266c723c72330868fd6e368f6f19b9fceb17025827f";
    static final String FUNCTION_SHA256="a3f7ec5cca49dd66a3621d91fb92d097e6c63d26e1533458fbf5d2324abfd1de";
    /** Exact reviewed PostgreSQL17 forms; restored casts/grouping differ, literal values remain identical. */
    static final String ORIGINAL_CHECK_CATALOGUE = "c1ef47536df14ff8c6e7e9725aa7b92ed7fe4ee69a3ded2d44a1654dd7439226";
    static final String RESTORED_CHECK_CATALOGUE = "c3a4a2ea9bdb76f317c6169ab5148d86220bba97c1f329526754c22da85e99c0";
    private MonitoringQuarantineSchemaVerifier(){}
    static void resource()throws Exception{
        try(var in=MonitoringQuarantineSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){
            if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Unreviewed raw event migration");
        }
    }
    static void verify(Connection c)throws SQLException{
        requireTrue(c,"SELECT current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        String table="mop_monitoring_quarantine";
        verifyColumns(c,table,Map.ofEntries(Map.entry("id","uuid"),Map.entry("pool_id","uuid"),Map.entry("source_event_id","text"),Map.entry("source_phase","character varying(16)"),Map.entry("claimed_tenant_id","uuid"),Map.entry("claimed_asset_id","uuid"),Map.entry("quarantine_reason","character varying(32)"),Map.entry("occurred_epoch_second","bigint"),Map.entry("occurred_nano","integer"),Map.entry("received_epoch_second","bigint"),Map.entry("received_nano","integer"),Map.entry("payload","bytea"),Map.entry("payload_sha256","text"),Map.entry("candidate_fingerprint","text"),Map.entry("quarantine_status","character varying(32)"),Map.entry("dispatch_allowed","boolean"),Map.entry("source_authorized","boolean")),Set.of("claimed_tenant_id","claimed_asset_id"),true);
        verifyRuntimeAcl(c,table,Set.of());
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent='public.mop_monitoring_quarantine'::regclass OR inhrelid='public.mop_monitoring_quarantine'::regclass) AND NOT EXISTS(SELECT 1 FROM pg_rewrite WHERE ev_class='public.mop_monitoring_quarantine'::regclass)");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t CROSS JOIN LATERAL aclexplode(COALESCE(t.relacl,acldefault('r',t.relowner))) a WHERE t.oid='public.mop_monitoring_quarantine'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_class t JOIN pg_attribute x ON x.attrelid=t.oid CROSS JOIN LATERAL aclexplode(x.attacl) a WHERE t.oid='public.mop_monitoring_quarantine'::regclass AND a.grantee<>t.relowner AND (a.is_grantable OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime')))");
        requireTrue(c,"SELECT encode(sha256(convert_to(string_agg(a.attname||'='||pg_get_expr(d.adbin,d.adrelid),chr(10) ORDER BY a.attname),'UTF8')),'hex')=? FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_monitoring_quarantine'::regclass","ee9dc293b8de6805908d8237c2679ec9455eb65f501f2ffcb8d931514e6b61df");

        requireTrue(c,"SELECT count(*)=15 FROM pg_constraint WHERE conrelid='public.mop_monitoring_quarantine'::regclass");
        requireTrue(c,"SELECT relkind='r' AND NOT relispartition AND relpersistence='p' AND NOT relrowsecurity AND NOT relforcerowsecurity AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_monitoring_quarantine'::regclass");
        requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.mop_monitoring_quarantine'::regclass AND NOT convalidated)");
        requireConstraint(c,table,"p",List.of("id"),null,List.of(),false);
        requireConstraint(c,table,"u",List.of("candidate_fingerprint"),null,List.of(),false);
        requireTrue(c,"SELECT count(*)=13 FROM pg_constraint WHERE conrelid='public.mop_monitoring_quarantine'::regclass AND contype='c'");
        requireTrue(c,"SELECT count(*)=0 FROM pg_trigger WHERE tgrelid='public.mop_monitoring_quarantine'::regclass AND NOT tgisinternal");
        // Exact PostgreSQL 17 catalogue forms; corrupt literals and all unreviewed definitions still fail.
        verifyCheckCatalogue(c);
        requireTrue(c,"SELECT encode(sha256(convert_to(string_agg(a.attname||'='||pg_get_expr(d.adbin,d.adrelid),chr(10) ORDER BY a.attname),'UTF8')),'hex')=? FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_monitoring_quarantine'::regclass AND a.attgenerated='s'","51e98b3cd37e90b633c2697328548335a087150a78555b1a2950e2af513cc872");
        requireTrue(c,"SELECT NOT p.prosecdef AND NOT p.proisstrict AND p.provolatile='i' AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner AND (a.is_grantable OR a.privilege_type<>'EXECUTE' OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname='mop_test_runtime'))) FROM pg_proc p WHERE p.oid=to_regprocedure('public.mop_quarantine_fingerprint(uuid,text,text,uuid,uuid,text,bigint,integer,bytea)')",FUNCTION_SHA256);
    }
    /** No SQL/literal normalization: only the two exact catalogues verified against the frozen source are accepted. */
    private static void verifyCheckCatalogue(Connection connection) throws SQLException {
        requireTrue(connection,
                "SELECT encode(sha256(convert_to(string_agg(conname||'='||pg_get_constraintdef(oid),chr(10) "
                        + "ORDER BY conname),'UTF8')),'hex') IN (?,?) FROM pg_constraint "
                        + "WHERE conrelid='public.mop_monitoring_quarantine'::regclass AND contype='c'",
                ORIGINAL_CHECK_CATALOGUE, RESTORED_CHECK_CATALOGUE);
    }

}
