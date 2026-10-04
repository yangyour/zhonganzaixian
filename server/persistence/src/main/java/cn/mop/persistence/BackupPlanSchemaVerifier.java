package cn.mop.persistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Explicit draft structure only. Never runs DDL or enables a backup plan. */
final class BackupPlanSchemaVerifier {
    static final String SHA256="3b28fe6679d787faafdf0d5ea175712ec622b5017c103650cfa42ff12c73816a";
    static final Map<String,String> FUNCTIONS=Map.of("mop_backup_plan_head_guard","5397288405a2b6a1e4c805649693c391c42f9427c5a203f6eed43599692f7f89","mop_backup_plan_revision_insert_guard","74a39ce6047d92f4b6354e56cb27af595c05704065b916cd9a1ef68821c814bb","mop_backup_plan_target_insert_guard","1e56808544a4468ec28318a3f47389ba80348d12a91caeadd788d105b7f4939d","mop_backup_plan_revision_complete","47dc881cdec9bbb446c879dde4614847afce270eb60b4afd9b0ba4c9f4273822");
    private BackupPlanSchemaVerifier(){}
    static void resource() throws Exception {
        try(var in=BackupPlanSchemaVerifier.class.getResourceAsStream("/db/migration/V24__unpublished_backup_plan_drafts.sql")) {
            if(in==null)throw new IllegalStateException("Missing backup plan migration"); byte[] bytes=in.readAllBytes();
            if(!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed backup plan migration");
            String sql=new String(bytes,StandardCharsets.UTF_8);
            for(var entry:FUNCTIONS.entrySet()) {
                int start=sql.indexOf("CREATE FUNCTION public."+entry.getKey()+"()"),marker=start<0?-1:sql.indexOf(" AS $$",start),end=marker<0?-1:sql.indexOf("$$;",marker+6);
                if(marker<0||end<0||!entry.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed backup plan guard");
            }
        }
    }
    static void verify(Connection c)throws SQLException {
        String head="mop_backup_plan_draft",revision="mop_backup_plan_revision",target="mop_backup_plan_target";
        verifyColumns(c,head,Map.of("id","uuid","tenant_id","uuid","created_by","uuid","version","bigint","created_at","timestamp(3) with time zone","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(c,revision,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("plan_id","uuid"),Map.entry("version","bigint"),Map.entry("previous_version","bigint"),Map.entry("recorded_by","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("creation_xid","xid8"),Map.entry("action","character varying(16)"),Map.entry("archived","boolean"),Map.entry("name","character varying(100)"),Map.entry("daily_local_time","character varying(5)"),Map.entry("timezone","character varying(64)"),Map.entry("credential_ref","uuid"),Map.entry("requested_enabled","boolean"),Map.entry("target_count","integer"),Map.entry("note","character varying(4000)"),Map.entry("lifecycle_status","character varying(16)"),Map.entry("approval_status","character varying(16)"),Map.entry("enabled","boolean"),Map.entry("effective","boolean")),Set.of("previous_version","credential_ref","note"),true);
        verifyColumns(c,target,Map.of("tenant_id","uuid","plan_id","uuid","version","bigint","position","integer","asset_id","uuid"),Set.of(),true);
        verifyRuntimeAcl(c,head,Set.of("version","updated_at"));verifyRuntimeAcl(c,revision,Set.of());verifyRuntimeAcl(c,target,Set.of());
        for(String table:List.of(head,revision,target)){
            requireTrue(c,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);
            requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);
        }
        requireConstraint(c,head,"p",List.of("id"),null,List.of(),false);requireConstraint(c,head,"u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(c,head,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);requireConstraint(c,head,"f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,head,"f",List.of("tenant_id","id","version"),revision,List.of("tenant_id","plan_id","version"),true);
        requireConstraint(c,revision,"p",List.of("tenant_id","plan_id","version"),null,List.of(),false);requireConstraint(c,revision,"f",List.of("tenant_id","plan_id"),head,List.of("tenant_id","id"),false);
        requireConstraint(c,revision,"f",List.of("tenant_id","recorded_by"),"mop_membership",List.of("tenant_id","user_id"),false);requireConstraint(c,revision,"f",List.of("tenant_id","plan_id","previous_version"),revision,List.of("tenant_id","plan_id","version"),false);
        requireConstraint(c,target,"p",List.of("tenant_id","plan_id","version","position"),null,List.of(),false);requireConstraint(c,target,"u",List.of("tenant_id","plan_id","version","asset_id"),null,List.of(),false);
        requireConstraint(c,target,"f",List.of("tenant_id","plan_id","version"),revision,List.of("tenant_id","plan_id","version"),false);requireConstraint(c,target,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);
        for(var entry:Map.of(head,3,revision,14,target,1).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",entry.getKey());
        requireTrue(c,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid WHERE idx.relname='mop_backup_plan_draft_order' AND idx.relnamespace='public'::regnamespace");
        for(var entry:FUNCTIONS.entrySet())requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.'||?||'()')",entry.getValue(),entry.getKey());
        for(var entry:Map.of(head,1,revision,2,target,1).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND NOT tgisinternal",entry.getKey());
        trigger(c,head,"mop_backup_plan_head_guard","mop_backup_plan_head_guard",23,false);
        trigger(c,revision,"mop_backup_plan_revision_insert_guard","mop_backup_plan_revision_insert_guard",7,false);
        trigger(c,revision,"mop_backup_plan_revision_complete","mop_backup_plan_revision_complete",5,true);
        trigger(c,target,"mop_backup_plan_target_insert_guard","mop_backup_plan_target_insert_guard",7,false);
    }
    private static void trigger(Connection c,String table,String name,String function,int type,boolean deferred)throws SQLException {
        requireTrue(c,"SELECT tgenabled='O' AND tgtype="+type+" AND tgfoid=to_regprocedure('public.'||?||'()') AND tgdeferrable="+deferred+" AND tginitdeferred="+deferred+" FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND tgname=?",function,table,name);
    }
}
