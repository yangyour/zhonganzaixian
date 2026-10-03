package cn.mop.persistence;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real V12/runtime-role structure checks. Every draft is rolled back; no customer calendar is activated. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresWorkingCalendarSchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static final String START="2026-10-03T00:00:00Z",END="2026-10-04T00:00:00Z",WINDOW="{\"start\":\"2026-10-03T01:00:00Z\",\"end\":\"2026-10-03T02:00:00Z\"}";
    static HikariDataSource pool;Connection c;String id,actor,foreignActor;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);}
    @BeforeEach void begin()throws SQLException{c=pool.getConnection();c.setAutoCommit(false);id=UUID.randomUUID().toString();actor=scalar("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"'::uuid AND role_code='padmin' AND active LIMIT 1");foreignActor=scalar("SELECT b.user_id::text FROM public.mop_membership b WHERE b.tenant_id='"+OTHER+"'::uuid AND NOT EXISTS(SELECT 1 FROM public.mop_membership a WHERE a.tenant_id='"+TENANT+"'::uuid AND a.user_id=b.user_id) LIMIT 1");try(var s=c.prepareStatement("INSERT INTO public.mop_working_calendar_draft(id,tenant_id,version) VALUES (?::uuid,?::uuid,1)")){s.setString(1,id);s.setString(2,TENANT);s.executeUpdate();}}
    @AfterEach void rollback()throws SQLException{if(c!=null){c.rollback();c.close();}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    String scalar(String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    void execute(String sql)throws SQLException{try(var s=c.createStatement()){s.execute(sql);}}
    @FunctionalInterface interface SqlAction{void run()throws SQLException;}
    void rejected(String code,SqlAction action)throws SQLException{Savepoint point=c.setSavepoint();SQLException error=assertThrows(SQLException.class,action::run);assertEquals(code,error.getSQLState());c.rollback(point);c.releaseSavepoint(point);}
    boolean valid(String windows)throws SQLException{try(var s=c.prepareStatement("SELECT public.mop_calendar_windows_valid(?::jsonb,?::timestamptz,?::timestamptz)")){s.setString(1,windows);s.setString(2,START);s.setString(3,END);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}}
    void revision(long version,Long previous,String windows,String name,String note)throws SQLException{revision(TENANT,actor,version,previous,windows,name,note,true,null);}
    void revision(String tenant,String author,long version,Long previous,String windows,String name,String note,Boolean complete,String extra)throws SQLException{
        String column="",value="";if(extra!=null){String[] parts=extra.split("=",2);column=","+parts[0];value=","+parts[1];}
        try(var s=c.prepareStatement("INSERT INTO public.mop_working_calendar_revision(tenant_id,calendar_id,version,previous_version,actor_id,name,coverage_start,coverage_end,working_windows,coverage_complete,note"+column+") VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?::timestamptz,?::timestamptz,?::jsonb,?,?"+value+")")){
            s.setString(1,tenant);s.setString(2,id);s.setLong(3,version);if(previous==null)s.setNull(4,Types.BIGINT);else s.setLong(4,previous);s.setString(5,author);s.setString(6,name);s.setString(7,START);s.setString(8,END);s.setString(9,windows);if(complete==null)s.setNull(10,Types.BOOLEAN);else s.setBoolean(10,complete);s.setString(11,note);s.executeUpdate();
        }
    }
    @Test void immutableDraftFactsRoundTripWithoutApprovingCalendarOrChangingRegistry()throws SQLException{
        String keys="SELECT count(*)::text FROM public.mop_configuration WHERE key IN ('sla.tenant_work_calendar','sla.basic_standard_p3_next_day') AND (registry_json->'value')='null'::jsonb";assertEquals("2",scalar(keys));revision(1,null,"["+WINDOW+"]","合成日历😀","  原始说明\n第二行\t ");execute("SET CONSTRAINTS ALL IMMEDIATE");
        assertEquals("DRAFT|UNVERIFIED|false|Asia/Shanghai|MANUAL_CALENDAR_DRAFT",scalar("SELECT lifecycle_status||'|'||customer_confirmation||'|'||effective::text||'|'||timezone||'|'||record_source FROM public.mop_working_calendar_revision WHERE calendar_id='"+id+"'::uuid"));assertEquals("  原始说明\n第二行\t ",scalar("SELECT note FROM public.mop_working_calendar_revision WHERE calendar_id='"+id+"'::uuid"));assertEquals("2",scalar(keys));
    }
    @Test void successiveRevisionAndCurrentPointerKeepOldWindowPayloadImmutable()throws SQLException{
        revision(1,null,"["+WINDOW+"]","合成v1",null);String before=scalar("SELECT working_windows::text FROM public.mop_working_calendar_revision WHERE calendar_id='"+id+"'::uuid AND version=1");revision(2,1L,"[]","合成v2",null);execute("UPDATE public.mop_working_calendar_draft SET version=2,updated_at=statement_timestamp() WHERE id='"+id+"'::uuid");execute("SET CONSTRAINTS ALL IMMEDIATE");assertEquals("2",scalar("SELECT version::text FROM public.mop_working_calendar_draft WHERE id='"+id+"'::uuid"));assertEquals(before,scalar("SELECT working_windows::text FROM public.mop_working_calendar_revision WHERE calendar_id='"+id+"'::uuid AND version=1"));
    }
    @Test void aHeaderCannotCommitWithoutItsExactRevision()throws SQLException{rejected("23503",()->execute("SET CONSTRAINTS ALL IMMEDIATE"));}
    @Test void compositeTenantReferencesRejectForeignParentAndActor()throws SQLException{rejected("23503",()->revision(OTHER,foreignActor,1,null,"[]","合成",null,true,null));rejected("23503",()->revision(TENANT,foreignActor,1,null,"[]","合成",null,true,null));}
    @Test void previousRevisionCannotBeNullSkippedOrFromMissingHistory()throws SQLException{revision(1,null,"[]","合成",null);rejected("23514",()->revision(2,null,"[]","合成",null));rejected("23514",()->revision(3,1L,"[]","合成",null));rejected("23503",()->revision(3,2L,"[]","合成",null));}
    @Test void malformedUnknownAndOutOfRangeWindowsFailClosed()throws SQLException{
        for(String json:List.of("null","{}","[null]","[1]","[{}]","[{\"start\":null,\"end\":\"2026-10-03T02:00:00Z\"}]","[{\"start\":\"2026-10-03T01:00:00Z\",\"end\":\"2026-10-03T02:00:00Z\",\"extra\":true}]","["+WINDOW.replace("2026-10-03T01:00:00Z","2026-10-02T23:00:00Z")+"]","["+WINDOW.replace("01:00:00Z","03:00:00Z")+"]","["+WINDOW.replace("01:00:00Z","01:00:00.0001Z")+"]","["+WINDOW.replace("01:00:00Z","01:00:60Z")+"]","["+WINDOW.replace("2026-10-03T01:00:00Z","2026-02-30T01:00:00Z")+"]","["+WINDOW.replace("01:00:00Z","01:00:00+00:00")+"]")){assertFalse(valid(json),json);rejected("23514",()->revision(1,null,json,"合成",null));}
        rejected("23502",()->revision(1,null,null,"合成",null));
    }
    @Test void explicitEmptyOverlappingAndDuplicateWindowsAreNotInventedDefaults()throws SQLException{
        assertTrue(valid("[]"));String raw="["+WINDOW+","+WINDOW+","+WINDOW.replace("01:00:00Z","00:30:00Z")+"]";assertTrue(valid(raw));revision(TENANT,actor,1,null,raw,"合成",null,false,null);assertEquals("3",scalar("SELECT jsonb_array_length(working_windows)::text FROM public.mop_working_calendar_revision WHERE calendar_id='"+id+"'::uuid"));assertEquals("false",scalar("SELECT coverage_complete::text FROM public.mop_working_calendar_revision WHERE calendar_id='"+id+"'::uuid"));assertTrue(valid("["+String.join(",",Collections.nCopies(1000,WINDOW))+"]"));assertFalse(valid("["+String.join(",",Collections.nCopies(1001,WINDOW))+"]"));
    }
    @Test void unicodeTextAndExplicitCompletenessHaveDatabaseBounds()throws SQLException{
        for(String name:List.of("","\ufeff\u00a0","name\n"))rejected("23514",()->revision(1,null,"[]",name,null));rejected("22001",()->revision(1,null,"[]","😀".repeat(101),null));for(String note:List.of("","\ufeff","x\u001b","😀".repeat(2001)))rejected("23514",()->revision(1,null,"[]","合成",note));rejected("23502",()->revision(TENANT,actor,1,null,"[]","合成",null,null,null));revision(1,null,"[]","😀".repeat(100),"😀".repeat(2000));
    }
    @Test void runtimeCannotRewriteDeleteOrPromoteHistoricalFacts()throws SQLException{
        for(String sql:List.of("UPDATE public.mop_working_calendar_revision SET name='changed' WHERE false","DELETE FROM public.mop_working_calendar_revision WHERE false","TRUNCATE public.mop_working_calendar_revision","DELETE FROM public.mop_working_calendar_draft WHERE false","UPDATE public.mop_working_calendar_draft SET tenant_id='"+OTHER+"'::uuid WHERE false","UPDATE public.mop_working_calendar_draft SET created_at=statement_timestamp() WHERE false"))rejected("42501",()->execute(sql));
        for(String extra:List.of("effective=true","lifecycle_status='ACTIVE'","customer_confirmation='CONFIRMED'","timezone='UTC'","record_source='CUSTOMER_PUBLISHED'"))rejected("23514",()->revision(TENANT,actor,1,null,"[]","合成",null,true,extra));
    }
    @Test void windowCheckerIsInputOnlyNotSecurityDefinerAndPublicCannotExecute()throws SQLException{
        assertEquals("t",scalar("SELECT NOT prosecdef AND provolatile='i' AND proparallel='s' AND proconfig @> ARRAY['search_path=pg_catalog']::text[] AND has_function_privilege(current_user,oid,'EXECUTE') FROM pg_proc WHERE oid='public.mop_calendar_windows_valid(jsonb,timestamp with time zone,timestamp with time zone)'::regprocedure"));assertEquals("0",scalar("SELECT count(*)::text FROM pg_proc p CROSS JOIN LATERAL aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE p.oid='public.mop_calendar_windows_valid(jsonb,timestamp with time zone,timestamp with time zone)'::regprocedure AND a.grantee=0"));
        assertEquals("0",scalar("SELECT count(*)::text FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid IN ('public.mop_working_calendar_draft'::regclass,'public.mop_working_calendar_revision'::regclass) AND a.grantee=0"));
    }
}
