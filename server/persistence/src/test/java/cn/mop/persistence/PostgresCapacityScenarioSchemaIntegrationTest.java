package cn.mop.persistence;
import cn.mop.domain.capacity.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** V16 explicitly applied beforehand. Runtime-role structural tests; every synthetic row is rolled back. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresCapacityScenarioSchemaIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;static final ObjectMapper JSON=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);Connection connection;String actor,foreignActor;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(2);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);}
    @BeforeEach void begin()throws SQLException{connection=pool.getConnection();connection.setAutoCommit(false);actor=scalar("SELECT user_id::text FROM public.mop_membership WHERE tenant_id='"+TENANT+"'::uuid AND role_code='padmin' AND active LIMIT 1");foreignActor=scalar("SELECT b.user_id::text FROM public.mop_membership b WHERE b.tenant_id='"+OTHER+"'::uuid AND NOT EXISTS(SELECT 1 FROM public.mop_membership a WHERE a.tenant_id='"+TENANT+"'::uuid AND a.user_id=b.user_id) LIMIT 1");}
    @AfterEach void rollback()throws SQLException{if(connection!=null){connection.rollback();connection.close();}}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    String scalar(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    boolean flag(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());boolean value=r.getBoolean(1);assertFalse(r.wasNull());return value;}}
    static CapacityCalculation calculation(boolean known){return known?new CapacityCalculation(new CapacityEstimate.Inputs(BigInteger.TEN,BigInteger.valueOf(100),new BigDecimal("10"),2,new BigDecimal("80"),new BigDecimal("0.5"),3,new BigDecimal("0.25")),new CapacityEstimate.Model("SYNTHETIC-v1",new BigDecimal("128"),new BigDecimal("60")),null):new CapacityCalculation(null,null,null);}
    static ObjectNode calc(boolean known){return JSON.valueToTree(calculation(known));}
    static ObjectNode snapshot(boolean known){return JSON.valueToTree(calculation(known).snapshot());}
    String insert(String id,String author,String label,String note,ObjectNode calculation,ObjectNode snapshot,String ref,String title,String hash,boolean wrongCalculationHash,boolean wrongSnapshotHash,String extra)throws SQLException{
        String[] parts=extra==null?null:extra.split("=",2);
        String sql="WITH material AS (SELECT ?::jsonb c,?::jsonb s) INSERT INTO public.mop_capacity_scenario_draft(id,tenant_id,actor_id,label,note,calculation,calculation_sha256,snapshot,snapshot_sha256,benchmark_reference,benchmark_title,benchmark_sha256"+(parts==null?"":","+parts[0])+") SELECT ?::uuid,?::uuid,?::uuid,?,?,c,CASE WHEN ? THEN repeat('a',64) ELSE encode(sha256(convert_to(c::text,'UTF8')),'hex') END,s,CASE WHEN ? THEN repeat('a',64) ELSE encode(sha256(convert_to(s::text,'UTF8')),'hex') END,?,?,?"+(parts==null?"":","+parts[1])+" FROM material";
        try(var s=connection.prepareStatement(sql)){s.setString(1,calculation.toString());s.setString(2,snapshot.toString());s.setString(3,id);s.setString(4,TENANT);s.setString(5,author);s.setString(6,label);s.setString(7,note);s.setBoolean(8,wrongCalculationHash);s.setBoolean(9,wrongSnapshotHash);s.setString(10,ref);s.setString(11,title);s.setString(12,hash);assertEquals(1,s.executeUpdate());}return id;
    }
    String insert(boolean known,String extra)throws SQLException{return insert(UUID.randomUUID().toString(),actor,"合成容量草稿","仅用于结构验证",calc(known),snapshot(known),null,null,null,false,false,extra);}
    @FunctionalInterface interface SqlAction{void run()throws SQLException;}
    void rejected(String state,SqlAction action)throws SQLException{Savepoint point=connection.setSavepoint();SQLException failure=assertThrows(SQLException.class,action::run);assertEquals(state,failure.getSQLState());connection.rollback(point);connection.releaseSavepoint(point);}
    @Test void unknownDraftPreservesNullInputsAndExplicitUnverifiedDefaults()throws SQLException{String id=insert(false,null);try(var s=connection.prepareStatement("SELECT * FROM public.mop_capacity_scenario_draft WHERE id=?::uuid")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(1,r.getLong("version"));assertEquals("DRAFT",r.getString("lifecycle_status"));assertEquals("UNVERIFIED",r.getString("model_approval_status"));for(String name:List.of("effective","measurement_verified","poc_executed"))assertFalse(r.getBoolean(name));for(String name:List.of("benchmark_reference","benchmark_title","benchmark_sha256"))assertNull(r.getObject(name));assertEquals("EXPLICIT_CAPACITY_DRAFT",r.getString("record_source"));assertNotNull(r.getObject("recorded_at",OffsetDateTime.class));}}
        assertTrue(flag("SELECT calculation_sha256=encode(sha256(convert_to(calculation::text,'UTF8')),'hex') AND snapshot_sha256=encode(sha256(convert_to(snapshot::text,'UTF8')),'hex') FROM public.mop_capacity_scenario_draft WHERE id='"+id+"'::uuid"));
    }
    @Test void knownCalculationAndUnicodeOriginalTextRoundTrip()throws Exception{String label="  合成😀  ",note=" 原文\r\n第二行\t ";String id=insert(UUID.randomUUID().toString(),actor,label,note,calc(true),snapshot(true),"document-ref:SYNTHETIC-v1","合成基准引用","b".repeat(64),false,false,null);try(var s=connection.prepareStatement("SELECT label,note,calculation,snapshot,benchmark_reference FROM public.mop_capacity_scenario_draft WHERE id=?::uuid")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(label,r.getString(1));assertEquals(note,r.getString(2));assertNotNull(r.getString(3));assertNotNull(r.getString(4));assertEquals("document-ref:SYNTHETIC-v1",r.getString(5));}}
        // JSONB normalizes numeric representation; compare exact JSONB values, not Jackson node classes.
        try(var s=connection.prepareStatement("SELECT calculation=?::jsonb,snapshot=?::jsonb FROM public.mop_capacity_scenario_draft WHERE id=?::uuid")){s.setString(1,calc(true).toString());s.setString(2,snapshot(true).toString());s.setString(3,id);try(var r=s.executeQuery()){assertTrue(r.next());assertTrue(r.getBoolean(1));assertTrue(r.getBoolean(2));}}
    }
    @Test void malformedOuterDocumentsAndMissingOrContradictoryResultStatesReject()throws SQLException{
        for(String variant:List.of("extra_input","missing_input","bad_group","extra_snapshot","wrong_formula","null_status","wrong_status","false_outputs","bad_missing_array")){
            var c=calc(false);var s=snapshot(false);switch(variant){case "extra_input"->c.put("tenant_id",TENANT);case "missing_input"->c.remove("model");case "bad_group"->c.put("inputs",false);case "extra_snapshot"->s.put("approved",true);case "wrong_formula"->s.putNull("capacity_formula_version");case "null_status"->((ObjectNode)s.get("capacity")).putNull("status");case "wrong_status"->((ObjectNode)s.get("capacity")).put("status","MEASURED");case "false_outputs"->((ObjectNode)s.get("capacity")).putObject("outputs");case "bad_missing_array"->((ObjectNode)s.get("proxy_buffer")).put("missing_inputs",false);default->throw new AssertionError();}
            rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",c,s,null,null,null,false,false,null));
        }
    }
    @Test void jsonEqualityFingerprintsCannotBeForged()throws SQLException{rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",calc(false),snapshot(false),null,null,null,true,false,null));rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",calc(false),snapshot(false),null,null,null,false,true,null));}
    @Test void effectiveApprovedMeasuredPocAndUnknownFormulaClaimsFail()throws SQLException{for(String extra:List.of("effective=true","measurement_verified=true","poc_executed=true","lifecycle_status='EFFECTIVE'","model_approval_status='APPROVED'","record_source='MEASURED_CAPACITY'","capacity_formula_version='unreviewed'","buffer_formula_version='unreviewed'"))rejected("23514",()->insert(false,extra));}
    @Test void baselineReferenceGroupAndTextRulesAreExplicit()throws SQLException{
        rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",calc(false),snapshot(false),"document-ref:one",null,null,false,false,null));
        for(String ref:List.of("https://example.invalid","document-ref:C:file","document-ref:/tmp/file","document-ref:token-value"))rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",calc(false),snapshot(false),ref,"title","a".repeat(64),false,false,null));
        rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",calc(false),snapshot(false),"document-ref:one","\ufeff","a".repeat(64),false,false,null));rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",calc(false),snapshot(false),"document-ref:one","title","A".repeat(64),false,false,null));
    }
    @Test void labelAndNoteCodePointBoundsRejectBlankControlsAndOversize()throws SQLException{
        for(String label:List.of(""," \ufeff\u3000","x\n","x\u0085"))rejected("23514",()->insert(UUID.randomUUID().toString(),actor,label,"note",calc(false),snapshot(false),null,null,null,false,false,null));
        for(String note:List.of(""," \ufeff\u3000","x\u0001","😀".repeat(2001)))rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label",note,calc(false),snapshot(false),null,null,null,false,false,null));
        rejected("22001",()->insert(UUID.randomUUID().toString(),actor,"😀".repeat(101),"note",calc(false),snapshot(false),null,null,null,false,false,null));insert(UUID.randomUUID().toString(),actor,"😀".repeat(100),"😀".repeat(2000),calc(false),snapshot(false),null,null,null,false,false,null);
    }
    @Test void foreignOrUnknownActorCannotCrossCompositeTenantReference()throws SQLException{for(String who:List.of(foreignActor,UUID.randomUUID().toString()))rejected("23503",()->insert(UUID.randomUUID().toString(),who,"label","note",calc(false),snapshot(false),null,null,null,false,false,null));}
    @Test void singleImmutableVersionAndUniqueIdentityAreEnforced()throws SQLException{for(String extra:List.of("version=0","version=2"))rejected("23514",()->insert(false,extra));String id=insert(false,null);rejected("23505",()->insert(id,actor,"other","note",calc(false),snapshot(false),null,null,null,false,false,null));}
    @Test void recordedTimestampRangeDoesNotChangeGlobalTimezone()throws SQLException{for(String extra:List.of("recorded_at=TIMESTAMPTZ '10000-01-01 00:00:00+00'","recorded_at=TIMESTAMPTZ '0001-01-01 00:00:00 BC'"))rejected("23514",()->insert(false,extra));assertNotNull(insert(false,"recorded_at=TIMESTAMPTZ '2026-01-01 00:00:00.123+00'"));}
    @Test void documentsHaveBoundedByteSizeIndependentOfJsonShape()throws SQLException{var c=calc(false);c.putObject("inputs").put("oversize","x".repeat(33000));rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",c,snapshot(false),null,null,null,false,false,null));var s=snapshot(true);((ObjectNode)s.get("capacity").get("outputs")).put("oversize","x".repeat(33000));rejected("23514",()->insert(UUID.randomUUID().toString(),actor,"label","note",calc(true),s,null,null,null,false,false,null));}
    @Test void runtimeCannotUpdateDeleteTruncateOrGrantPublicRights()throws SQLException{
        assertFalse(flag("SELECT has_table_privilege(current_user,'public.mop_capacity_scenario_draft','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"));assertFalse(flag("SELECT has_any_column_privilege(current_user,'public.mop_capacity_scenario_draft','UPDATE')"));assertEquals("0",scalar("SELECT count(*) FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_capacity_scenario_draft'::regclass AND a.grantee=0"));
        for(String sql:List.of("UPDATE public.mop_capacity_scenario_draft SET label='changed' WHERE false","DELETE FROM public.mop_capacity_scenario_draft WHERE false","TRUNCATE public.mop_capacity_scenario_draft"))rejected("42501",()->{try(var s=connection.createStatement()){s.executeUpdate(sql);}});
    }
}
