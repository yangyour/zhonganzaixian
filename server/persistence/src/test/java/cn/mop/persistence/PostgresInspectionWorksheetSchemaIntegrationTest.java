package cn.mop.persistence;

import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionWorksheet.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual restricted PG checks. Synthetic worksheet/template rows roll back; not API authorization or performance proof. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresInspectionWorksheetSchemaIntegrationTest {
    static HikariDataSource pool;
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    static final ObjectMapper JSON=new ObjectMapper();
    Connection c; String actor,assetId,assetName,templateId; InspectionTemplate template;
    PostgresInspectionTemplateSchemaIntegrationTest templates;
    @BeforeAll static void connect(){
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);
    }
    @BeforeEach void begin()throws Exception{
        c=pool.getConnection();c.setAutoCommit(false);
        actor=scalar("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"' AND role_code='padmin' AND active ORDER BY user_id LIMIT 1");
        assetId=scalar("SELECT id::text FROM public.mop_asset WHERE tenant_id='"+TENANT+"' ORDER BY id LIMIT 1");
        assetName=scalar("SELECT name FROM public.mop_asset WHERE id='"+assetId+"'");
        templates=new PostgresInspectionTemplateSchemaIntegrationTest();templates.c=c;templates.actor=actor;
        template=templates.definition();templateId=templates.create();
    }
    @AfterEach void rollback()throws Exception{if(c!=null){c.rollback();c.close();}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    void sql(String query)throws SQLException{try(var s=c.createStatement()){s.execute(query);}}
    String scalar(String query)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getString(1);}}
    @FunctionalInterface interface Action{void run()throws Exception;}
    void rejected(String state,Action action)throws Exception{var point=c.setSavepoint();var error=assertThrows(SQLException.class,action::run);assertEquals(state,error.getSQLState());c.rollback(point);c.releaseSavepoint(point);}
    void validate()throws SQLException{sql("SET CONSTRAINTS ALL IMMEDIATE");sql("SET CONSTRAINTS ALL DEFERRED");}
    InspectionWorksheetContent content(Map<String,Observation> values,Instant performed){
        var snapshot=InspectionTemplate.Snapshot.capture(UUID.fromString(TENANT),UUID.fromString(templateId),1,template);
        return new InspectionWorksheetContent(new InspectionWorksheet(UUID.fromString(TENANT),UUID.fromString(assetId),assetName,snapshot,values),performed);
    }
    Observation observation(Result result,String note){return new Observation(result,note,List.of());}
    String observations(InspectionWorksheetContent content)throws Exception{
        var rows=new ArrayList<Map<String,Object>>();
        // Intentionally reverse template order: SQL must canonicalize by frozen position, not JSON order.
        var items=new ArrayList<>(template.items());Collections.reverse(items);
        for(var item:items){var observation=content.worksheet().observations().get(item.key());if(observation==null)continue;var row=new LinkedHashMap<String,Object>();row.put("key",item.key());row.put("result",observation.result().name());row.put("note",observation.note());rows.add(row);}
        return JSON.writeValueAsString(rows);
    }
    String head()throws SQLException{return head(TENANT,assetId,assetName,templateId,1,template.fingerprint());}
    String head(String tenant,String asset,String name,String source,int version,String sha)throws SQLException{
        String id=UUID.randomUUID().toString();try(var s=c.prepareStatement("INSERT INTO public.mop_inspection_worksheet(id,tenant_id,created_by,asset_id,asset_name,template_id,template_version,template_content_sha256) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?::uuid,?,?)")){
            s.setString(1,id);s.setString(2,tenant);s.setString(3,actor);s.setString(4,asset);s.setString(5,name);s.setString(6,source);s.setInt(7,version);s.setString(8,sha);s.executeUpdate();}return id;
    }
    void revision(String id,int version,String action,InspectionWorksheetContent content,String json,String sha)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_inspection_worksheet_revision(tenant_id,worksheet_id,version,previous_version,recorded_by,action,archived,reported_performed_at,observations,content_sha256) VALUES(?::uuid,?::uuid,?,?,?::uuid,?,?,?,?::jsonb,?)")){
            s.setString(1,TENANT);s.setString(2,id);s.setInt(3,version);if(version==1)s.setNull(4,Types.BIGINT);else s.setInt(4,version-1);s.setString(5,actor);s.setString(6,action);s.setBoolean(7,"ARCHIVE".equals(action));
            s.setTimestamp(8,content.reportedPerformedAt()==null?null:Timestamp.from(content.reportedPerformedAt()));s.setString(9,json);s.setString(10,sha);s.executeUpdate();}
    }
    void complete(String id,int version,String action,InspectionWorksheetContent content)throws Exception{
        if(version>1)sql("UPDATE public.mop_inspection_worksheet SET version="+version+",updated_at=statement_timestamp() WHERE id='"+id+"'");
        revision(id,version,action,content,observations(content),content.fingerprint());validate();
    }
    String create(InspectionWorksheetContent content)throws Exception{String id=head();complete(id,1,"CREATE",content);return id;}
    String row(String id,int version)throws SQLException{return scalar("SELECT to_jsonb(r)::text FROM public.mop_inspection_worksheet_revision r WHERE worksheet_id='"+id+"' AND version="+version);}
    @Test void actualStructureRuntimePrivilegesAndFunctionDigestsMatch()throws Exception{InspectionWorksheetSchemaVerifier.verify(c);assertEquals("mop_test_runtime",scalar("SELECT current_user"));}
    @Test void emptyWorksheetKeepsUnknownTimeAndZeroAnswered()throws Exception{var content=content(Map.of(),null);String id=create(content);assertEquals("0",scalar("SELECT answered_count FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"'"));assertEquals("true",scalar("SELECT (reported_performed_at IS NULL)::text FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"'"));assertEquals("SELF_REPORTED_UNVERIFIED",scalar("SELECT verification_status FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"'"));assertEquals(2,content.worksheet().summary().unanswered());}
    @Test void javaFingerprintMatchesUtf8MultilineReverseJsonAndExplicitTime()throws Exception{var content=content(Map.of("firmware",observation(Result.ATTENTION,"😀\n第一行\r\n第二行\t'"),"line_quality",observation(Result.REQUIRES_ACTION,"")),Instant.parse("2026-01-02T03:04:05.123Z"));String id=create(content);assertEquals(content.fingerprint(),scalar("SELECT content_sha256 FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"'"));assertEquals("2",scalar("SELECT answered_count FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"'"));}
    @Test void nullAndEmptyNotesRemainDifferentImmutableContent()throws Exception{var first=content(Map.of("firmware",observation(Result.NORMAL,null)),null);var next=content(Map.of("firmware",observation(Result.NORMAL,"")),null);assertNotEquals(first.fingerprint(),next.fingerprint());String id=create(first),old=row(id,1);complete(id,2,"UPDATE",next);assertEquals(old,row(id,1));assertEquals("true",scalar("SELECT (observations->0->'note'='null'::jsonb)::text FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"' AND version=1"));}
    @Test void generatedAnsweredCountCannotBeProvidedByCaller()throws Exception{String id=head();rejected("428C9",()->sql("INSERT INTO public.mop_inspection_worksheet_revision(tenant_id,worksheet_id,version,recorded_by,action,observations,answered_count,content_sha256) VALUES('"+TENANT+"','"+id+"',1,'"+actor+"','CREATE','[]',99,'"+"0".repeat(64)+"')"));}
    @Test void missingRevisionCannotCrossDeferredCommitBoundary()throws Exception{rejected("23503",()->{head();validate();});}
    @Test void laterRevisionRequiresExactlyAdvancedHead()throws Exception{var value=content(Map.of(),null);String id=create(value);rejected("23514",()->revision(id,2,"UPDATE",value,"[]",value.fingerprint()));for(int version:List.of(0,1,3))rejected("23514",()->sql("UPDATE public.mop_inspection_worksheet SET version="+version+" WHERE id='"+id+"'"));}
    @Test void oldTemplateVersionArchivedTemplateAndForgedSourceHashAreRejected()throws Exception{rejected("23514",()->head(TENANT,assetId,assetName,templateId,1,"0".repeat(64)));templates.complete(templateId,2,"UPDATE",template);rejected("23514",this::head);templates.complete(templateId,3,"ARCHIVE",template);rejected("23514",()->head(TENANT,assetId,assetName,templateId,3,template.fingerprint()));}
    @Test void historicalWorksheetStaysOnCapturedTemplateAfterParentChangesAndArchive()throws Exception{var value=content(Map.of("firmware",observation(Result.ATTENTION,null)),null);String id=create(value);String old=row(id,1);templates.complete(templateId,2,"UPDATE",new InspectionTemplate("new_type","另一模板修订",List.of(new InspectionTemplate.Item("other","新组","新项",null))));templates.complete(templateId,3,"ARCHIVE",new InspectionTemplate("new_type","另一模板修订",List.of(new InspectionTemplate.Item("other","新组","新项",null))));complete(id,2,"UPDATE",value);assertEquals(old,row(id,1));assertEquals("1",scalar("SELECT template_version FROM public.mop_inspection_worksheet WHERE id='"+id+"'"));}
    @Test void unknownOrForeignAssetAndTemplateCannotBind()throws Exception{rejected("23503",()->head(TENANT,UUID.randomUUID().toString(),assetName,templateId,1,template.fingerprint()));rejected("23503",()->head(TENANT,assetId,assetName,UUID.randomUUID().toString(),1,template.fingerprint()));String otherAsset=scalar("SELECT id::text FROM public.mop_asset WHERE tenant_id<>'"+TENANT+"' ORDER BY id LIMIT 1");rejected("23503",()->head(TENANT,otherAsset,assetName,templateId,1,template.fingerprint()));rejected("23503",()->head(UUID.randomUUID().toString(),assetId,assetName,templateId,1,template.fingerprint()));}
    @Test void capturedAssetNameMustMatchRegisteredRow()throws Exception{rejected("23514",()->head(TENANT,assetId,"伪造名称",templateId,1,template.fingerprint()));}
    @Test void observationKeyMustBeUniqueAndBelongToFrozenTemplate()throws Exception{String id=head();var value=content(Map.of(),null);for(String json:List.of("[{\"key\":\"missing\",\"result\":\"NORMAL\",\"note\":null}]","[{\"key\":\"firmware\",\"result\":\"NORMAL\",\"note\":null},{\"key\":\"firmware\",\"result\":\"NORMAL\",\"note\":null}]"))rejected("23514",()->revision(id,1,"CREATE",value,json,value.fingerprint()));}
    @Test void unknownFieldsEvidenceReferencesBadEnumsAndWrongJsonTypesAreRejected()throws Exception{String id=head();var value=content(Map.of(),null);for(String json:List.of("{}","null","[{\"key\":\"firmware\",\"result\":\"NORMAL\",\"note\":null,\"evidence_ids\":[]}]","[{\"key\":\"firmware\",\"result\":\"AUTO_NORMAL\",\"note\":null}]","[{\"key\":null,\"result\":\"NORMAL\",\"note\":null}]","[{\"key\":\"firmware\",\"result\":\"NORMAL\",\"note\":42}]"))rejected("23514",()->revision(id,1,"CREATE",value,json,value.fingerprint()));}
    @Test void forgedContentAndFutureReportedTimeFail()throws Exception{String id=head();var value=content(Map.of(),null);rejected("23514",()->revision(id,1,"CREATE",value,"[]","0".repeat(64)));var future=content(Map.of(),Instant.parse("9999-12-31T23:59:59.999Z"));rejected("23514",()->revision(id,1,"CREATE",future,"[]",future.fingerprint()));}
    @Test void explicitArchiveRestoreRetainsAllContentHistory()throws Exception{var value=content(Map.of("firmware",observation(Result.ATTENTION,"待核实")),null);String id=create(value),old=row(id,1);complete(id,2,"ARCHIVE",value);complete(id,3,"RESTORE",content(Map.of(),null));assertEquals(old,row(id,1));assertEquals("CREATE,ARCHIVE,RESTORE",scalar("SELECT string_agg(action,',' ORDER BY version) FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"'"));}
    @Test void archiveCannotChangeContentAndUpdateCannotBypassRestore()throws Exception{var empty=content(Map.of(),null);var changed=content(Map.of("firmware",observation(Result.NORMAL,null)),null);String id=create(empty);rejected("23514",()->complete(id,2,"ARCHIVE",changed));rejected("23514",()->complete(id,2,"RESTORE",empty));complete(id,2,"ARCHIVE",empty);rejected("23514",()->complete(id,3,"UPDATE",empty));}
    @Test void runtimeCannotRewriteHistoryDeleteRelinkOrClaimVerification()throws Exception{for(String query:List.of("UPDATE public.mop_inspection_worksheet_revision SET observations='[]' WHERE false","UPDATE public.mop_inspection_worksheet_revision SET verification_status='VERIFIED' WHERE false","UPDATE public.mop_inspection_worksheet SET template_version=2 WHERE false","UPDATE public.mop_inspection_worksheet SET asset_id=gen_random_uuid() WHERE false","UPDATE public.mop_inspection_worksheet SET tenant_id=gen_random_uuid() WHERE false","DELETE FROM public.mop_inspection_worksheet WHERE false","DELETE FROM public.mop_inspection_worksheet_revision WHERE false","TRUNCATE public.mop_inspection_worksheet"))rejected("42501",()->sql(query));}
    @Test void overlongAndControlCharacterNotesFailWithoutChangingExistingRows()throws Exception{String id=head();var value=content(Map.of(),null);for(String note:List.of("😀".repeat(3001),"bad\u0001")){var entry=new LinkedHashMap<String,Object>();entry.put("key","firmware");entry.put("result","NORMAL");entry.put("note",note);String json=JSON.writeValueAsString(List.of(entry));rejected("23514",()->revision(id,1,"CREATE",value,json,value.fingerprint()));}assertEquals("0",scalar("SELECT count(*) FROM public.mop_inspection_worksheet_revision WHERE worksheet_id='"+id+"'"));}
}
