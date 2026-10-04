package cn.mop.persistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Explicit draft structure only. Never runs DDL or enables a change notice. */
final class ChangeNoticeSchemaVerifier {
    static final String SHA256="650ce8f83e9fc673cfb6a4838872638149650f735a90f37c5861a6fce771bc67";
    static final Map<String,String> FUNCTIONS=Map.of("mop_change_notice_head_guard","e87e125b2ed94fd4c070b2c68dcb76c10d63fc42258d5b633393f7916571bfcf","mop_change_notice_revision_insert_guard","ccbd715508cfe5e3a79f8b6a9094ace45d58eac7f3f944325a105170fe2c769a","mop_change_notice_asset_insert_guard","cf3899c4ea8863d6abccf489cf289bd279b82c07665f6fc0260fe9a8596b0924","mop_change_notice_revision_complete","5d8fb28f0723101d5f091010d860f1c558aeb9b094b239a1d84df61c246157ab");
    private ChangeNoticeSchemaVerifier(){}
    static void resource() throws Exception {
        try(var in=ChangeNoticeSchemaVerifier.class.getResourceAsStream("/db/migration/V25__unreviewed_change_notice_drafts.sql")) {
            if(in==null)throw new IllegalStateException("Missing change notice migration"); byte[] bytes=in.readAllBytes();
            if(!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed change notice migration");
            String sql=new String(bytes,StandardCharsets.UTF_8);
            for(var entry:FUNCTIONS.entrySet()) {
                int start=sql.indexOf("CREATE FUNCTION public."+entry.getKey()+"()"),marker=start<0?-1:sql.indexOf(" AS $$",start),end=marker<0?-1:sql.indexOf("$$;",marker+6);
                if(marker<0||end<0||!entry.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed change notice guard");
            }
        }
    }
    static void verify(Connection c)throws SQLException {
        String head="mop_change_notice_draft",revision="mop_change_notice_revision",target="mop_change_notice_asset";
        verifyColumns(c,head,Map.of("id","uuid","tenant_id","uuid","applicant_id","uuid","version","bigint","created_at","timestamp(3) with time zone","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(c,revision,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("notice_id","uuid"),Map.entry("version","bigint"),Map.entry("previous_version","bigint"),Map.entry("recorded_by","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("creation_xid","xid8"),Map.entry("action","character varying(16)"),Map.entry("archived","boolean"),Map.entry("ticket_id","uuid"),Map.entry("change_summary","character varying(2000)"),Map.entry("planned_start_at","timestamp(3) with time zone"),Map.entry("planned_end_at","timestamp(3) with time zone"),Map.entry("asset_count","integer"),Map.entry("content_sha256","character varying(64)"),Map.entry("timezone","character varying(64)"),Map.entry("lifecycle_status","character varying(16)"),Map.entry("approval_workflow_status","character varying(32)"),Map.entry("approval_status","text"),Map.entry("matching_policy_status","character varying(32)"),Map.entry("automatic_p3_decision","text"),Map.entry("effective","boolean"),Map.entry("approval_evidence_verified","boolean"),Map.entry("device_execution_allowed","boolean"),Map.entry("alert_suppression_allowed","boolean"),Map.entry("record_source","character varying(40)")),Set.of("previous_version","ticket_id","planned_end_at","approval_status","automatic_p3_decision"),true);
        verifyColumns(c,target,Map.of("tenant_id","uuid","notice_id","uuid","version","bigint","position","integer","asset_id","uuid"),Set.of(),true);
        verifyRuntimeAcl(c,head,Set.of("version","updated_at"));verifyRuntimeAcl(c,revision,Set.of());verifyRuntimeAcl(c,target,Set.of());
        for(String table:List.of(head,revision,target)){
            requireTrue(c,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);
            requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);
        }
        requireConstraint(c,head,"p",List.of("id"),null,List.of(),false);requireConstraint(c,head,"u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(c,head,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);requireConstraint(c,head,"f",List.of("tenant_id","applicant_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,head,"f",List.of("tenant_id","id","version"),revision,List.of("tenant_id","notice_id","version"),true);
        requireConstraint(c,revision,"p",List.of("tenant_id","notice_id","version"),null,List.of(),false);requireConstraint(c,revision,"f",List.of("tenant_id","notice_id"),head,List.of("tenant_id","id"),false);
        requireConstraint(c,revision,"f",List.of("tenant_id","recorded_by"),"mop_membership",List.of("tenant_id","user_id"),false);requireConstraint(c,revision,"f",List.of("tenant_id","notice_id","previous_version"),revision,List.of("tenant_id","notice_id","version"),false);
        requireConstraint(c,revision,"f",List.of("tenant_id","ticket_id"),"mop_ticket",List.of("tenant_id","id"),false);
        requireConstraint(c,target,"p",List.of("tenant_id","notice_id","version","position"),null,List.of(),false);requireConstraint(c,target,"u",List.of("tenant_id","notice_id","version","asset_id"),null,List.of(),false);
        requireConstraint(c,target,"f",List.of("tenant_id","notice_id","version"),revision,List.of("tenant_id","notice_id","version"),false);requireConstraint(c,target,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);
        for(var entry:Map.of(head,3,revision,21,target,1).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",entry.getKey());
        requireTrue(c,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid WHERE idx.relname='mop_change_notice_draft_order' AND idx.relnamespace='public'::regnamespace");
        for(var entry:FUNCTIONS.entrySet())requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.'||?||'()')",entry.getValue(),entry.getKey());
        for(var entry:Map.of(head,1,revision,2,target,1).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND NOT tgisinternal",entry.getKey());
        trigger(c,head,"mop_change_notice_head_guard","mop_change_notice_head_guard",23,false);
        trigger(c,revision,"mop_change_notice_revision_insert_guard","mop_change_notice_revision_insert_guard",7,false);
        trigger(c,revision,"mop_change_notice_revision_complete","mop_change_notice_revision_complete",5,true);
        trigger(c,target,"mop_change_notice_asset_insert_guard","mop_change_notice_asset_insert_guard",7,false);
    }
    private static void trigger(Connection c,String table,String name,String function,int type,boolean deferred)throws SQLException {
        requireTrue(c,"SELECT tgenabled='O' AND tgtype="+type+" AND tgfoid=to_regprocedure('public.'||?||'()') AND tgdeferrable="+deferred+" AND tginitdeferred="+deferred+" FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND tgname=?",function,table,name);
    }
}
