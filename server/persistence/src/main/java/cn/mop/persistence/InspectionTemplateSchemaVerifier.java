package cn.mop.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Independently verified unpublished template structure. Never runs DDL from API/Worker startup. */
final class InspectionTemplateSchemaVerifier {
    static final String SHA256="85cf043f55b72fa1c06919ccf53705b516643468b97285980d436c3b78a4145b";
    static final Map<String,String> FUNCTIONS=Map.of("mop_inspection_template_head_guard","5be5640edb7c5ae07a270c441c4ee7ac540ab55ef557d0bfb49ea22e1784af78","mop_inspection_template_revision_guard","cd007d00c5c8af3a4e983d932734d77ec384033bd8481c9e0b74f347a2b83cf9");
    private InspectionTemplateSchemaVerifier() {}
    static void resource() throws Exception {
        try(var in=InspectionTemplateSchemaVerifier.class.getResourceAsStream("/db/migration/V21__inspection_template_revisions.sql")) {
            if(in==null)throw new IllegalStateException("Missing inspection migration"); byte[] bytes=in.readAllBytes();
            if(!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed inspection migration");
            String sql=new String(bytes,StandardCharsets.UTF_8);
            for(var entry:FUNCTIONS.entrySet()) {
                int start=sql.indexOf("CREATE FUNCTION public."+entry.getKey()+"()"),marker=start<0?-1:sql.indexOf(" AS $$",start),end=marker<0?-1:sql.indexOf("$$;",marker+6);
                if(marker<0||end<0||!entry.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed inspection guard");
            }
        }
    }
    static void verify(Connection c) throws SQLException {
        String head="mop_inspection_template",revision="mop_inspection_template_revision",item="mop_inspection_template_item";
        verifyColumns(c,head,Map.of("id","uuid","tenant_id","uuid","created_by","uuid","version","bigint","created_at","timestamp(3) with time zone","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(c,revision,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("template_id","uuid"),Map.entry("version","bigint"),Map.entry("previous_version","bigint"),Map.entry("recorded_by","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("action","character varying(16)"),Map.entry("archived","boolean"),Map.entry("device_type","character varying(64)"),Map.entry("name","character varying(200)"),Map.entry("item_count","integer"),Map.entry("content_sha256","character varying(64)"),Map.entry("approval_status","character varying(16)")),Set.of("previous_version"),true);
        verifyColumns(c,item,Map.of("tenant_id","uuid","template_id","uuid","version","bigint","position","integer","item_key","character varying(64)","section","character varying(100)","label","character varying(200)","instruction","character varying(2000)"),Set.of("instruction"),true);
        verifyRuntimeAcl(c,head,Set.of("version","updated_at"));verifyRuntimeAcl(c,revision,Set.of());verifyRuntimeAcl(c,item,Set.of());
        for(String table:List.of(head,revision,item)) {
            requireTrue(c,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);
            requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);
        }
        requireConstraint(c,head,"p",List.of("id"),null,List.of(),false);requireConstraint(c,head,"u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(c,head,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);requireConstraint(c,head,"f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(c,head,"f",List.of("tenant_id","id","version"),revision,List.of("tenant_id","template_id","version"),true);
        requireConstraint(c,revision,"p",List.of("tenant_id","template_id","version"),null,List.of(),false);requireConstraint(c,revision,"f",List.of("tenant_id","template_id"),head,List.of("tenant_id","id"),false);
        requireConstraint(c,revision,"f",List.of("tenant_id","recorded_by"),"mop_membership",List.of("tenant_id","user_id"),false);requireConstraint(c,revision,"f",List.of("tenant_id","template_id","previous_version"),revision,List.of("tenant_id","template_id","version"),false);
        requireConstraint(c,item,"p",List.of("tenant_id","template_id","version","position"),null,List.of(),false);requireConstraint(c,item,"u",List.of("tenant_id","template_id","version","item_key"),null,List.of(),false);requireConstraint(c,item,"f",List.of("tenant_id","template_id","version"),revision,List.of("tenant_id","template_id","version"),false);
        for(var entry:Map.of(head,3,revision,10,item,5).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",entry.getKey());
        requireTrue(c,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid WHERE idx.relname='mop_inspection_template_order' AND idx.relnamespace='public'::regnamespace");
        for(var entry:FUNCTIONS.entrySet())requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.'||?||'()')",entry.getValue(),entry.getKey());
        for(String table:List.of(head,revision,item))requireTrue(c,"SELECT count(*)=1 FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND NOT tgisinternal",table);
        trigger(c,head,"mop_inspection_template_head_guard","mop_inspection_template_head_guard",23,false);
        trigger(c,revision,"mop_inspection_template_revision_complete","mop_inspection_template_revision_guard",5,true);
        trigger(c,item,"mop_inspection_template_items_complete","mop_inspection_template_revision_guard",5,true);
    }
    private static void trigger(Connection c,String table,String name,String function,int type,boolean deferred)throws SQLException {
        requireTrue(c,"SELECT tgenabled='O' AND tgtype="+type+" AND tgfoid=to_regprocedure('public.'||?||'()') AND tgdeferrable="+deferred+" AND tginitdeferred="+deferred+" FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND tgname=?",function,table,name);
    }
}
