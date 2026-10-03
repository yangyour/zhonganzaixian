package cn.mop.persistence;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real runtime-role V13 constraints only; every synthetic registration is rolled back. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresManualIncidentSchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool; Connection connection; String asset,actor,foreignAsset,foreignActor,assetName; long assetVersion;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);}
    @BeforeEach void begin()throws SQLException{
        connection=pool.getConnection();connection.setAutoCommit(false);
        asset=scalar("SELECT id::text FROM public.mop_asset WHERE tenant_id='"+TENANT+"'::uuid ORDER BY id LIMIT 1");
        foreignAsset=scalar("SELECT id::text FROM public.mop_asset WHERE tenant_id='"+OTHER+"'::uuid ORDER BY id LIMIT 1");
        actor=scalar("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"'::uuid AND role_code='msp' AND active LIMIT 1");
        foreignActor=scalar("SELECT b.user_id::text FROM public.mop_membership b WHERE b.tenant_id='"+OTHER+"'::uuid AND NOT EXISTS(SELECT 1 FROM public.mop_membership a WHERE a.tenant_id='"+TENANT+"'::uuid AND a.user_id=b.user_id) LIMIT 1");
        assetName=scalar("SELECT name FROM public.mop_asset WHERE id='"+asset+"'::uuid");
        assetVersion=Long.parseLong(scalar("SELECT version::text FROM public.mop_asset WHERE id='"+asset+"'::uuid"));
    }
    @AfterEach void rollback()throws SQLException{if(connection!=null){connection.rollback();connection.close();}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    String scalar(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    String insert(String parent,String author,String title,String severity,long version,String name,String extra)throws SQLException{
        String id=UUID.randomUUID().toString();String[] parts=extra==null?null:extra.split("=",2);
        try(var s=connection.prepareStatement("INSERT INTO public.mop_manual_incident_registration(id,tenant_id,asset_id,actor_id,observed_asset_version,asset_name_at_registration,title,severity"+(parts==null?"":","+parts[0])+") VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?"+(parts==null?"":","+parts[1])+")")){
            s.setString(1,id);s.setString(2,TENANT);s.setString(3,parent);s.setString(4,author);s.setLong(5,version);s.setString(6,name);s.setString(7,title);s.setString(8,severity);s.executeUpdate();
        }return id;
    }
    String insert(String title,String severity,long version)throws SQLException{return insert(asset,actor,title,severity,version,assetName,null);}
    @FunctionalInterface interface SqlAction{void run()throws SQLException;}
    void rejected(String state,SqlAction action)throws SQLException{Savepoint point=connection.setSavepoint();SQLException failure=assertThrows(SQLException.class,action::run);assertEquals(state,failure.getSQLState());connection.rollback(point);connection.releaseSavepoint(point);}
    @Test void exactUnicodeProvenanceUnknownFieldsAndParentRemainUnchanged()throws SQLException{
        String title="  合成电话登记😀  ",before=scalar("SELECT md5(to_jsonb(a)::text) FROM public.mop_asset a WHERE id='"+asset+"'::uuid");
        String id=insert(title,"warning",assetVersion);
        try(var s=connection.prepareStatement("SELECT * FROM public.mop_manual_incident_registration WHERE id=?::uuid")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(title,r.getString("title"));assertEquals(assetName,r.getString("asset_name_at_registration"));assertEquals(assetVersion,r.getLong("observed_asset_version"));assertEquals("MANUAL",r.getString("source_state"));assertEquals("REGISTERED_ONLY",r.getString("registration_status"));assertEquals("SELF_REPORTED",r.getString("verification_status"));assertEquals("NOT_CONFIRMED",r.getString("manual_recovery_status"));assertEquals("MANUAL_INCIDENT_REGISTRATION",r.getString("record_source"));assertFalse(r.getBoolean("ticket_automatically_created"));for(String column:List.of("monitor_state","disposition_state","ticket_priority","observed_at","recovered_at"))assertNull(r.getObject(column));assertNotNull(r.getObject("registered_at",OffsetDateTime.class));}}
        assertEquals(before,scalar("SELECT md5(to_jsonb(a)::text) FROM public.mop_asset a WHERE id='"+asset+"'::uuid"));
    }
    @Test void compositeTenantForeignKeysRejectOtherAssetAndActor()throws SQLException{
        rejected("23503",()->insert(foreignAsset,actor,"范围验证","info",1,assetName,null));
        rejected("23503",()->insert(asset,foreignActor,"范围验证","info",assetVersion,assetName,null));
    }
    @Test void fiveSeveritiesAreExplicitAndUnknownOrMissingRejected()throws SQLException{
        for(String severity:List.of("disaster","high","average","warning","info")){String id=insert("等级验证",severity,assetVersion);assertEquals(severity,scalar("SELECT severity FROM public.mop_manual_incident_registration WHERE id='"+id+"'::uuid"));}
        for(String severity:List.of("none","NORMAL","unknown",""))rejected("23514",()->insert("等级验证",severity,assetVersion));
        rejected("23502",()->insert("等级验证",null,assetVersion));
    }
    @Test void safeObservedVersionBoundsAreEnforced()throws SQLException{
        for(long version:new long[]{0,-1,9007199254740992L})rejected("23514",()->insert("版本验证","info",version));
        assertNotNull(insert("版本上限","info",9007199254740991L));
        // Matching the actual asset version is an application transaction responsibility, not a schema claim.
    }
    @Test void titlesPreserveCodePointsAndRejectBlankControlsAndOversize()throws SQLException{
        for(String title:List.of(""," \ufeff\u00a0\u3000","x\n","x\t","x\u001b","x\u0085"))rejected("23514",()->insert(title,"info",assetVersion));
        assertNotNull(insert("😀".repeat(200),"info",assetVersion));
        rejected("22001",()->insert("😀".repeat(201),"info",assetVersion));
        rejected("23502",()->insert(null,"info",assetVersion));
    }
    @Test void sourceRecoveryDispositionAndAutomaticTicketClaimsAreRejected()throws SQLException{
        for(String extra:List.of("source_state='MONITOR'","registration_status='ACTIVE'","verification_status='VERIFIED'","monitor_state='NORMAL'","disposition_state='RESOLVED'","ticket_priority='P1'","observed_at=statement_timestamp()","recovered_at=statement_timestamp()","manual_recovery_status='CONFIRMED'","ticket_automatically_created=true","record_source='MONITOR_CALLBACK'"))
            rejected("23514",()->insert(asset,actor,"来源边界","info",assetVersion,assetName,extra));
    }
    @Test void runtimeHasOnlyInsertSelectAndNoPublicOrColumnUpdateGrant()throws SQLException{
        for(String sql:List.of("UPDATE public.mop_manual_incident_registration SET title='changed' WHERE false","DELETE FROM public.mop_manual_incident_registration WHERE false","TRUNCATE public.mop_manual_incident_registration"))rejected("42501",()->{try(var s=connection.createStatement()){s.executeUpdate(sql);}});
        assertEquals("t",scalar("SELECT has_table_privilege(current_user,'public.mop_manual_incident_registration','SELECT') AND has_table_privilege(current_user,'public.mop_manual_incident_registration','INSERT') AND NOT has_table_privilege(current_user,'public.mop_manual_incident_registration','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public.mop_manual_incident_registration','UPDATE')"));
        assertEquals("0",scalar("SELECT count(*)::text FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_manual_incident_registration'::regclass AND a.grantee=0"));
    }
    @Test void snapshotNameAndRegistrationTimestampBoundariesAreRequired()throws SQLException{
        rejected("23502",()->insert(asset,actor,"快照","info",assetVersion,null,null));
        rejected("23514",()->insert(asset,actor,"快照","info",assetVersion,"",null));
        rejected("22001",()->insert(asset,actor,"快照","info",assetVersion,"a".repeat(101),null));
        rejected("23502",()->insert(asset,actor,"时间","info",assetVersion,assetName,"registered_at=NULL"));
        for(String time:List.of("0001-01-01 00:00:00 BC","10000-01-01 00:00:00+00"))rejected("23514",()->insert(asset,actor,"时间","info",assetVersion,assetName,"registered_at=TIMESTAMPTZ '"+time+"'"));
    }
}
