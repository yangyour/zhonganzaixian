package cn.mop.persistence;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real explicitly pre-applied V8 constraints; all test data transactions roll back. No migration here. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresWorklogSchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static String ticket,owner,otherActor,otherTenantOwner;
    static Connection connect()throws SQLException{
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        var c=DriverManager.getConnection(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));c.setAutoCommit(false);return c;
    }
    static String one(Connection c,String sql,String value)throws SQLException{try(var s=c.prepareStatement(sql)){s.setString(1,value);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
    @BeforeAll static void prerequisites()throws SQLException{
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND to_regclass('public.mop_worklog_revision') IS NOT NULL")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
        try(var c=connect()){
            ticket=one(c,"SELECT id::text FROM public.mop_ticket WHERE tenant_id::text=? ORDER BY id LIMIT 1",TENANT);
            owner=one(c,"SELECT user_id::text FROM public.mop_membership WHERE tenant_id::text=? AND active AND role_code='msp' ORDER BY user_id LIMIT 1",TENANT);
            otherActor=one(c,"SELECT user_id::text FROM public.mop_membership WHERE tenant_id::text=? AND active AND role_code='viewer' ORDER BY user_id LIMIT 1",TENANT);
            otherTenantOwner=one(c,"SELECT user_id::text FROM public.mop_membership WHERE tenant_id::text=? AND active ORDER BY user_id LIMIT 1",OTHER);
        }
    }
    static String header(Connection c,String tenant,String ticketId,String actor)throws SQLException{
        String id=UUID.randomUUID().toString();try(var s=c.prepareStatement("INSERT INTO public.mop_worklog(id,tenant_id,ticket_id,owner_id,version) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,1)")){s.setString(1,id);s.setString(2,tenant);s.setString(3,ticketId);s.setString(4,actor);s.executeUpdate();}return id;
    }
    static void revision(Connection c,String id,String actor,String action,String kind,String start,String end,String source)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_worklog_revision(tenant_id,worklog_id,version,actor_id,action,kind,started_at,ended_at,record_source) VALUES (?::uuid,?::uuid,1,?::uuid,?,?,?,?,?)")){
            s.setString(1,TENANT);s.setString(2,id);s.setString(3,actor);s.setString(4,action);s.setString(5,kind);s.setObject(6,OffsetDateTime.parse(start));s.setObject(7,OffsetDateTime.parse(end));s.setString(8,source);s.executeUpdate();
        }
    }
    static void valid(Connection c,String id)throws SQLException{revision(c,id,owner,"CREATE","REMOTE","2026-10-02T00:00:00Z","2026-10-04T01:00:00Z","MANUAL_INTERVAL");}
    @Test void manualSourceAndLongExplicitIntervalAreStoredWithoutCostOrDefaultColumns()throws SQLException{
        try(var c=connect()){
            String id=header(c,TENANT,ticket,owner);valid(c,id);
            try(var s=c.prepareStatement("SELECT kind,started_at,ended_at,note,record_source FROM public.mop_worklog_revision WHERE tenant_id::text=? AND worklog_id::text=?")){s.setString(1,TENANT);s.setString(2,id);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals("REMOTE",r.getString(1));assertEquals(49*3600000L,java.time.Duration.between(r.getObject(2,OffsetDateTime.class),r.getObject(3,OffsetDateTime.class)).toMillis());assertNull(r.getString(4));assertEquals("MANUAL_INTERVAL",r.getString(5));}}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('mop_worklog','mop_worklog_revision') AND column_name IN ('default_minutes','cost_amount','billing_minutes')")){r.next();assertEquals(0,r.getLong(1));}c.rollback();
        }
    }
    @Test void invalidRangeKindSourceAndInitialActionFailConstraints()throws SQLException{
        for(String[] values:List.of(new String[]{"CREATE","REMOTE","2026-10-02T00:00:00Z","2026-10-01T00:00:00Z","MANUAL_INTERVAL"},new String[]{"CREATE","UNKNOWN","2026-10-02T00:00:00Z","2026-10-02T01:00:00Z","MANUAL_INTERVAL"},new String[]{"CREATE","REMOTE","2026-10-02T00:00:00Z","2026-10-02T01:00:00Z","DEFAULT"},new String[]{"UPDATE","REMOTE","2026-10-02T00:00:00Z","2026-10-02T01:00:00Z","MANUAL_INTERVAL"},new String[]{"CREATE","REMOTE","2026-10-02T00:00:00Z","+10000-01-01T00:00:00Z","MANUAL_INTERVAL"})){
            try(var c=connect()){String id=header(c,TENANT,ticket,owner);assertEquals("23514",assertThrows(SQLException.class,()->revision(c,id,owner,values[0],values[1],values[2],values[3],values[4])).getSQLState());c.rollback();}
        }
    }
    @Test void revisionActorMustMatchImmutableEntryOwner()throws SQLException{
        try(var c=connect()){String id=header(c,TENANT,ticket,owner);assertEquals("23503",assertThrows(SQLException.class,()->revision(c,id,otherActor,"CREATE","REMOTE","2026-10-02T00:00:00Z","2026-10-02T01:00:00Z","MANUAL_INTERVAL")).getSQLState());c.rollback();}
    }
    @Test void tenantTicketAndOwnerForeignKeysCannotBeMixed()throws SQLException{
        try(var c=connect()){assertEquals("23503",assertThrows(SQLException.class,()->header(c,OTHER,ticket,otherTenantOwner)).getSQLState());c.rollback();}
        try(var c=connect()){assertEquals("23503",assertThrows(SQLException.class,()->header(c,TENANT,UUID.randomUUID().toString(),owner)).getSQLState());c.rollback();}
        try(var c=connect()){assertEquals("23503",assertThrows(SQLException.class,()->header(c,TENANT,ticket,UUID.randomUUID().toString())).getSQLState());c.rollback();}
    }
    @Test void currentRevisionPointerMustExistAtCommit()throws SQLException{try(var c=connect()){header(c,TENANT,ticket,owner);assertEquals("23503",assertThrows(SQLException.class,c::commit).getSQLState());c.rollback();}}
    @Test void runtimeCannotChangeOwnersParentLinksOrHistoricalFactsOrDelete()throws SQLException{
        for(String sql:List.of("UPDATE public.mop_worklog SET owner_id=owner_id WHERE false","UPDATE public.mop_worklog SET ticket_id=ticket_id WHERE false","UPDATE public.mop_worklog SET created_at=created_at WHERE false","UPDATE public.mop_worklog_revision SET note=note WHERE false","DELETE FROM public.mop_worklog WHERE false","DELETE FROM public.mop_worklog_revision WHERE false","TRUNCATE public.mop_worklog_revision")){
            try(var c=connect();var s=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->s.execute(sql)).getSQLState());c.rollback();}
        }
    }
}
