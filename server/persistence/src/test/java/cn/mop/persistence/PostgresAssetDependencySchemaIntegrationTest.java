package cn.mop.persistence;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real authorized test PostgreSQL, explicitly pre-applied V7. No test-side DDL or migration. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAssetDependencySchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    static final String OTHER="55000000-0000-4000-8000-000000000002";
    static String actor,parent,child,foreign;
    static Connection connect()throws SQLException{
        String url=System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        Connection c=DriverManager.getConnection(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        c.setAutoCommit(false);return c;
    }
    static String one(Connection c,String sql,String value)throws SQLException{
        try(var s=c.prepareStatement(sql)){s.setString(1,value);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}
    }
    @BeforeAll static void prerequisites()throws SQLException{
        try(Connection c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND to_regclass('public.mop_asset_dependency_graph') IS NOT NULL")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
        try(Connection c=connect()){
            actor=one(c,"SELECT user_id::text FROM public.mop_membership WHERE tenant_id::text=? AND active ORDER BY user_id LIMIT 1",TENANT);
            try(var s=c.prepareStatement("SELECT id::text FROM public.mop_asset WHERE tenant_id::text=? ORDER BY id LIMIT 2")){s.setString(1,TENANT);try(var r=s.executeQuery()){assertTrue(r.next());parent=r.getString(1);assertTrue(r.next());child=r.getString(1);}}
            foreign=one(c,"SELECT id::text FROM public.mop_asset WHERE tenant_id::text=? ORDER BY id LIMIT 1",OTHER);
        }
    }
    static long prepareGraph(Connection c)throws SQLException{
        long previous=0;
        try(var s=c.prepareStatement("SELECT version FROM public.mop_asset_dependency_graph WHERE tenant_id::text=? FOR UPDATE")){s.setString(1,TENANT);try(var r=s.executeQuery()){if(r.next())previous=r.getLong(1);}}
        long version=previous+1;
        String sql=previous==0?"INSERT INTO public.mop_asset_dependency_graph(version,tenant_id) VALUES (?,?::uuid)":"UPDATE public.mop_asset_dependency_graph SET version=? WHERE tenant_id::text=?";
        try(var s=c.prepareStatement(sql)){s.setLong(1,version);s.setString(2,TENANT);assertEquals(1,s.executeUpdate());}
        return version;
    }
    static long revision(Connection c)throws SQLException{
        long version=prepareGraph(c);
        try(var s=c.prepareStatement("INSERT INTO public.mop_asset_dependency_revision(tenant_id,version,created_by) VALUES (?::uuid,?,?::uuid)")){s.setString(1,TENANT);s.setLong(2,version);s.setString(3,actor);s.executeUpdate();}return version;
    }
    static void edge(Connection c,long version,String parentId,String childId)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO public.mop_asset_dependency_edge(tenant_id,revision_version,parent_id,child_id) VALUES (?::uuid,?,?::uuid,?::uuid)")){
            s.setString(1,TENANT);s.setLong(2,version);s.setString(3,parentId);s.setString(4,childId);s.executeUpdate();
        }
    }
    @Test void explicitSchemaStoresOnlyDraftFactsAndNullProposalMetadata()throws SQLException{
        try(Connection c=connect()){
            long version=revision(c);edge(c,version,parent,child);
            try(var s=c.prepareStatement("SELECT r.lifecycle_status,r.decision_status,r.monitoring_applied,e.proposed_dependency_type,e.proposed_suppression_scope,e.proposed_enabled FROM public.mop_asset_dependency_revision r JOIN public.mop_asset_dependency_edge e ON e.tenant_id=r.tenant_id AND e.revision_version=r.version WHERE r.tenant_id::text=? AND r.version=?")){
                s.setString(1,TENANT);s.setLong(2,version);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals("DRAFT",r.getString(1));assertEquals("pending",r.getString(2));assertFalse(r.getBoolean(3));for(int i=4;i<=6;i++)assertNull(r.getObject(i));assertFalse(r.next());}
            }
            c.rollback();
        }
    }
    @Test void runtimeHasNoHistoricalUpdatesDeletesOrTableWideGraphUpdate()throws SQLException{
        for(String sql:List.of("UPDATE public.mop_asset_dependency_revision SET lifecycle_status='DRAFT' WHERE false","UPDATE public.mop_asset_dependency_edge SET proposed_enabled=false WHERE false","DELETE FROM public.mop_asset_dependency_graph WHERE false","DELETE FROM public.mop_asset_dependency_revision WHERE false","DELETE FROM public.mop_asset_dependency_edge WHERE false","UPDATE public.mop_asset_dependency_graph SET tenant_id=tenant_id WHERE false","TRUNCATE public.mop_asset_dependency_edge")){
            try(Connection c=connect();var s=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->s.execute(sql)).getSQLState());c.rollback();}
        }
    }
    @Test void publishedOrAppliedFactsCannotBeInserted()throws SQLException{
        for(String badColumn:List.of("lifecycle_status","decision_status","monitoring_applied")){
            try(Connection c=connect()){
                long version=prepareGraph(c);
                String value=badColumn.equals("monitoring_applied")?"true":"'APPROVED'";
                try(var s=c.prepareStatement("INSERT INTO public.mop_asset_dependency_revision(tenant_id,version,created_by,"+badColumn+") VALUES (?::uuid,?,?::uuid,"+value+")")){
                    s.setString(1,TENANT);s.setLong(2,version);s.setString(3,actor);assertEquals("23514",assertThrows(SQLException.class,s::executeUpdate).getSQLState());
                }c.rollback();
            }
        }
    }
    @Test void foreignMissingAndSelfReferencesAreRejectedByDatabaseConstraints()throws SQLException{
        for(String other:List.of(foreign,UUID.randomUUID().toString(),parent)){
            try(Connection c=connect()){
                long version=revision(c);String expected=other.equals(parent)?"23514":"23503";
                assertEquals(expected,assertThrows(SQLException.class,()->edge(c,version,parent,other)).getSQLState());c.rollback();
            }
        }
    }
    @Test void graphCannotCommitAnUnbackedLatestRevision()throws SQLException{
        try(Connection c=connect()){prepareGraph(c);assertEquals("23503",assertThrows(SQLException.class,c::commit).getSQLState());c.rollback();}
    }
    @Test void duplicateEdgesCannotOverwriteProposalHistory()throws SQLException{
        try(Connection c=connect()){
            long version=revision(c);edge(c,version,parent,child);assertEquals("23505",assertThrows(SQLException.class,()->edge(c,version,parent,child)).getSQLState());c.rollback();
        }
    }
}
