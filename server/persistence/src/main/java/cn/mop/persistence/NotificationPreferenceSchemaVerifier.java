package cn.mop.persistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;
/** Explicit draft structure only. Never runs DDL or enables a notification preference. */
final class NotificationPreferenceSchemaVerifier {
    static final String SHA256="df3c59cc6c241316b110ae87b3cb7a80aee57b4847f4122e7786b90ac94c23d8";
    static final Map<String,String> FUNCTIONS=Map.of("mop_notification_preference_head_guard","3b98942ebec99912097f70061639e77ed534bc8b56d24718195c4e8d60ce97db","mop_notification_preference_insert_guard","d587dff6578353ddc7a35ec4bf540a3ab9a07b4e5e016a8e4ef3d28f7b773419","mop_notification_preference_complete","2330c1bd33af739a119e93f8c8b426ab8cf0d1c783775d8a9cf4df65e6b8059b");
    private NotificationPreferenceSchemaVerifier(){}
    static void resource() throws Exception {
        try(var in=NotificationPreferenceSchemaVerifier.class.getResourceAsStream("/db/migration/V26__own_notification_preference_drafts.sql")) {
            if(in==null)throw new IllegalStateException("Missing notification preference migration"); byte[] bytes=in.readAllBytes();
            if(!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed notification preference migration");
            String sql=new String(bytes,StandardCharsets.UTF_8);
            for(var entry:FUNCTIONS.entrySet()) {
                int start=sql.indexOf("CREATE FUNCTION public."+entry.getKey()+"()"),marker=start<0?-1:sql.indexOf(" AS $$",start),end=marker<0?-1:sql.indexOf("$$;",marker+6);
                if(marker<0||end<0||!entry.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed notification preference guard");
            }
        }
    }
    static void verify(Connection c)throws SQLException {
        String head="mop_notification_preference_draft",revision="mop_notification_preference_revision";
        verifyColumns(c,head,Map.of("id","uuid","tenant_id","uuid","owner_id","uuid","version","bigint","created_at","timestamp(3) with time zone","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(c,revision,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("preference_id","uuid"),Map.entry("owner_id","uuid"),Map.entry("version","bigint"),Map.entry("previous_version","bigint"),Map.entry("recorded_by","uuid"),Map.entry("recorded_permission_version","bigint"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("creation_xid","xid8"),Map.entry("action","character varying(16)"),Map.entry("requested_level","character varying(16)"),Map.entry("requested_channels","text[]"),Map.entry("quiet_window_supplied","boolean"),Map.entry("quiet_enabled","boolean"),Map.entry("quiet_start","character varying(5)"),Map.entry("quiet_end","character varying(5)"),Map.entry("content_sha256","character varying(64)"),Map.entry("timezone","character varying(64)"),Map.entry("configuration_status","character varying(32)"),Map.entry("effective","boolean"),Map.entry("effective_level","text"),Map.entry("effective_channels","text[]"),Map.entry("effective_quiet_window","jsonb"),Map.entry("verified_receiver_id","uuid"),Map.entry("receiver_binding_status","character varying(32)"),Map.entry("delivery_decision","boolean"),Map.entry("notification_dispatched","boolean"),Map.entry("record_source","character varying(48)")),Set.of("previous_version","requested_level","requested_channels","quiet_enabled","quiet_start","quiet_end","effective_level","effective_channels","effective_quiet_window","verified_receiver_id","delivery_decision"),true);
        verifyRuntimeAcl(c,head,Set.of("version","updated_at"));verifyRuntimeAcl(c,revision,Set.of());
        for(String table:List.of(head,revision)){
            requireTrue(c,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);
            requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);
        }
        requireConstraint(c,head,"p",List.of("id"),null,List.of(),false);
        requireConstraint(c,head,"u",List.of("tenant_id","owner_id"),null,List.of(),false);
        requireConstraint(c,head,"u",List.of("tenant_id","id","owner_id"),null,List.of(),false);
        requireConstraint(c,head,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(c,head,"f",List.of("tenant_id","owner_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,head,"f",List.of("tenant_id","id","version"),revision,List.of("tenant_id","preference_id","version"),true);
        requireConstraint(c,revision,"p",List.of("tenant_id","preference_id","version"),null,List.of(),false);
        requireConstraint(c,revision,"f",List.of("tenant_id","preference_id","owner_id"),head,List.of("tenant_id","id","owner_id"),false);
        requireConstraint(c,revision,"f",List.of("tenant_id","recorded_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,revision,"f",List.of("tenant_id","preference_id","previous_version"),revision,List.of("tenant_id","preference_id","version"),false);
        for(var entry:Map.of(head,3,revision,21).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",entry.getKey());
        for(var entry:FUNCTIONS.entrySet())requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.'||?||'()')",entry.getValue(),entry.getKey());
        for(var entry:Map.of(head,1,revision,2).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND NOT tgisinternal",entry.getKey());
        trigger(c,head,"mop_notification_preference_head_guard","mop_notification_preference_head_guard",23,false);
        trigger(c,revision,"mop_notification_preference_insert_guard","mop_notification_preference_insert_guard",7,false);
        trigger(c,revision,"mop_notification_preference_complete","mop_notification_preference_complete",5,true);
    }
    private static void trigger(Connection c,String table,String name,String function,int type,boolean deferred)throws SQLException {
        requireTrue(c,"SELECT tgenabled='O' AND tgtype="+type+" AND tgfoid=to_regprocedure('public.'||?||'()') AND tgdeferrable="+deferred+" AND tginitdeferred="+deferred+" FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND tgname=?",function,table,name);
    }
}
