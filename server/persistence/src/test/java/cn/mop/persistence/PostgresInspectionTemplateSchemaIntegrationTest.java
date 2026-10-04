package cn.mop.persistence;

import cn.mop.domain.inspection.InspectionTemplate;
import cn.mop.domain.inspection.InspectionTemplate.Item;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual restricted PG structure checks, isolated synthetic rows roll back; no repository/API authorization claim. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresInspectionTemplateSchemaIntegrationTest {
    static HikariDataSource pool;
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    Connection c; String actor;
    @BeforeAll static void connect() {
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);
    }
    @BeforeEach void begin() throws Exception {c=pool.getConnection();c.setAutoCommit(false);actor=scalar("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"' AND role_code='padmin' AND active ORDER BY user_id LIMIT 1");}
    @AfterEach void rollback() throws Exception {if(c!=null){c.rollback();c.close();}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    void sql(String value)throws SQLException{try(var s=c.createStatement()){s.execute(value);}}
    String scalar(String value)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(value)){assertTrue(r.next());return r.getString(1);}}
    @FunctionalInterface interface Action{void run()throws Exception;}
    void rejected(String state,Action action)throws Exception{var point=c.setSavepoint();var error=assertThrows(SQLException.class,action::run);assertEquals(state,error.getSQLState());c.rollback(point);c.releaseSavepoint(point);}
    void validate()throws SQLException{sql("SET CONSTRAINTS ALL IMMEDIATE");sql("SET CONSTRAINTS ALL DEFERRED");}
    InspectionTemplate definition(){return new InspectionTemplate("router","明确合成模板",List.of(new Item("firmware","安全合规","固件版本",null),new Item("line_quality","线路质量","人工观察","第一行\n第二行 ' ")) );}
    String head()throws SQLException{String id=UUID.randomUUID().toString();try(var s=c.prepareStatement("INSERT INTO public.mop_inspection_template(id,tenant_id,created_by) VALUES (?::uuid,?::uuid,?::uuid)")){s.setString(1,id);s.setString(2,TENANT);s.setString(3,actor);s.executeUpdate();}return id;}
    void revision(String id,int version,String action,InspectionTemplate template,String hash,int count)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_inspection_template_revision(tenant_id,template_id,version,previous_version,recorded_by,action,archived,device_type,name,item_count,content_sha256) VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?,?)")){
            s.setString(1,TENANT);s.setString(2,id);s.setInt(3,version);if(version==1)s.setNull(4,Types.BIGINT);else s.setLong(4,version-1);s.setString(5,actor);s.setString(6,action);s.setBoolean(7,"ARCHIVE".equals(action));s.setString(8,template.deviceType());s.setString(9,template.name());s.setInt(10,count);s.setString(11,hash);s.executeUpdate();
        }
    }
    void item(String id,int version,int position,Item item)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_inspection_template_item(tenant_id,template_id,version,position,item_key,section,label,instruction) VALUES (?::uuid,?::uuid,?,?,?,?,?,?)")){s.setString(1,TENANT);s.setString(2,id);s.setInt(3,version);s.setInt(4,position);s.setString(5,item.key());s.setString(6,item.section());s.setString(7,item.label());s.setString(8,item.instruction());s.executeUpdate();}
    }
    void complete(String id,int version,String action,InspectionTemplate definition)throws SQLException{
        revision(id,version,action,definition,definition.fingerprint(),definition.items().size());int position=0;for(var item:definition.items())item(id,version,++position,item);
        if(version>1)sql("UPDATE public.mop_inspection_template SET version="+version+",updated_at=statement_timestamp() WHERE id='"+id+"'");validate();
    }
    String create()throws SQLException{String id=head();complete(id,1,"CREATE",definition());return id;}
    @Test void actualRuntimeStructureAclAndFunctionHashesMatch()throws Exception{InspectionTemplateSchemaVerifier.verify(c);assertEquals("mop_test_runtime",scalar("SELECT current_user"));}
    @Test void javaFingerprintMatchesActualOrderedUtf8PostgresDefinition()throws Exception{String id=create();assertEquals(definition().fingerprint(),scalar("SELECT content_sha256 FROM public.mop_inspection_template_revision WHERE template_id='"+id+"'"));assertEquals("UNVERIFIED",scalar("SELECT approval_status FROM public.mop_inspection_template_revision WHERE template_id='"+id+"'"));assertEquals("2",scalar("SELECT count(*) FROM public.mop_inspection_template_item WHERE template_id='"+id+"'"));}
    @Test void fifthDeviceTypeIsDataAndUnicodeNullEmptyInstructionsRoundTrip()throws Exception{var items=List.of(new Item("a","分组","😀",null),new Item("b","分组","其他",""));var template=new InspectionTemplate("fifth_type","自定义",items);String id=head();complete(id,1,"CREATE",template);assertEquals("fifth_type",scalar("SELECT device_type FROM public.mop_inspection_template_revision WHERE template_id='"+id+"'"));assertEquals("true",scalar("SELECT (instruction IS NULL)::text FROM public.mop_inspection_template_item WHERE template_id='"+id+"' AND position=1"));assertEquals("",scalar("SELECT instruction FROM public.mop_inspection_template_item WHERE template_id='"+id+"' AND position=2"));}
    @Test void incompleteRevisionCannotPassDeferredCommitBoundary()throws Exception{rejected("23514",()->{String id=head();revision(id,1,"CREATE",definition(),definition().fingerprint(),2);item(id,1,1,definition().items().get(0));validate();});}
    @Test void missingCurrentRevisionAndDanglingFutureRevisionAreRejected()throws Exception{rejected("23503",()->{head();validate();});String id=create();rejected("23514",()->{revision(id,2,"UPDATE",definition(),definition().fingerprint(),2);for(int i=0;i<2;i++)item(id,2,i+1,definition().items().get(i));validate();});}
    @Test void duplicateKeysAndNoncontiguousPositionsFail()throws Exception{rejected("23505",()->{String id=head();revision(id,1,"CREATE",definition(),definition().fingerprint(),2);item(id,1,1,definition().items().get(0));item(id,1,2,definition().items().get(0));});rejected("23514",()->{String id=head();revision(id,1,"CREATE",definition(),definition().fingerprint(),2);item(id,1,1,definition().items().get(0));item(id,1,3,definition().items().get(1));validate();});}
    @Test void forgedFingerprintAndDeclaredItemCountFail()throws Exception{rejected("23514",()->{String id=head();revision(id,1,"CREATE",definition(),"0".repeat(64),2);for(int i=0;i<2;i++)item(id,1,i+1,definition().items().get(i));validate();});rejected("23514",()->{String id=head();revision(id,1,"CREATE",definition(),definition().fingerprint(),1);for(int i=0;i<2;i++)item(id,1,i+1,definition().items().get(i));validate();});}
    @Test void headVersionCannotStartLaterSkipOrMoveBackward()throws Exception{rejected("23514",()->sql("INSERT INTO public.mop_inspection_template(id,tenant_id,created_by,version) VALUES (gen_random_uuid(),'"+TENANT+"','"+actor+"',2)"));String id=create();for(int version:List.of(0,1,3))rejected("23514",()->sql("UPDATE public.mop_inspection_template SET version="+version+" WHERE id='"+id+"'"));}
    @Test void updateKeepsOriginalRevisionAndItemsByteEquivalent()throws Exception{String id=create();String old=scalar("SELECT string_agg(to_jsonb(i)::text,'|' ORDER BY position) FROM public.mop_inspection_template_item i WHERE template_id='"+id+"' AND version=1");complete(id,2,"UPDATE",new InspectionTemplate("firewall","新版本",List.of(new Item("custom","自定义","检查",null))));assertEquals("2",scalar("SELECT version FROM public.mop_inspection_template WHERE id='"+id+"'"));assertEquals(old,scalar("SELECT string_agg(to_jsonb(i)::text,'|' ORDER BY position) FROM public.mop_inspection_template_item i WHERE template_id='"+id+"' AND version=1"));}
    @Test void archivePreservesDefinitionAndRestoreIsExplicit()throws Exception{String id=create();complete(id,2,"ARCHIVE",definition());complete(id,3,"RESTORE",new InspectionTemplate("new_type","恢复时显式模板",List.of(new Item("a","组","项",null))));assertEquals("CREATE,ARCHIVE,RESTORE",scalar("SELECT string_agg(action,',' ORDER BY version) FROM public.mop_inspection_template_revision WHERE template_id='"+id+"'"));}
    @Test void archiveCannotAlterDefinitionAndUpdateCannotBypassRestore()throws Exception{String id=create();var changed=new InspectionTemplate("new_type","改动",definition().items());rejected("23514",()->complete(id,2,"ARCHIVE",changed));rejected("23514",()->complete(id,2,"RESTORE",definition()));complete(id,2,"ARCHIVE",definition());rejected("23514",()->complete(id,3,"UPDATE",definition()));}
    @Test void runtimeCannotRewriteHistoryScopeOrDelete()throws Exception{for(String query:List.of("UPDATE public.mop_inspection_template_revision SET name='changed' WHERE false","UPDATE public.mop_inspection_template_item SET label='changed' WHERE false","UPDATE public.mop_inspection_template SET tenant_id=gen_random_uuid() WHERE false","UPDATE public.mop_inspection_template SET created_by=gen_random_uuid() WHERE false","DELETE FROM public.mop_inspection_template WHERE false","DELETE FROM public.mop_inspection_template_revision WHERE false","DELETE FROM public.mop_inspection_template_item WHERE false","TRUNCATE public.mop_inspection_template"))rejected("42501",()->sql(query));}
    @Test void foreignTenantOrNonmemberActorCannotBind()throws Exception{rejected("23503",()->sql("INSERT INTO public.mop_inspection_template(id,tenant_id,created_by) VALUES(gen_random_uuid(),gen_random_uuid(),'"+actor+"')"));rejected("23503",()->sql("INSERT INTO public.mop_inspection_template(id,tenant_id,created_by) VALUES(gen_random_uuid(),'"+TENANT+"',gen_random_uuid())"));}
    @Test void inputConstraintsRejectUnapprovedStatusAndOutOfBoundsCount()throws Exception{String id=head();rejected("23514",()->revision(id,1,"CREATE",definition(),definition().fingerprint(),101));rejected("23514",()->sql("INSERT INTO public.mop_inspection_template_revision(tenant_id,template_id,version,recorded_by,action,device_type,name,item_count,content_sha256,approval_status) VALUES('"+TENANT+"','"+id+"',1,'"+actor+"','CREATE','router','合成',1,'"+"a".repeat(64)+"','APPROVED')"));}
}
