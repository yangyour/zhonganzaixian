package cn.mop.persistence;
import cn.mop.domain.manualincident.ManualRecoveryConfirmation;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** V14 runtime-role schema only. Synthetic parents and all confirmation attempts roll back after every test. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresManualRecoverySchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;Connection connection;String parent,foreignParent,actor,foreignActor;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);}
    @BeforeEach void begin()throws SQLException{
        connection=pool.getConnection();connection.setAutoCommit(false);actor=scalar("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"'::uuid AND role_code='msp' AND active LIMIT 1");foreignActor=scalar("SELECT b.user_id::text FROM public.mop_membership b WHERE b.tenant_id='"+OTHER+"'::uuid AND NOT EXISTS(SELECT 1 FROM public.mop_membership a WHERE a.tenant_id='"+TENANT+"'::uuid AND a.user_id=b.user_id) LIMIT 1");parent=parent(TENANT,actor);foreignParent=parent(OTHER,foreignActor);
    }
    @AfterEach void rollback()throws SQLException{if(connection!=null){connection.rollback();connection.close();}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    String scalar(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    String parent(String tenant,String author)throws SQLException{String id=UUID.randomUUID().toString();try(var s=connection.prepareStatement("INSERT INTO public.mop_manual_incident_registration(id,tenant_id,asset_id,actor_id,observed_asset_version,asset_name_at_registration,title,severity) SELECT ?::uuid,tenant_id,id,?::uuid,version,name,'合成人工恢复结构测试父登记','info' FROM public.mop_asset WHERE tenant_id=?::uuid ORDER BY id LIMIT 1")){s.setString(1,id);s.setString(2,author);s.setString(3,tenant);assertEquals(1,s.executeUpdate());}return id;}
    String insert(String incident,String author,String note,String hash,String extra)throws SQLException{
        String id=UUID.randomUUID().toString();String[] parts=extra==null?null:extra.split("=",2);
        try(var s=connection.prepareStatement("INSERT INTO public.mop_manual_recovery_confirmation(id,tenant_id,incident_id,actor_id,note,note_sha256"+(parts==null?"":","+parts[0])+") VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?"+(parts==null?"":","+parts[1])+")")){s.setString(1,id);s.setString(2,TENANT);s.setString(3,incident);s.setString(4,author);s.setString(5,note);s.setString(6,hash);s.executeUpdate();}return id;
    }
    String insert(String note,String extra)throws SQLException{var value=new ManualRecoveryConfirmation(note,null);return insert(parent,actor,note,value.noteSha256(),extra);}
    @FunctionalInterface interface SqlAction{void run()throws SQLException;}
    void rejected(String state,SqlAction action)throws SQLException{Savepoint point=connection.setSavepoint();SQLException failure=assertThrows(SQLException.class,action::run);assertEquals(state,failure.getSQLState());connection.rollback(point);connection.releaseSavepoint(point);}
    @Test void originalNoteHashAndUnknownTimePersistWithoutChangingOriginalRegistration()throws SQLException{
        String note="  人工确认😀\r\n原始说明\t ",before=scalar("SELECT md5(to_jsonb(i)::text) FROM public.mop_manual_incident_registration i WHERE id='"+parent+"'::uuid"),id=insert(note,null);
        try(var s=connection.prepareStatement("SELECT * FROM public.mop_manual_recovery_confirmation WHERE id=?::uuid")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(note,r.getString("note"));assertEquals(new ManualRecoveryConfirmation(note,null).noteSha256(),r.getString("note_sha256"));assertEquals(1,r.getLong("version"));assertEquals("HUMAN_CONFIRMED",r.getString("manual_recovery_status"));assertEquals("SELF_REPORTED",r.getString("verification_status"));assertEquals("MANUAL_RECOVERY_CONFIRMATION",r.getString("record_source"));for(String column:List.of("reported_recovered_at","monitor_state","disposition_state"))assertNull(r.getObject(column));for(String column:List.of("monitoring_recovery_applicable","ticket_state_changed","sla_changed"))assertFalse(r.getBoolean(column));assertNotNull(r.getObject("recorded_at",OffsetDateTime.class));}}
        assertEquals(before,scalar("SELECT md5(to_jsonb(i)::text) FROM public.mop_manual_incident_registration i WHERE id='"+parent+"'::uuid"));assertEquals("NOT_CONFIRMED",scalar("SELECT manual_recovery_status FROM public.mop_manual_incident_registration WHERE id='"+parent+"'::uuid"));
    }
    @Test void compositeTenantForeignKeysHideForeignParentAndActorAtStorageBoundary()throws SQLException{var value=new ManualRecoveryConfirmation("scope",null);rejected("23503",()->insert(foreignParent,actor,value.note(),value.noteSha256(),null));rejected("23503",()->insert(parent,foreignActor,value.note(),value.noteSha256(),null));}
    @Test void exactlyOneImmutableVersionPerRegistration()throws SQLException{rejected("23514",()->insert("invalid version","version=0"));rejected("23514",()->insert("invalid version","version=2"));insert("first",null);rejected("23505",()->insert("different second assertion",null));}
    @Test void hashNonBlankControlsAndUnicodeBoundsCannotBeForged()throws Exception{
        for(String note:List.of(""," \ufeff\u00a0\n\t","x\u001b","x\u0085","😀".repeat(2001))){String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(note.getBytes(java.nio.charset.StandardCharsets.UTF_8)));rejected("23514",()->insert(parent,actor,note,hash,null));}
        rejected("23514",()->insert(parent,actor,"raw note","a".repeat(64),null));assertNotNull(insert("😀".repeat(2000),null));
    }
    @Test void monitoringAutomaticRecoveryOrFormalWorkflowClaimsAreRejected()throws SQLException{
        for(String extra:List.of("manual_recovery_status='AUTO_RECOVERED'","verification_status='VERIFIED'","record_source='MONITOR_CALLBACK'","monitor_state='NORMAL'","disposition_state='RESOLVED'","monitoring_recovery_applicable=true","ticket_state_changed=true","sla_changed=true"))rejected("23514",()->insert("claims",extra));
    }
    @Test void reportedRecoveryTimeCanBeHistoricalButCannotExceedRecordedTime()throws SQLException{
        rejected("23514",()->insert("future","reported_recovered_at=statement_timestamp()+interval '1 hour'"));
        rejected("23514",()->insert("outside range","reported_recovered_at=TIMESTAMPTZ '10000-01-01 00:00:00+00'"));
        rejected("23514",()->insert("outside range","recorded_at=TIMESTAMPTZ '0001-01-01 00:00:00 BC'"));
        String id=insert("明确历史人工恢复时间","reported_recovered_at=TIMESTAMPTZ '2020-02-29 00:00:00.123+00'");
        // The designated server uses +08 while the cloud test session used UTC.
        // Compare the stored instant, not PostgreSQL's session-dependent text rendering.
        try(var statement=connection.prepareStatement("SELECT reported_recovered_at FROM public.mop_manual_recovery_confirmation WHERE id=?::uuid")){
            statement.setString(1,id);
            try(var result=statement.executeQuery()){
                assertTrue(result.next());
                assertEquals(OffsetDateTime.parse("2020-02-29T00:00:00.123Z").toInstant(),result.getObject(1,OffsetDateTime.class).toInstant());
            }
        }
    }
    @Test void runtimeCannotMutateOrDeleteEvidenceAndPublicHasNoGrant()throws SQLException{
        for(String sql:List.of("UPDATE public.mop_manual_recovery_confirmation SET note='changed' WHERE false","DELETE FROM public.mop_manual_recovery_confirmation WHERE false","TRUNCATE public.mop_manual_recovery_confirmation"))rejected("42501",()->{try(var s=connection.createStatement()){s.executeUpdate(sql);}});
        assertEquals("t",scalar("SELECT has_table_privilege(current_user,'public.mop_manual_recovery_confirmation','SELECT') AND has_table_privilege(current_user,'public.mop_manual_recovery_confirmation','INSERT') AND NOT has_table_privilege(current_user,'public.mop_manual_recovery_confirmation','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public.mop_manual_recovery_confirmation','UPDATE')"));assertEquals("0",scalar("SELECT count(*)::text FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_manual_recovery_confirmation'::regclass AND a.grantee=0"));
    }
    @Test void mandatoryEvidenceCannotBeOmittedAndOriginalParentNeedsNoNewUpdateGrant()throws SQLException{
        var value=new ManualRecoveryConfirmation("required",null);rejected("23502",()->insert(parent,null,value.note(),value.noteSha256(),null));rejected("23502",()->insert(parent,actor,null,value.noteSha256(),null));rejected("23502",()->insert(parent,actor,value.note(),null,null));rejected("23502",()->insert("required","recorded_at=NULL"));
        assertEquals("f",scalar("SELECT has_any_column_privilege(current_user,'public.mop_manual_incident_registration','UPDATE')"));
    }
}
