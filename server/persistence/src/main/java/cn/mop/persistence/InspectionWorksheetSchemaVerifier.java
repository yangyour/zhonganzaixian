package cn.mop.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Explicit worksheet draft structure checks, never automatic migration or production approval. */
final class InspectionWorksheetSchemaVerifier {
    static final String SHA256="ab3f98ddf1c8803b8398347b9bc7d36af8d9741abdcb7591f29f1a2810791dc3";
    static final Map<String,String> FUNCTIONS=Map.of("mop_inspection_worksheet_head_guard","ce87c51b501e8aae96529bb674ba5b3f9ff0da0693e7858076221a8462cfc7e3","mop_inspection_worksheet_content_guard","4fcb087c6a00cf7dbfe6a59dbfa24a01da00e1a33cf1fd269241c9e5773f4177");
    private InspectionWorksheetSchemaVerifier() {}
    static void resource()throws Exception{
        try(var in=InspectionWorksheetSchemaVerifier.class.getResourceAsStream("/db/migration/V22__manual_inspection_worksheet_drafts.sql")){
            if(in==null)throw new IllegalStateException("Missing worksheet migration");byte[] bytes=in.readAllBytes();
            if(!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed worksheet migration");
            String sql=new String(bytes,StandardCharsets.UTF_8);
            for(var entry:FUNCTIONS.entrySet()){int start=sql.indexOf("CREATE FUNCTION public."+entry.getKey()+"()"),marker=start<0?-1:sql.indexOf(" AS $$",start),end=marker<0?-1:sql.indexOf("$$;",marker+6);if(marker<0||end<0||!entry.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed worksheet guard");}
        }
    }
    static void verify(Connection c)throws SQLException{
        String head="mop_inspection_worksheet",revision="mop_inspection_worksheet_revision";
        verifyColumns(c,head,Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("created_by","uuid"),Map.entry("asset_id","uuid"),Map.entry("asset_name","character varying(200)"),Map.entry("template_id","uuid"),Map.entry("template_version","bigint"),Map.entry("template_content_sha256","character varying(64)"),Map.entry("record_source","character varying(40)"),Map.entry("version","bigint"),Map.entry("created_at","timestamp(3) with time zone"),Map.entry("updated_at","timestamp(3) with time zone")),Set.of(),true);
        verifyColumns(c,revision,Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("worksheet_id","uuid"),Map.entry("version","bigint"),Map.entry("previous_version","bigint"),Map.entry("recorded_by","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("action","character varying(16)"),Map.entry("archived","boolean"),Map.entry("reported_performed_at","timestamp(3) with time zone"),Map.entry("observations","jsonb"),Map.entry("answered_count","integer"),Map.entry("content_sha256","character varying(64)"),Map.entry("verification_status","character varying(32)")),Set.of("previous_version","reported_performed_at"),true);
        verifyRuntimeAcl(c,head,Set.of("version","updated_at"));verifyRuntimeAcl(c,revision,Set.of());
        for(String table:List.of(head,revision)){requireTrue(c,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);requireTrue(c,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);}
        requireConstraint(c,head,"p",List.of("id"),null,List.of(),false);requireConstraint(c,head,"u",List.of("tenant_id","id"),null,List.of(),false);requireConstraint(c,head,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(c,head,"f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);requireConstraint(c,head,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);requireConstraint(c,head,"f",List.of("tenant_id","template_id","template_version"),"mop_inspection_template_revision",List.of("tenant_id","template_id","version"),false);
        requireConstraint(c,head,"f",List.of("tenant_id","id","version"),revision,List.of("tenant_id","worksheet_id","version"),true);
        requireConstraint(c,revision,"p",List.of("tenant_id","worksheet_id","version"),null,List.of(),false);requireConstraint(c,revision,"f",List.of("tenant_id","worksheet_id"),head,List.of("tenant_id","id"),false);requireConstraint(c,revision,"f",List.of("tenant_id","recorded_by"),"mop_membership",List.of("tenant_id","user_id"),false);requireConstraint(c,revision,"f",List.of("tenant_id","worksheet_id","previous_version"),revision,List.of("tenant_id","worksheet_id","version"),false);
        for(var entry:Map.of(head,7,revision,10).entrySet())requireTrue(c,"SELECT count(*)="+entry.getValue()+" FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",entry.getKey());
        requireTrue(c,"SELECT a.attgenerated='s' AND position('jsonb_array_length(observations)' IN replace(regexp_replace(pg_get_expr(d.adbin,d.adrelid),'[[:space:]]','','g'),'pg_catalog.',''))>0 FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_inspection_worksheet_revision'::regclass AND a.attname='answered_count' AND NOT a.attisdropped");
        for(String index:List.of("mop_inspection_worksheet_order","mop_inspection_worksheet_asset"))requireTrue(c,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid WHERE idx.relname=? AND idx.relnamespace='public'::regnamespace",index);
        for(var entry:FUNCTIONS.entrySet())requireTrue(c,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.'||?||'()')",entry.getValue(),entry.getKey());
        for(String table:List.of(head,revision))requireTrue(c,"SELECT count(*)=1 FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND NOT tgisinternal",table);
        trigger(c,head,"mop_inspection_worksheet_head_guard",23);trigger(c,revision,"mop_inspection_worksheet_content_guard",7);
    }
    private static void trigger(Connection c,String table,String name,int type)throws SQLException{requireTrue(c,"SELECT tgenabled='O' AND tgtype="+type+" AND tgfoid=to_regprocedure('public.'||?||'()') AND NOT tgdeferrable AND NOT tginitdeferred FROM pg_trigger WHERE tgrelid=('public.'||?)::regclass AND tgname=?",name,table,name);}
}
