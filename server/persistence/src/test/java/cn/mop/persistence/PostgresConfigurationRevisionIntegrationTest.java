package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated PostgreSQL, private environment settings, no fixture or local database. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresConfigurationRevisionIntegrationTest {
    static HikariDataSource pool;
    static PostgresPlatformRepository repository;
    static ObjectMapper mapper;
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    static final String OTHER="55000000-0000-4000-8000-000000000002";
    static final String CONFIG="remote.idle_timeout_sec";
    static String creator,reviewer,engineer;
    static final List<Map<String,String>> EVIDENCE=List.of(Map.of("reference","document-ref:mop-test.evidence-v2","title","虚构内部审批证据，未经验证","sha256","a".repeat(64)));

    @BeforeAll static void connect()throws Exception{
        String url=required("SPRING_DATASOURCE_URL");assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        try(Connection connection=DriverManager.getConnection(url,required("MOP_MIGRATION_USERNAME"),required("MOP_MIGRATION_PASSWORD"));Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT current_database(),current_user")){
            assertTrue(rows.next());assertEquals("mop_test",rows.getString(1));assertEquals("mop_test_migrator",rows.getString(2));
        }
        Flyway.configure().dataSource(url,required("MOP_MIGRATION_USERNAME"),required("MOP_MIGRATION_PASSWORD")).locations("classpath:db/migration").schemas("public").createSchemas(false).cleanDisabled(true).target("4").load().migrate();
        pool=pool(5);mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        repository=new PostgresPlatformRepository(pool,mapper);assertTrue(repository.dependencyReady());
        creator=repository.users().stream().filter(user->user.displayName().equals("平台管理员（测试身份）")&&user.role().equals("padmin")).findFirst().orElseThrow().id();
        reviewer=repository.users().stream().filter(user->user.displayName().equals("配置复核管理员（测试身份）")&&user.role().equals("padmin")).findFirst().orElseThrow().id();
        engineer=repository.users().stream().filter(user->repository.membership(user.id(),TENANT)
            .filter(member->member.active()&&member.role().equals("msp")).isPresent()).findFirst().orElseThrow().id();assertNotEquals(creator,reviewer);
    }
    private static HikariDataSource pool(int maximum){
        HikariConfig config=new HikariConfig();config.setJdbcUrl(required("SPRING_DATASOURCE_URL"));config.setUsername(required("SPRING_DATASOURCE_USERNAME"));config.setPassword(required("SPRING_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(maximum);config.setConnectionTimeout(5000);return new HikariDataSource(config);
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing private remote setting "+key);return value;}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    private static Object write(PostgresPlatformRepository repo,String actor,String tenant,String route,String key,String hash,Supplier<Object> command){
        return repo.idempotent(actor,tenant,route,key,hash,repo.membership(actor,tenant).orElseThrow().permissionVersion(),command);
    }
    private static Map<String,Object> create(){return create(repository,creator,TENANT,CONFIG,UUID.randomUUID().toString());}
    private static Map<String,Object> create(PostgresPlatformRepository repo,String actor,String tenant,String config,String key){
        return asMap(write(repo,actor,tenant,"POST /integration/revisions/"+config,key,"1".repeat(64),()->{
            Map<String,Object> revision=repo.createConfigurationRevision(tenant,actor,config,240,"虚构配置修订集成验证",EVIDENCE);
            repo.audit(actor,tenant,"integration.configuration_revision.create","configuration_revision",revision.get("id").toString(),"SUCCEEDED","TEST_ONLY_UNVERIFIED",UUID.randomUUID().toString());return revision;
        }));
    }
    private static Map<String,Object> action(String actor,Map<String,Object> revision,String action){return action(repository,actor,TENANT,revision.get("id").toString(),((Number)revision.get("version")).longValue(),action,UUID.randomUUID().toString(),"2".repeat(64));}
    private static Map<String,Object> action(PostgresPlatformRepository repo,String actor,String tenant,String id,long expected,String action,String requestKey,String hash){
        return asMap(write(repo,actor,tenant,"POST /integration/revisions/"+id+"/"+action,requestKey,hash,()->{
            Map<String,Object> revision=repo.transitionConfigurationRevision(tenant,actor,id,expected,action,"SUBMIT".equals(action)?null:"虚构内部动作说明");
            repo.audit(actor,tenant,"integration.configuration_revision."+action.toLowerCase(Locale.ROOT),"configuration_revision",id,"SUCCEEDED","TEST_ONLY_UNVERIFIED",UUID.randomUUID().toString());return revision;
        }));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> asMap(Object value){return mapper.convertValue(value,LinkedHashMap.class);}
    private static JsonNode httpJson(Object value){try{return mapper.readTree(mapper.writeValueAsBytes(value));}catch(Exception exception){throw new IllegalStateException(exception);}}
    private static int historySize(Map<String,Object> revision){return ((List<?>)revision.get("history")).size();}

    @Test void durableTenantRevisionApprovalNeverActivatesTheRegistry(){
        Map<String,Object> registryBefore=repository.configuration(CONFIG),draft=create();String id=draft.get("id").toString();
        assertEquals("DRAFT",draft.get("workflow_status"));assertEquals(1L,((Number)draft.get("version")).longValue());assertEquals(1,historySize(draft));
        assertTrue(repository.configurationRevision(OTHER,id).isEmpty());assertTrue(repository.configurationRevisions(OTHER,CONFIG).stream().noneMatch(item->id.equals(item.get("id"))));
        DomainException hidden=assertThrows(DomainException.class,()->action(repository,reviewer,OTHER,id,1,"APPROVE",UUID.randomUUID().toString(),"3".repeat(64)));assertEquals(404,hidden.status);
        Map<String,Object> submitted=action(creator,draft,"SUBMIT");assertEquals("IN_REVIEW",submitted.get("workflow_status"));
        assertEquals(403,assertThrows(DomainException.class,()->action(creator,submitted,"APPROVE")).status);assertEquals(403,assertThrows(DomainException.class,()->action(creator,submitted,"REJECT")).status);
        assertEquals(2,historySize(repository.configurationRevision(TENANT,id).orElseThrow()));
        Map<String,Object> approved=action(reviewer,submitted,"APPROVE");assertEquals("APPROVED",approved.get("workflow_status"));assertEquals(reviewer,approved.get("reviewed_by"));assertNotNull(approved.get("reviewed_at"));
        assertEquals("UNVERIFIED",approved.get("confirmation_status"));assertEquals("pending",approved.get("decision_status"));assertNull(approved.get("effective_value"));assertEquals(3,historySize(approved));
        assertEquals(registryBefore,repository.configuration(CONFIG));assertEquals(approved,asMap(new PostgresPlatformRepository(pool,mapper).configurationRevision(TENANT,id).orElseThrow()));
        assertEquals(422,assertThrows(DomainException.class,()->action(creator,approved,"WITHDRAW")).status);
        assertEquals(403,assertThrows(DomainException.class,()->create(repository,engineer,TENANT,CONFIG,UUID.randomUUID().toString())).status);
        assertEquals(422,assertThrows(DomainException.class,()->create(repository,creator,TENANT,"business.timezone",UUID.randomUUID().toString())).status);
    }

    @Test void rejectedAndWithdrawnProposalsRemainImmutableAndReproposalGetsANewId(){
        Map<String,Object> draft=create();assertEquals(403,assertThrows(DomainException.class,()->action(reviewer,draft,"SUBMIT")).status);assertEquals(403,assertThrows(DomainException.class,()->action(reviewer,draft,"WITHDRAW")).status);
        Map<String,Object> rejected=action(reviewer,action(creator,draft,"SUBMIT"),"REJECT");assertEquals("REJECTED",rejected.get("workflow_status"));assertEquals(422,assertThrows(DomainException.class,()->action(creator,rejected,"WITHDRAW")).status);
        Map<String,Object> reproposal=create();assertNotEquals(draft.get("id"),reproposal.get("id"));assertEquals("DRAFT",reproposal.get("workflow_status"));
        Map<String,Object> withdrawnDraft=action(creator,reproposal,"WITHDRAW");assertEquals("WITHDRAWN",withdrawnDraft.get("workflow_status"));assertEquals(2,historySize(withdrawnDraft));
        Map<String,Object> withdrawnReview=action(creator,action(creator,create(),"SUBMIT"),"WITHDRAW");assertEquals("WITHDRAWN",withdrawnReview.get("workflow_status"));assertNull(withdrawnReview.get("reviewed_by"));assertEquals(3,historySize(withdrawnReview));
        for(String action:List.of("SUBMIT","APPROVE","REJECT","WITHDRAW"))assertEquals(422,assertThrows(DomainException.class,()->action(action.equals("APPROVE")||action.equals("REJECT")?reviewer:creator,withdrawnReview,action)).status);
        assertEquals(draft.get("proposed_value"),rejected.get("proposed_value"));assertEquals(draft.get("evidence_refs"),rejected.get("evidence_refs"));
    }

    @Test void concurrentReviewHasOneCasWinnerAndExactlyOneDecisionEvent()throws Exception{
        Map<String,Object> submitted=action(creator,create(),"SUBMIT");ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<Integer>> tasks=new ArrayList<>();for(String decision:List.of("APPROVE","REJECT"))tasks.add(threads.submit(()->{start.await();try{action(new PostgresPlatformRepository(pool,mapper),reviewer,TENANT,submitted.get("id").toString(),2,decision,UUID.randomUUID().toString(),"4".repeat(64));return 200;}catch(DomainException error){return error.status;}}));
            start.countDown();List<Integer> results=List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS));assertTrue(results.contains(200)&&results.contains(409));
            Map<String,Object> actual=repository.configurationRevision(TENANT,submitted.get("id").toString()).orElseThrow();assertEquals(3L,((Number)actual.get("version")).longValue());assertEquals(3,historySize(actual));
        }finally{threads.shutdownNow();}
    }

    @Test void replayPrecedesStaleVersionButPayloadAndPermissionChangesFail(){
        String creationKey=UUID.randomUUID().toString();Map<String,Object> draft=create(repository,creator,TENANT,CONFIG,creationKey);String id=draft.get("id").toString(),submitKey=UUID.randomUUID().toString();
        Map<String,Object> submitted=action(repository,creator,TENANT,id,1,"SUBMIT",submitKey,"5".repeat(64));action(reviewer,submitted,"APPROVE");
        Map<String,Object> creationReplay=create(new PostgresPlatformRepository(pool,mapper),creator,TENANT,CONFIG,creationKey),submissionReplay=action(repository,creator,TENANT,id,1,"SUBMIT",submitKey,"5".repeat(64));
        assertEquals(httpJson(draft),httpJson(creationReplay));assertEquals(httpJson(submitted),httpJson(submissionReplay));
        assertEquals(id,creationReplay.get("id"));assertEquals(id,submissionReplay.get("id"));assertEquals(1L,((Number)creationReplay.get("version")).longValue());assertEquals(2L,((Number)submissionReplay.get("version")).longValue());assertEquals(1,historySize(creationReplay));assertEquals(2,historySize(submissionReplay));
        assertEquals(409,assertThrows(DomainException.class,()->action(repository,creator,TENANT,id,1,"SUBMIT",submitKey,"6".repeat(64))).status);
        long permission=repository.membership(creator,TENANT).orElseThrow().permissionVersion();assertEquals(401,assertThrows(DomainException.class,()->repository.idempotent(creator,TENANT,"POST /integration/revisions/"+id+"/SUBMIT",submitKey,"5".repeat(64),permission+1,()->"must not execute")).status);
        Map<String,Object> current=repository.configurationRevision(TENANT,id).orElseThrow();assertEquals("APPROVED",current.get("workflow_status"));assertEquals(3L,((Number)current.get("version")).longValue());assertEquals(3,historySize(current));assertEquals(1,repository.configurationRevisions(TENANT,CONFIG).stream().filter(item->id.equals(item.get("id"))).count());
    }

    @Test void rollbackRemovesRevisionHistoryAuditAndReplayAndAllowsARealRetry(){
        String key=UUID.randomUUID().toString(),trace=UUID.randomUUID().toString();int before=repository.configurationRevisions(TENANT,CONFIG).size();
        assertThrows(IllegalStateException.class,()->write(repository,creator,TENANT,"POST /integration/revisions/rollback",key,"7".repeat(64),()->{
            Map<String,Object> revision=repository.createConfigurationRevision(TENANT,creator,CONFIG,241,"虚构回滚验证",EVIDENCE);repository.audit(creator,TENANT,"integration.configuration_revision.rollback","configuration_revision",revision.get("id").toString(),"SUCCEEDED","MUST_ROLL_BACK",trace);throw new IllegalStateException("intentional rollback");
        }));
        assertEquals(before,repository.configurationRevisions(TENANT,CONFIG).size());assertTrue(repository.audits(TENANT).stream().noneMatch(row->trace.equals(row.get("trace_id"))));
        assertEquals("retried",write(repository,creator,TENANT,"POST /integration/revisions/rollback",key,"8".repeat(64),()->"retried"));
        Map<String,Object> draft=create();String id=draft.get("id").toString(),actionKey=UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class,()->write(repository,creator,TENANT,"POST /integration/revisions/rollback-action",actionKey,"9".repeat(64),()->{repository.transitionConfigurationRevision(TENANT,creator,id,1,"SUBMIT",null);throw new IllegalStateException("intentional action rollback");}));
        assertEquals(draft,asMap(repository.configurationRevision(TENANT,id).orElseThrow()));assertEquals("IN_REVIEW",action(creator,draft,"SUBMIT").get("workflow_status"));
    }

    @Test void runtimeCannotRewriteProposalOwnershipOrAppendOnlyHistoryAndOneConnectionCanRead()throws Exception{
        Map<String,Object> draft=create();String id=draft.get("id").toString();
        try(Connection connection=pool.getConnection();Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT has_column_privilege(current_user,'public.mop_configuration_revision','proposal','UPDATE'),has_column_privilege(current_user,'public.mop_configuration_revision','tenant_id','UPDATE'),has_column_privilege(current_user,'public.mop_configuration_revision','created_by','UPDATE'),has_column_privilege(current_user,'public.mop_configuration_revision','created_at','UPDATE'),has_table_privilege(current_user,'public.mop_configuration_revision','DELETE'),has_table_privilege(current_user,'public.mop_configuration_revision_history','UPDATE'),has_table_privilege(current_user,'public.mop_configuration_revision_history','DELETE'),has_column_privilege(current_user,'public.mop_configuration_revision','workflow_status','UPDATE')")){
            assertTrue(rows.next());for(int i=1;i<=7;i++)assertFalse(rows.getBoolean(i));assertTrue(rows.getBoolean(8));
        }
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_configuration_revision_history SET payload='{}'::jsonb WHERE tenant_id::text=? AND revision_id::text=?")){
            statement.setString(1,TENANT);statement.setString(2,id);assertEquals("42501",assertThrows(SQLException.class,statement::executeUpdate).getSQLState());
        }
        try(HikariDataSource single=pool(1)){
            PostgresPlatformRepository reader=new PostgresPlatformRepository(single,mapper);assertEquals(id,reader.configurationRevision(TENANT,id).orElseThrow().get("id"));assertTrue(reader.configurationRevisions(TENANT,CONFIG).stream().anyMatch(row->id.equals(row.get("id"))));
        }
    }
}
