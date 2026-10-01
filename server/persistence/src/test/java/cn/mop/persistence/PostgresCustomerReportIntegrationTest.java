package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Designated PostgreSQL only. A durable receipt remains PENDING and is not a configured ticket workflow. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresCustomerReportIntegrationTest {
    static HikariDataSource pool;
    static PostgresPlatformRepository repository;
    static ObjectMapper mapper;
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static String customer,otherCustomer,sameTenantCustomer,admin,engineer;
    static final String ROUTE="POST /integration/tickets";
    @BeforeAll static void connect()throws Exception{
        String url=required("SPRING_DATASOURCE_URL");assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        try(Connection connection=DriverManager.getConnection(url,required("MOP_MIGRATION_USERNAME"),required("MOP_MIGRATION_PASSWORD"));Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT current_database(),current_user")){assertTrue(rows.next());assertEquals("mop_test",rows.getString(1));assertEquals("mop_test_migrator",rows.getString(2));}
        Flyway.configure().dataSource(url,required("MOP_MIGRATION_USERNAME"),required("MOP_MIGRATION_PASSWORD")).locations("classpath:db/migration").schemas("public").createSchemas(false).cleanDisabled(true).load().migrate();
        pool=pool(5);mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);repository=new PostgresPlatformRepository(pool,mapper);assertTrue(repository.dependencyReady());
        customer=user("租户 A 只读用户（测试身份）");otherCustomer=user("租户 B 只读用户（测试身份）");sameTenantCustomer=user("租户 A 管理员（测试身份）");admin=user("平台管理员（测试身份）");engineer=user("服务工程师（测试身份）");
    }
    static String user(String display){return repository.users().stream().filter(user->user.displayName().equals(display)).findFirst().orElseThrow().id();}
    static HikariDataSource pool(int maximum){HikariConfig config=new HikariConfig();config.setJdbcUrl(required("SPRING_DATASOURCE_URL"));config.setUsername(required("SPRING_DATASOURCE_USERNAME"));config.setPassword(required("SPRING_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(maximum);config.setConnectionTimeout(5000);return new HikariDataSource(config);}
    static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing private remote setting "+key);return value;}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static CustomerReport report(String description,String asset){return new CustomerReport(null,description,"网络不通","company","虚构联系人","+86 (010) 1234-5678","urgent",asset);}
    static Object register(PostgresPlatformRepository repo,String actor,String tenant,CustomerReport report,String requestKey,String hash){
        return repo.idempotent(actor,tenant,ROUTE,requestKey,hash,repo.membership(actor,tenant).orElseThrow().permissionVersion(),()->{
            CustomerReportRegistration registration=repo.registerCustomerReport(tenant,actor,report);
            if(registration.created())repo.audit(actor,tenant,"integration.ticket.customer_report.register","ticket",registration.ticketId(),"SUCCEEDED","DURABLE_RECEIPT_ONLY_RULES_PENDING",UUID.randomUUID().toString());return Map.of("operation_id",registration.operationId(),"status","PENDING");
        });
    }
    static Object register(CustomerReport report){return register(repository,customer,TENANT,report,UUID.randomUUID().toString(),"1".repeat(64));}
    static JsonNode httpJson(Object value){try{return mapper.readTree(mapper.writeValueAsBytes(value));}catch(Exception error){throw new IllegalStateException(error);}}
    static Set<String> fields(JsonNode value){Set<String> fields=new HashSet<>();value.fieldNames().forEachRemaining(fields::add);return fields;}
    static String operationId(Object accepted){return httpJson(accepted).path("operation_id").asText();}
    static Map<String,Object> ticket(Object accepted,String actor,String tenant){Map<String,Object> operation=repository.operation(tenant,actor,operationId(accepted)).orElseThrow();String id=operation.get("result_ref").toString().substring("/api/v1/tickets/".length());return repository.ticket(tenant,actor,id).orElseThrow();}
    static long count(String table,String field,String id)throws SQLException{assertTrue(Set.of("mop_ticket","mop_ticket_history","mop_operation","mop_outbox","mop_audit").contains(table));assertTrue(Set.of("id","ticket_id","operation_id").contains(field));try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public."+table+" WHERE "+field+"::text=?")){statement.setString(1,id);try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}}
    static void assertSingleRegistration(Map<String,Object> ticket)throws SQLException{
        String id=ticket.get("id").toString();assertEquals(1,count("mop_ticket","id",id));assertEquals(1,count("mop_ticket_history","ticket_id",id));assertEquals(1,count("mop_operation","ticket_id",id));assertEquals(1,count("mop_outbox","ticket_id",id));assertEquals(1,repository.audits(TENANT).stream().filter(row->id.equals(row.get("resource_id"))&&"integration.ticket.customer_report.register".equals(row.get("action"))).count());
    }
    static String freshAsset(){String key=UUID.randomUUID().toString();Asset asset=(Asset)repository.idempotent(admin,TENANT,"POST /integration/ticket-assets",key,"a".repeat(64),()->repository.insert(TENANT,admin,"虚构报障设备 "+key,"router","192.0.2.95","site-a-main"));return asset.id();}

    @Test void acceptedIntentPersistsAcrossRepositoryRestartWithoutFalseBusinessOrExternalStatus()throws Exception{
        Object accepted=register(report("虚构耐久受理 "+UUID.randomUUID(),null));Map<String,Object> ticket=ticket(accepted,customer,TENANT);String id=ticket.get("id").toString();
        assertEquals(Set.of("operation_id","status"),fields(httpJson(accepted)));assertEquals("PENDING",httpJson(accepted).path("status").asText());
        assertEquals("REGISTERED",ticket.get("registration_status"));assertEquals("MOP",ticket.get("authority_source"));assertEquals("RULES_PENDING",ticket.get("authority_status"));assertEquals("NOT_APPLICABLE",ticket.get("sync_state"));assertEquals("UNVERIFIED",ticket.get("contact_binding_status"));assertEquals(customer,ticket.get("reporter_id"));assertEquals(1L,((Number)ticket.get("version")).longValue());
        for(String key:List.of("state","external_id","external_state","priority","response_due_at","recovery_due_at","asset_id","asset_name"))assertNull(ticket.get(key));assertTrue(ticket.get("receipt_no").toString().startsWith("REG-"));assertEquals(1,((List<?>)ticket.get("history")).size());assertSingleRegistration(ticket);
        PostgresPlatformRepository restarted=new PostgresPlatformRepository(pool,mapper);assertEquals(httpJson(ticket),httpJson(restarted.ticket(TENANT,customer,id).orElseThrow()));
        Map<String,Object> operation=restarted.operation(TENANT,customer,operationId(accepted)).orElseThrow();assertEquals("CUSTOMER_REPORT_REGISTRATION",operation.get("kind"));assertEquals("PENDING",operation.get("status"));assertEquals("AWAITING_TICKET_CONFIGURATION",operation.get("step"));assertNull(operation.get("progress"));assertEquals("/api/v1/tickets/"+id,operation.get("result_ref"));JsonNode error=httpJson(operation).path("error");assertEquals("CONFIG_PENDING",error.path("code").asText());assertFalse(error.path("retryable").asBoolean());assertEquals(mapper.valueToTree(List.of("Q07","Q08","Q09","Q14","Q16")),error.path("question_ids"));assertFalse(operation.containsKey("payload"));assertFalse(operation.containsKey("actor_id"));
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT status,blocked_question_ids,payload,next_at FROM public.mop_outbox WHERE ticket_id::text=?")){statement.setString(1,id);try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertEquals("CONFIG_PENDING",rows.getString(1));assertEquals(error.path("question_ids"),mapper.readTree(rows.getString(2)));JsonNode payload=mapper.readTree(rows.getString(3));assertEquals(id,payload.path("ticket_id").asText());assertFalse(payload.has("description"));assertFalse(payload.has("contact_phone"));assertNull(rows.getTimestamp(4));}}
    }

    @Test void tenantReporterAndAssetScopeAreEnforcedInsideTheRepository(){
        Object accepted=register(report("虚构权限隔离 "+UUID.randomUUID(),null));Map<String,Object> ticket=ticket(accepted,customer,TENANT);String id=ticket.get("id").toString(),operation=operationId(accepted);
        assertTrue(repository.ticket(TENANT,sameTenantCustomer,id).isEmpty());assertTrue(repository.operation(TENANT,sameTenantCustomer,operation).isEmpty());assertTrue(repository.ticket(OTHER,otherCustomer,id).isEmpty());assertTrue(repository.operation(OTHER,otherCustomer,operation).isEmpty());
        assertTrue(repository.ticket(TENANT,admin,id).isPresent());assertTrue(repository.operation(TENANT,engineer,operation).isPresent());assertTrue(repository.tickets(TENANT,sameTenantCustomer,null,null,id,null,null,15).isEmpty());
        String foreignAsset=repository.list(OTHER).get(0).id();assertEquals(404,assertThrows(DomainException.class,()->register(repository,customer,TENANT,report("虚构跨租户资产",foreignAsset),UUID.randomUUID().toString(),"2".repeat(64))).status);
        for(String internal:List.of(admin,engineer))assertEquals(403,assertThrows(DomainException.class,()->register(repository,internal,TENANT,report("不得冒充客户",null),UUID.randomUUID().toString(),"2".repeat(64))).status);
    }

    @Test void concurrentSameHttpKeyCommitsOneIntentAndPayloadConflictCannotOverwrite()throws Exception{
        String requestKey=UUID.randomUUID().toString();CustomerReport report=report("虚构并发HTTP幂等 "+requestKey,null);ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Object>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(threads.submit(()->{start.await();return register(new PostgresPlatformRepository(pool,mapper),customer,TENANT,report,requestKey,"3".repeat(64));}));start.countDown();Object first=tasks.get(0).get(20,TimeUnit.SECONDS),second=tasks.get(1).get(20,TimeUnit.SECONDS);assertEquals(httpJson(first),httpJson(second));Map<String,Object> ticket=ticket(first,customer,TENANT);assertSingleRegistration(ticket);assertEquals(httpJson(first),httpJson(register(new PostgresPlatformRepository(pool,mapper),customer,TENANT,report,requestKey,"3".repeat(64))));assertEquals(409,assertThrows(DomainException.class,()->register(repository,customer,TENANT,report("修改内容",null),requestKey,"4".repeat(64))).status);assertEquals(report.description(),repository.ticket(TENANT,customer,ticket.get("id").toString()).orElseThrow().get("description"));}finally{threads.shutdownNow();}
    }

    @Test void thirtySecondKnownAssetDedupAcrossDifferentHttpKeysPreservesTheOriginalReport()throws Exception{
        String asset=freshAsset(),description="虚构30秒语义去重 "+UUID.randomUUID();CustomerReport original=report(description,asset);ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Object>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(threads.submit(()->{start.await();return register(new PostgresPlatformRepository(pool,mapper),customer,TENANT,original,UUID.randomUUID().toString(),"5".repeat(64));}));start.countDown();Object first=tasks.get(0).get(20,TimeUnit.SECONDS),second=tasks.get(1).get(20,TimeUnit.SECONDS);assertEquals(httpJson(first),httpJson(second));Map<String,Object> ticket=ticket(first,customer,TENANT);assertSingleRegistration(ticket);
            CustomerReport changed=new CustomerReport("新标题不会覆盖", "新说明不会覆盖","网络不通","individual","另一联系人","9999","normal",asset);assertEquals(httpJson(first),httpJson(register(repository,customer,TENANT,changed,UUID.randomUUID().toString(),"6".repeat(64))));Map<String,Object> actual=repository.ticket(TENANT,customer,ticket.get("id").toString()).orElseThrow();assertEquals(description,actual.get("description"));assertEquals(original.contactName(),actual.get("contact_name"));assertEquals(original.contactPhone(),actual.get("contact_phone"));assertEquals("urgent",actual.get("urgency"));assertSingleRegistration(actual);
            Object separateActor=register(repository,sameTenantCustomer,TENANT,original,UUID.randomUUID().toString(),"5".repeat(64));assertNotEquals(operationId(first),operationId(separateActor));assertTrue(repository.operation(TENANT,customer,operationId(separateActor)).isEmpty());
            Object unknownFirst=register(report("设备不确定语义仍待规则",null)),unknownSecond=register(report("设备不确定语义仍待规则",null));assertNotEquals(operationId(unknownFirst),operationId(unknownSecond));
        }finally{threads.shutdownNow();}
    }

    @Test void failureAfterAllBusinessWritesRollsBackTicketHistoryOperationOutboxAuditAndReplay()throws Exception{
        String key=UUID.randomUUID().toString(),trace=UUID.randomUUID().toString();CustomerReportRegistration[] attempted=new CustomerReportRegistration[1];
        assertThrows(IllegalStateException.class,()->repository.idempotent(customer,TENANT,ROUTE,key,"7".repeat(64),()->{attempted[0]=repository.registerCustomerReport(TENANT,customer,report("虚构整笔回滚",null));repository.audit(customer,TENANT,"integration.ticket.rollback","ticket",attempted[0].ticketId(),"SUCCEEDED","MUST_ROLL_BACK",trace);throw new IllegalStateException("intentional rollback");}));
        String id=attempted[0].ticketId();assertEquals(0,count("mop_ticket","id",id));assertEquals(0,count("mop_ticket_history","ticket_id",id));assertEquals(0,count("mop_operation","ticket_id",id));assertEquals(0,count("mop_outbox","ticket_id",id));assertTrue(repository.audits(TENANT).stream().noneMatch(row->trace.equals(row.get("trace_id"))));
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public.mop_idempotency WHERE actor_id::text=? AND tenant_id::text=? AND route=? AND request_key=?")){statement.setString(1,customer);statement.setString(2,TENANT);statement.setString(3,ROUTE);statement.setString(4,key);try(ResultSet rows=statement.executeQuery()){rows.next();assertEquals(0,rows.getInt(1));}}
        Object retry=register(repository,customer,TENANT,report("虚构重试真实受理",null),key,"8".repeat(64));assertNotEquals(attempted[0].operationId(),operationId(retry));assertSingleRegistration(ticket(retry,customer,TENANT));
    }

    @Test void currentMembershipRevocationRejectsReadsAndDurableReplayThenRestoresOnlyTestMembership()throws Exception{
        String key=UUID.randomUUID().toString();CustomerReport report=report("虚构撤权重放 "+key,null);Object accepted=register(repository,customer,TENANT,report,key,"9".repeat(64));String id=ticket(accepted,customer,TENANT).get("id").toString();
        try{
            try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id::text=? AND user_id::text=?")){statement.setString(1,TENANT);statement.setString(2,customer);assertEquals(1,statement.executeUpdate());}
            assertEquals(401,assertThrows(DomainException.class,()->repository.ticket(TENANT,customer,id)).status);assertEquals(401,assertThrows(DomainException.class,()->repository.operation(TENANT,customer,operationId(accepted))).status);assertEquals(401,assertThrows(DomainException.class,()->repository.idempotent(customer,TENANT,ROUTE,key,"9".repeat(64),()->"must not replay")).status);
        }finally{try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id::text=? AND user_id::text=?")){statement.setString(1,TENANT);statement.setString(2,customer);assertEquals(1,statement.executeUpdate());}}
        assertEquals(httpJson(accepted),httpJson(register(repository,customer,TENANT,report,key,"9".repeat(64))));
    }

    @Test void keysetQueriesProjectOnlyVisibleReportsAndSingleConnectionReadsHistory()throws Exception{
        String marker="虚构分页 %_ "+UUID.randomUUID();for(int i=0;i<3;i++)register(report(marker+" "+i,null));register(repository,sameTenantCustomer,TENANT,report(marker+" 他人",null),UUID.randomUUID().toString(),"b".repeat(64));
        List<Map<String,Object>> first=repository.tickets(TENANT,customer,"网络不通","urgent",marker,null,null,2);assertEquals(2,first.size());Map<String,Object> last=first.get(1);List<Map<String,Object>> after=repository.tickets(TENANT,customer,"网络不通","urgent",marker,Instant.parse(last.get("created_at").toString()),last.get("id").toString(),2);assertEquals(1,after.size());assertNotEquals(first.get(0).get("id"),after.get(0).get("id"));assertEquals(4,repository.tickets(TENANT,admin,null,null,marker,null,null,15).size());
        try(HikariDataSource single=pool(1)){PostgresPlatformRepository reader=new PostgresPlatformRepository(single,mapper);assertEquals(3,reader.tickets(TENANT,customer,null,null,marker,null,null,15).size());assertEquals(httpJson(last),httpJson(reader.ticket(TENANT,customer,last.get("id").toString()).orElseThrow()));}
    }

    @Test void runtimeCannotModifyTicketPiiOrHistoryOrMutatePendingOperationsAndOutbox()throws Exception{
        Map<String,Object> ticket=ticket(register(report("虚构权限最小化 "+UUID.randomUUID(),null)),customer,TENANT);
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT has_table_privilege(current_user,?,'UPDATE'),has_table_privilege(current_user,?,'DELETE')")){
            for(String table:List.of("mop_ticket","mop_ticket_history","mop_operation","mop_outbox")){statement.setString(1,"public."+table);statement.setString(2,"public."+table);try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertFalse(rows.getBoolean(1));assertFalse(rows.getBoolean(2));}}
        }
        try(Connection connection=pool.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_ticket SET contact_phone='9999' WHERE tenant_id::text=? AND id::text=?")){statement.setString(1,TENANT);statement.setString(2,ticket.get("id").toString());assertEquals("42501",assertThrows(SQLException.class,statement::executeUpdate).getSQLState());}
        assertEquals(ticket.get("contact_phone"),repository.ticket(TENANT,customer,ticket.get("id").toString()).orElseThrow().get("contact_phone"));
    }
}
