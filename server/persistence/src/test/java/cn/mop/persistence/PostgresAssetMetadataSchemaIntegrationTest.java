package cn.mop.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** V9 explicitly applied beforehand. Structural/ACL checks only; all synthetic transactions roll back. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAssetMetadataSchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static final ObjectMapper JSON=new ObjectMapper();static String actor,foreignActor;
    static Connection connect()throws SQLException{String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var c=DriverManager.getConnection(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));c.setAutoCommit(false);return c;}
    @BeforeAll static void prepare()throws SQLException{try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND to_regclass('public.mop_asset_manual_revision') IS NOT NULL")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"' AND active AND role_code='msp' ORDER BY user_id LIMIT 1")){assertTrue(r.next());actor=r.getString(1);}
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT b.user_id::text FROM public.mop_membership b WHERE b.tenant_id='"+OTHER+"' AND b.active AND NOT EXISTS(SELECT 1 FROM public.mop_membership a WHERE a.tenant_id='"+TENANT+"' AND a.user_id=b.user_id) ORDER BY b.user_id LIMIT 1")){assertTrue(r.next());foreignActor=r.getString(1);}
    }
    static String asset(Connection c)throws SQLException{String id=UUID.randomUUID().toString();try(var s=c.prepareStatement("INSERT INTO public.mop_asset(id,tenant_id,name,type,management_ip,site_id,source) VALUES (?::uuid,?::uuid,?,'router','192.0.2.77','site-a-main','manual')")){s.setString(1,id);s.setString(2,TENANT);s.setString(3,"SCHEMA-META-"+id);s.executeUpdate();}return id;}
    static ObjectNode before(String id,long version,boolean previous){var value=JSON.createObjectNode();value.putObject("asset").put("id",id).put("tenant_id",TENANT).put("version",version).put("management_ip","192.0.2.77").put("source","manual").put("lifecycle_status","DRAFT").put("status","unknown").putNull("sampled_at").put("availability","UNKNOWN").put("sync_state","PENDING_ONBOARDING");if(previous)value.putObject("metadata").put("name","previous synthetic");else value.putNull("metadata");return value;}
    static void revision(Connection c,String id,long version,Long previous,String actorId,String owner,String site,ObjectNode snapshot,String note,String verification,boolean monitoring,LocalDate purchase)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_asset_manual_revision(tenant_id,asset_id,asset_version,previous_metadata_version,actor_id,name,type,management_ip,site_id,owner_id,purchase_date,note,before_snapshot,verification_status,monitoring_applied) VALUES (?::uuid,?::uuid,?,?,?::uuid,'new synthetic name','router','192.0.2.77',?,?::uuid,?,?,?::jsonb,?,?)")){
            s.setString(1,TENANT);s.setString(2,id);s.setLong(3,version);if(previous==null)s.setNull(4,Types.BIGINT);else s.setLong(4,previous);s.setString(5,actorId);s.setString(6,site);s.setString(7,owner);s.setObject(8,purchase);s.setString(9,note);s.setString(10,snapshot.toString());s.setString(11,verification);s.setBoolean(12,monitoring);s.executeUpdate();
        }
    }
    static void valid(Connection c,String id)throws SQLException{revision(c,id,2,null,actor,null,"site-a-main",before(id,1,false),"synthetic note","SELF_REPORTED",false,null);}
    static void pointer(Connection c,String id,long version)throws SQLException{try(var s=c.prepareStatement("INSERT INTO public.mop_asset_manual_details(tenant_id,asset_id,revision_version) VALUES (?::uuid,?::uuid,?)")){s.setString(1,TENANT);s.setString(2,id);s.setLong(3,version);s.executeUpdate();}}
    @Test void optionalFactsRemainNullAndDraftOnlyFlagsCannotImplyMonitoringOrCmdbSuccess()throws SQLException{try(var c=connect()){String id=asset(c);valid(c,id);pointer(c,id,2);try(var s=c.prepareStatement("SELECT brand,owner_id,purchase_date,collection_method_note,verification_status,monitoring_applied,cmdb_applied,before_snapshot->'metadata' FROM public.mop_asset_manual_revision WHERE asset_id=?::uuid")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());for(int i=1;i<=4;i++)assertNull(r.getObject(i));assertEquals("SELF_REPORTED",r.getString(5));assertFalse(r.getBoolean(6));assertFalse(r.getBoolean(7));assertEquals("null",r.getString(8));}}c.rollback();}}
    @Test void foreignActorOwnerSiteAndMissingAssetFailCompositeReferences()throws SQLException{
        for(String variant:List.of("actor","owner","site","asset"))try(var c=connect()){String id=variant.equals("asset")?UUID.randomUUID().toString():asset(c);String who=variant.equals("actor")?foreignActor:actor,owner=variant.equals("owner")?foreignActor:null,site=variant.equals("site")?"site-b-main":"site-a-main";assertEquals("23503",assertThrows(SQLException.class,()->revision(c,id,2,null,who,owner,site,before(id,1,false),"note","SELF_REPORTED",false,null)).getSQLState());c.rollback();}
    }
    @Test void beforeSnapshotMustMatchTenantAssetPreviousVersionIpAndPristineManualState()throws SQLException{
        for(String key:List.of("id","tenant_id","version","management_ip","source","lifecycle_status","status","sampled_at","availability","sync_state"))try(var c=connect()){String id=asset(c);var snapshot=before(id,1,false);var base=(ObjectNode)snapshot.get("asset");if(key.equals("version"))base.put(key,5);else base.put(key,"different");assertEquals("23514",assertThrows(SQLException.class,()->revision(c,id,2,null,actor,null,"site-a-main",snapshot,"note","SELF_REPORTED",false,null)).getSQLState());c.rollback();}
    }
    @Test void verificationMonitoringAndRequiredNoteConstraintsRejectFalseFacts()throws SQLException{
        for(String variant:List.of("verified","monitoring","blank"))try(var c=connect()){String id=asset(c);assertEquals("23514",assertThrows(SQLException.class,()->revision(c,id,2,null,actor,null,"site-a-main",before(id,1,false),variant.equals("blank")?" ":"note",variant.equals("verified")?"VERIFIED":"SELF_REPORTED",variant.equals("monitoring"),null)).getSQLState());c.rollback();}
        try(var c=connect()){String id=asset(c);assertEquals("23502",assertThrows(SQLException.class,()->revision(c,id,2,null,actor,null,"site-a-main",before(id,1,false),null,"SELF_REPORTED",false,null)).getSQLState());c.rollback();}
    }
    @Test void strictDateRangeAndPreviousRevisionLinkAreEnforced()throws SQLException{
        try(var c=connect()){String id=asset(c);revision(c,id,2,null,actor,null,"site-a-main",before(id,1,false),"note","SELF_REPORTED",false,LocalDate.of(1,1,1));revision(c,id,3,2L,actor,actor,"site-a-main",before(id,2,true),"next note","SELF_REPORTED",false,LocalDate.of(9999,12,31));c.rollback();}
        for(LocalDate date:List.of(LocalDate.of(0,1,1),LocalDate.of(10000,1,1)))try(var c=connect()){String id=asset(c);assertEquals("23514",assertThrows(SQLException.class,()->revision(c,id,2,null,actor,null,"site-a-main",before(id,1,false),"note","SELF_REPORTED",false,date)).getSQLState());c.rollback();}
        try(var c=connect()){String id=asset(c);assertEquals("23503",assertThrows(SQLException.class,()->revision(c,id,4,3L,actor,null,"site-a-main",before(id,3,true),"note","SELF_REPORTED",false,null)).getSQLState());c.rollback();}
    }
    @Test void metadataAbsenceAndDuplicateVersionCannotBeMisrepresented()throws SQLException{
        try(var c=connect()){String id=asset(c);assertEquals("23514",assertThrows(SQLException.class,()->revision(c,id,2,null,actor,null,"site-a-main",before(id,1,true),"note","SELF_REPORTED",false,null)).getSQLState());c.rollback();}
        try(var c=connect()){String id=asset(c);valid(c,id);assertEquals("23505",assertThrows(SQLException.class,()->valid(c,id)).getSQLState());c.rollback();}
    }
    @Test void currentPointerMustReferenceAnExistingRevisionAtCommit()throws SQLException{try(var c=connect()){String id=asset(c);pointer(c,id,2);assertEquals("23503",assertThrows(SQLException.class,c::commit).getSQLState());c.rollback();}}
    @Test void runtimeOnlyGetsNamedMutableDraftColumnsAndNoHistoricalOrDeleteRights()throws SQLException{
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT has_table_privilege(current_user,'public.mop_asset','UPDATE,DELETE,TRUNCATE'),has_table_privilege(current_user,'public.mop_asset_manual_revision','UPDATE,DELETE,TRUNCATE'),has_table_privilege(current_user,'public.mop_asset_manual_details','UPDATE,DELETE,TRUNCATE'),has_column_privilege(current_user,'public.mop_asset','management_ip','UPDATE'),has_column_privilege(current_user,'public.mop_asset','source','UPDATE'),has_column_privilege(current_user,'public.mop_asset','status','UPDATE'),has_column_privilege(current_user,'public.mop_asset','name','UPDATE'),has_column_privilege(current_user,'public.mop_asset_manual_details','revision_version','UPDATE')")){r.next();for(int i=1;i<=6;i++)assertFalse(r.getBoolean(i));assertTrue(r.getBoolean(7));assertTrue(r.getBoolean(8));}
        try(var c=connect()){String id=asset(c);try(var s=c.prepareStatement("UPDATE public.mop_asset SET name=?,type='switch',site_id='site-a-main',version=2,updated_at=statement_timestamp(),updated_by=?::uuid WHERE id=?::uuid")){s.setString(1,"SCHEMA-META-UPDATED-"+id);s.setString(2,actor);s.setString(3,id);assertEquals(1,s.executeUpdate());}c.rollback();}
    }
}
