package cn.mop.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Frozen V40 verification only. This class neither applies migrations nor activates notices. */
final class ChangeNoticeApprovalSchemaVerifier {
    static final String SCRIPT="V40__immutable_change_notice_approval_events.sql";
    static final String SHA256="a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756";
    static final String GUARD_SHA256="48b1effafef8bd59bb68b47bce283248aff5e051e19c178ae507e6711a3ffa27";
    // SHA-256 of all PostgreSQL17 CHECK definitions, deparsed with UTC / ISO, MDY.
    static final Map<String,String> CHECK_HASHES=Map.ofEntries(
        Map.entry("mop_change_notice_approval_even_alert_suppression_allowed_check","f065e60e419a9283f3794a912ad5a2b3a295ac7753e79c41f9ec29380cae96b1"),
        Map.entry("mop_change_notice_approval_event_check","2b15804179b0aa8ab074b339fcddebe0f3116412e46a29655cf103dda1104548"),
        Map.entry("mop_change_notice_approval_event_check1","ed057bc0fa75157bd5f0b9fb2eecc43b84fda38caf1656958999ebd73e72445d"),
        Map.entry("mop_change_notice_approval_event_check2","b6a2f0005e0e0702123b73297eff7001765dd61a22982c5b90d8d750694280be"),
        Map.entry("mop_change_notice_approval_event_content_sha256_check","230a0b27e1907de0b1656b83118ef590af247dcd87dbb1063bd0e64422faa549"),
        Map.entry("mop_change_notice_approval_event_device_execution_allowed_check","6e4e142d4edb84885da1e863bc45d0b7cf989fd74ac012789933eb00be40d6bf"),
        Map.entry("mop_change_notice_approval_event_effective_check","413a91be363581b4eb255c9c63f78a3e03b07742de8f60d4b20a56f6b65857f9"),
        Map.entry("mop_change_notice_approval_event_note_check","9d9b94344a112d866215806b9cd1824aceafd79488f5acf0e8cd14a9309ef512"),
        Map.entry("mop_change_notice_approval_event_occurred_at_check","34ba9f5992a7546e57d81af1dbdb64144752fdb23248537b10df69a8ce2d4881"),
        Map.entry("mop_change_notice_approval_event_permission_version_check","378033ad8c9b5d0d1808c84e5a24d3b405f694e691821dd1804df24daae2cecb"),
        Map.entry("mop_change_notice_approval_event_version_check","5c60c6dde11c9164a7325c5502ac3a445867a78a060050cc2c1460bde2321fb3"));
    private ChangeNoticeApprovalSchemaVerifier() {}
    static void resource() throws Exception {
        try(var in=ChangeNoticeApprovalSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)) {
            if(in==null)throw new IllegalStateException("Missing approval candidate");
            byte[] bytes=in.readAllBytes();
            if(!SHA256.equals(hash(bytes)))throw new IllegalStateException("Approval candidate bytes changed");
            String sql=new String(bytes,StandardCharsets.UTF_8);
            int start=sql.indexOf(" AS $$")+6,end=sql.indexOf("$$;",start);
            if(start<6||end<start||!GUARD_SHA256.equals(hash(sql.substring(start,end).getBytes(StandardCharsets.UTF_8))))throw new IllegalStateException("Approval guard bytes changed");
        }
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static void requireCheckHashes(Map<String,String> observed) {
        if(!CHECK_HASHES.equals(observed))throw new IllegalStateException("Approval CHECK definitions differ from frozen schema");
    }
    private static void verifyCheckDefinitions(Connection c)throws SQLException {
        String timezone,dateStyle;
        try(var q=c.createStatement();var rows=q.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")){if(!rows.next())throw new IllegalStateException("Missing schema session settings");timezone=rows.getString(1);dateStyle=rows.getString(2);}
        try {
            Map<String,String> definitions=new TreeMap<>();
            // CASE evaluates the local setting before deparsing; local settings vanish at autocommit.
            // For a caller transaction they are restored below, without changing session defaults.
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT conname,CASE WHEN set_config('TimeZone','UTC',true)='UTC' AND set_config('DateStyle','ISO, MDY',true)='ISO, MDY' THEN encode(sha256(convert_to(pg_get_constraintdef(oid),'UTF8')),'hex') END FROM pg_constraint WHERE conrelid='public.mop_change_notice_approval_event'::regclass AND contype='c' ORDER BY conname")) {
                while(rows.next())definitions.put(rows.getString(1),rows.getString(2));
            }
            requireCheckHashes(definitions);
        }finally {
            try(var restore=c.prepareStatement("SELECT set_config('TimeZone',?,true),set_config('DateStyle',?,true)")){restore.setString(1,timezone);restore.setString(2,dateStyle);restore.execute();}
        }
    }
    static void verify(Connection c)throws SQLException {
        String table="mop_change_notice_approval_event";
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        verifyColumns(c,table,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("notice_id","uuid"),Map.entry("draft_revision","bigint"),Map.entry("content_sha256","character varying(64)"),Map.entry("version","bigint"),Map.entry("from_state","character varying(16)"),Map.entry("to_state","character varying(16)"),Map.entry("action","character varying(16)"),Map.entry("actor_id","uuid"),Map.entry("permission_version","bigint"),Map.entry("review_policy_release_id","uuid"),Map.entry("note","character varying(2000)"),Map.entry("occurred_at","timestamp(3) with time zone"),Map.entry("effective","boolean"),Map.entry("device_execution_allowed","boolean"),Map.entry("alert_suppression_allowed","boolean")),Set.of("review_policy_release_id","note"),true);
        verifyRuntimeAcl(c,table,Set.of());
        requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)","public."+table);
        requireConstraint(c,table,"p",List.of("tenant_id","notice_id","draft_revision","version"),null,List.of(),false);
        requireConstraint(c,table,"f",List.of("tenant_id","notice_id","draft_revision"),"mop_change_notice_revision",List.of("tenant_id","notice_id","version"),false);
        requireConstraint(c,table,"f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,table,"f",List.of("tenant_id","review_policy_release_id"),"mop_runtime_configuration_release",List.of("tenant_id","id"),false);
        requireTrue(c,"SELECT count(*)=11 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid=to_regclass(?) AND contype='c'","public."+table);
        verifyCheckDefinitions(c);
        requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=31 AND NOT tgdeferrable AND NOT tginitdeferred AND tgfoid='public.mop_change_notice_approval_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal","public."+table);
        requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.mop_change_notice_approval_guard()')",GUARD_SHA256);
        requireTrue(c,"SELECT registry_json->'value'='null'::jsonb AND registry_json->>'contract_value_kind'='pending_customer_decision' FROM public.mop_configuration WHERE key='change_notice.approver_policy'");
    }
}
