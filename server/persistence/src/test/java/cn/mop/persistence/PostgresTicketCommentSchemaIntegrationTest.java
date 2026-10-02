package cn.mop.persistence;

import cn.mop.domain.ticketcomment.TicketComment;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real runtime-role V11 constraints and ACL only. Every attempted comment is rolled back, not a workflow proof. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresTicketCommentSchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;Connection connection;String ticket,actor,foreignTicket,foreignActor;long parentVersion;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);}
    @BeforeEach void begin()throws SQLException{
        connection=pool.getConnection();connection.setAutoCommit(false);ticket=scalar("SELECT id::text FROM public.mop_ticket WHERE tenant_id='"+TENANT+"'::uuid ORDER BY created_at,id LIMIT 1");foreignTicket=scalar("SELECT id::text FROM public.mop_ticket WHERE tenant_id='"+OTHER+"'::uuid ORDER BY created_at,id LIMIT 1");actor=scalar("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"'::uuid AND role_code='msp' AND active LIMIT 1");foreignActor=scalar("SELECT b.user_id::text FROM public.mop_membership b WHERE b.tenant_id='"+OTHER+"'::uuid AND NOT EXISTS(SELECT 1 FROM public.mop_membership a WHERE a.tenant_id='"+TENANT+"'::uuid AND a.user_id=b.user_id) LIMIT 1");parentVersion=Long.parseLong(scalar("SELECT version::text FROM public.mop_ticket WHERE id='"+ticket+"'::uuid"));
    }
    @AfterEach void rollback()throws SQLException{if(connection!=null){connection.rollback();connection.close();}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    String scalar(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    String insert(String parent,String author,String text,String hash,long version)throws SQLException{
        return insert(parent,author,text,hash,version,"ENGINEER");
    }
    String insert(String parent,String author,String text,String hash,long version,String kind)throws SQLException{
        String id=UUID.randomUUID().toString();try(var s=connection.prepareStatement("INSERT INTO public.mop_ticket_comment(id,tenant_id,ticket_id,actor_id,observed_ticket_version,comment_text,comment_sha256,author_kind) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?)")){s.setString(1,id);s.setString(2,TENANT);s.setString(3,parent);s.setString(4,author);s.setLong(5,version);s.setString(6,text);s.setString(7,hash);s.setString(8,kind);s.executeUpdate();}return id;
    }
    @FunctionalInterface interface SqlAction{void run()throws SQLException;}
    void rejected(String sqlState,SqlAction action)throws SQLException{Savepoint point=connection.setSavepoint();SQLException failure=assertThrows(SQLException.class,action::run);assertEquals(sqlState,failure.getSQLState());connection.rollback(point);connection.releaseSavepoint(point);}
    @Test void rawMultilineUnicodeAndItsExactHashRoundTripWithoutChangingParent()throws SQLException{
        var note=new TicketComment("  合成客户可见评论😀\n第二行\t ");String before=scalar("SELECT md5(to_jsonb(t)::text) FROM public.mop_ticket t WHERE id='"+ticket+"'::uuid");String id=insert(ticket,actor,note.text(),note.sha256(),parentVersion);
        try(var s=connection.prepareStatement("SELECT * FROM public.mop_ticket_comment WHERE id=?::uuid")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(note.text(),r.getString("comment_text"));assertEquals(note.sha256(),r.getString("comment_sha256"));assertEquals(parentVersion,r.getLong("observed_ticket_version"));assertEquals("REPORTER_AND_INTERNAL",r.getString("visibility"));assertEquals("MANUAL_TICKET_COMMENT",r.getString("record_source"));assertEquals("SELF_REPORTED",r.getString("verification_status"));assertFalse(r.getBoolean("ticket_state_changed"));assertFalse(r.getBoolean("sla_changed"));assertFalse(r.getBoolean("itsm_applied"));assertEquals("NOT_REQUESTED",r.getString("itsm_sync_status"));assertEquals("ENGINEER",r.getString("author_kind"));assertNotNull(r.getObject("created_at",OffsetDateTime.class));}}
        assertEquals(before,scalar("SELECT md5(to_jsonb(t)::text) FROM public.mop_ticket t WHERE id='"+ticket+"'::uuid"));
    }
    @Test void compositeTenantForeignKeysRejectOtherTicketAndActor()throws SQLException{var note=new TicketComment("合成范围验证");rejected("23503",()->insert(foreignTicket,actor,note.text(),note.sha256(),1));rejected("23503",()->insert(ticket,foreignActor,note.text(),note.sha256(),parentVersion));}
    @Test void hashAndParentVersionCannotBeForged()throws SQLException{var note=new TicketComment("合成hash验证");rejected("23514",()->insert(ticket,actor,note.text(),"a".repeat(64),parentVersion));rejected("23514",()->insert(ticket,actor,note.text(),note.sha256(),0));}
    @Test void databaseEnforcesNonBlankControlAndCodePointLimits()throws Exception{
        for(String text:List.of(""," \ufeff\u00a0\n\t","x\u001b","x\u0085","😀".repeat(2001))){String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));rejected("23514",()->insert(ticket,actor,text,hash,parentVersion));}var longest=new TicketComment("😀".repeat(2000));assertNotNull(insert(ticket,actor,longest.text(),longest.sha256(),parentVersion));
    }
    @Test void runtimeCannotUpdateDeleteOrTruncateHistoricalNotes()throws SQLException{
        for(String sql:List.of("UPDATE public.mop_ticket_comment SET comment_text='changed' WHERE false","DELETE FROM public.mop_ticket_comment WHERE false","TRUNCATE public.mop_ticket_comment"))rejected("42501",()->{try(var s=connection.createStatement()){s.executeUpdate(sql);}});
        assertEquals("t",scalar("SELECT has_table_privilege(current_user,'public.mop_ticket_comment','SELECT') AND has_table_privilege(current_user,'public.mop_ticket_comment','INSERT') AND NOT has_table_privilege(current_user,'public.mop_ticket_comment','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public.mop_ticket_comment','UPDATE')"));
        assertEquals("0",scalar("SELECT count(*)::text FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_ticket_comment'::regclass AND a.grantee=0"));
    }
    @Test void wrongAudienceAndClaimedWorkflowOrProviderChangesAreRejected()throws SQLException{
        var note=new TicketComment("合成标签边界");for(String assignment:List.of("visibility='TENANT_WIDE'","visibility='INTERNAL'","record_source='EXTERNAL_ITSM'","verification_status='VERIFIED'","ticket_state_changed=true","sla_changed=true","itsm_applied=true","itsm_sync_status='SYNCED'")){
            String[] parts=assignment.split("=",2);rejected("23514",()->{try(var s=connection.prepareStatement("INSERT INTO public.mop_ticket_comment(id,tenant_id,ticket_id,actor_id,observed_ticket_version,comment_text,comment_sha256,author_kind,"+parts[0]+") VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,'ENGINEER',"+parts[1]+")")){s.setString(1,UUID.randomUUID().toString());s.setString(2,TENANT);s.setString(3,ticket);s.setString(4,actor);s.setLong(5,parentVersion);s.setString(6,note.text());s.setString(7,note.sha256());s.executeUpdate();}});
        }
    }
    @Test void explicitAuthorKindsRoundTripAndUnknownKindsAreRejected()throws SQLException{
        var comment=new TicketComment("合成作者类别");String reporter=scalar("SELECT reporter_id::text FROM public.mop_ticket WHERE id='"+ticket+"'::uuid");
        for(String kind:List.of("REPORTER","ENGINEER")){String id=insert(ticket,kind.equals("REPORTER")?reporter:actor,comment.text(),comment.sha256(),parentVersion,kind);assertEquals(kind,scalar("SELECT author_kind FROM public.mop_ticket_comment WHERE id='"+id+"'::uuid"));}
        for(String kind:List.of("ADMIN","INTERNAL","viewer",""))rejected("23514",()->insert(ticket,actor,comment.text(),comment.sha256(),parentVersion,kind));
        rejected("23502",()->insert(ticket,actor,comment.text(),comment.sha256(),parentVersion,null));
    }

}
