package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in only against the designated shared test server through its local SSH tunnel. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresRepositoryIntegrationTest {
    static HikariDataSource pool;
    static PostgresPlatformRepository repository;
    static ObjectMapper mapper;
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    static final String OTHER="55000000-0000-4000-8000-000000000002";
    static String actor;
    @BeforeAll static void connect()throws Exception{
        String url=required("SPRING_DATASOURCE_URL");assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        try(Connection connection=DriverManager.getConnection(url,required("MOP_MIGRATION_USERNAME"),required("MOP_MIGRATION_PASSWORD"));Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT current_database(),current_user")){assertTrue(rows.next());assertEquals("mop_test",rows.getString(1));assertEquals("mop_test_migrator",rows.getString(2));}
        Flyway.configure().dataSource(url,required("MOP_MIGRATION_USERNAME"),required("MOP_MIGRATION_PASSWORD")).locations("classpath:db/migration").schemas("public").createSchemas(false).cleanDisabled(true).load().migrate();
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(required("SPRING_DATASOURCE_USERNAME"));config.setPassword(required("SPRING_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(5);config.setConnectionTimeout(5000);pool=new HikariDataSource(config);
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        repository=new PostgresPlatformRepository(pool,mapper);actor=repository.users().stream().filter(user->user.role().equals("padmin")).findFirst().orElseThrow().id();assertTrue(repository.dependencyReady());
    }
    static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing private remote setting "+key);return value;}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static Object create(PostgresPlatformRepository repo,String name,String key,String hash){return repo.idempotent(actor,TENANT,"POST /integration/assets",key,hash,()->{Asset asset=repo.insert(TENANT,actor,name,"router","192.0.2.88","site-a-main");repo.audit(actor,TENANT,"integration.asset_draft","asset",asset.id(),"SUCCEEDED","TEST_ONLY",UUID.randomUUID().toString());return asset;});}
    static JsonNode httpJson(Object value){try{return mapper.readTree(mapper.writeValueAsString(value));}catch(Exception exception){throw new IllegalStateException(exception);}}
    @Test void independentRepositoryReplaysCommittedResultAndEnforcesPayloadScope(){
        String key=UUID.randomUUID().toString(),name="远程持久验证 "+key,hash="a".repeat(64);Object created=create(repository,name,key,hash);
        PostgresPlatformRepository afterRestart=new PostgresPlatformRepository(pool,mapper);Object replay=create(afterRestart,name,key,hash);
        assertEquals(httpJson(created),httpJson(replay));String id=mapper.valueToTree(created).path("id").asText();assertEquals("manual",afterRestart.find(TENANT,id).orElseThrow().source());assertTrue(afterRestart.find(OTHER,id).isEmpty());
        DomainException conflict=assertThrows(DomainException.class,()->create(afterRestart,name,key,"b".repeat(64)));assertEquals("IDEMPOTENCY_CONFLICT",conflict.code);
    }
    @Test void concurrentConnectionsCommitOneAssetAndOneAudit()throws Exception{
        String key=UUID.randomUUID().toString(),name="远程并发验证 "+key;ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Object>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(threads.submit(()->{start.await();return create(new PostgresPlatformRepository(pool,mapper),name,key,"c".repeat(64));}));start.countDown();JsonNode first=httpJson(tasks.get(0).get(20,TimeUnit.SECONDS)),second=httpJson(tasks.get(1).get(20,TimeUnit.SECONDS));assertEquals(first,second);assertEquals(1,repository.list(TENANT).stream().filter(asset->asset.name().equals(name)).count());assertEquals(1,repository.audits(TENANT).stream().filter(row->Objects.equals(first.path("id").asText(),row.get("resource_id"))).count());}
        finally{threads.shutdownNow();}
    }
    @Test void failedCommandRollsBackAssetAuditAndReplayRecord(){
        String key=UUID.randomUUID().toString(),name="远程回滚验证 "+key;
        assertThrows(IllegalStateException.class,()->repository.idempotent(actor,TENANT,"POST /integration/rollback",key,"d".repeat(64),()->{Asset asset=repository.insert(TENANT,actor,name,"router","192.0.2.89","site-a-main");repository.audit(actor,TENANT,"integration.rollback","asset",asset.id(),"SUCCEEDED","MUST_ROLL_BACK",UUID.randomUUID().toString());throw new IllegalStateException("simulated failure");}));
        assertTrue(repository.list(TENANT).stream().noneMatch(asset->asset.name().equals(name)));assertTrue(repository.audits(TENANT).stream().noneMatch(row->"integration.rollback".equals(row.get("action"))));
        assertEquals("retried",repository.idempotent(actor,TENANT,"POST /integration/rollback",key,"e".repeat(64),()->"retried"));
    }
    @Test void configurationCasHasOneWinnerAndNeverActivatesValue()throws Exception{
        Map<String,Object> chosen=repository.configurations().stream().filter(row->"pending".equals(row.get("decision_status"))&&"integer".equals(row.get("type"))).findFirst().orElseThrow();String key=chosen.get("key").toString();long version=((Number)chosen.get("version")).longValue();
        ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Integer>> tasks=new ArrayList<>();for(int value:List.of(31,32))tasks.add(threads.submit(()->{start.await();try{repository.idempotent(actor,TENANT,"POST /integration/config/"+key,UUID.randomUUID().toString(),"f".repeat(64),()->{Map<String,Object> result=repository.draftConfiguration(key,value,version);repository.audit(actor,TENANT,"integration.config_draft","configuration",key,"SUCCEEDED","TEST_ONLY",UUID.randomUUID().toString());return result;});return 200;}catch(DomainException exception){return exception.status;}}));start.countDown();List<Integer> status=List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS));assertTrue(status.contains(200)&&status.contains(409));Map<String,Object> actual=repository.configuration(key);assertEquals(version+1,((Number)actual.get("version")).longValue());assertNull(actual.get("effective_value"));assertNull(actual.get("value"));}
        finally{threads.shutdownNow();}
    }
    @Test void runtimePrivilegesAndAuditHashAreVerifiable()throws Exception{
        try(Connection connection=pool.getConnection();Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT current_user,has_schema_privilege(current_user,'public','CREATE'),has_table_privilege(current_user,'public.mop_audit','UPDATE'),has_table_privilege(current_user,'public.mop_audit','DELETE'),has_column_privilege(current_user,'public.mop_configuration','registry_json','UPDATE'),has_table_privilege(current_user,'public.flyway_schema_history','SELECT')")){assertTrue(rows.next());assertEquals("mop_test_runtime",rows.getString(1));for(int i=2;i<=6;i++)assertFalse(rows.getBoolean(i));}
        String key=UUID.randomUUID().toString();Object created=create(repository,"远程审计验证 "+key,key,"1".repeat(64));String id=mapper.valueToTree(created).path("id").asText();Map<String,Object> row=repository.audits(TENANT).stream().filter(item->id.equals(item.get("resource_id"))).findFirst().orElseThrow();TreeMap<String,Object> canonical=new TreeMap<>(row);String hash=canonical.remove("entry_hash").toString();String input=canonical.get("previous_hash")+mapper.valueToTree(canonical).toString();assertEquals(hash,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))));
    }
}
