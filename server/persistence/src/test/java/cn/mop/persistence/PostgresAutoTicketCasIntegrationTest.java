package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import java.lang.reflect.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG transactions with one controlled JDBC CAS miss; not evidence of live concurrent scheduling. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_AUTOTICKET_CAS_TESTS",matches="true")
class PostgresAutoTicketCasIntegrationTest {
 @BeforeAll static void connect()throws Exception{assertEquals("dot-integration-fresh-pg17-11",System.getenv("MOP_TEST_ENVIRONMENT_NAME"));PostgresMonitoringEventCenterIntegrationTest.connect();}
 @AfterAll static void close(){PostgresMonitoringEventCenterIntegrationTest.close();}
 @Test void missingAttemptVersionUpdateRollsBackAllTicketAndEventChanges()throws Exception{check("UPDATE public.mop_autoticket_attempt SET version=");}
 @Test void missingEventTicketLinkUpdateRollsBackAllTicketAndEventChanges()throws Exception{check("UPDATE public.mop_monitoring_event SET version=?,ticket_id=");}
 @Test void concurrentAutomaticRetryAndManualCreationHaveOneDurableTicket()throws Exception{
  var platform=PostgresMonitoringEventCenterIntegrationTest.platform;var events=PostgresMonitoringEventCenterIntegrationTest.events;
  String actor=PostgresMonitoringEventCenterIntegrationTest.msp,tenant=PostgresMonitoringEventCenterIntegrationTest.TENANT,asset=PostgresMonitoringEventCenterIntegrationTest.assetA;
  long version=platform.membership(actor,tenant).orElseThrow().permissionVersion();var access=new Access(tenant,actor,version,"synthetic-concurrent-cas");
  for(int iteration=0;iteration<3;iteration++){
   String rule="SYNTH_RACE_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);Instant now=Instant.now().minusSeconds(30);
   var seed=(EventSummary)platform.idempotentAuthorized(actor,tenant,"POST /integration/race/seed/"+rule,UUID.randomUUID().toString(),"a".repeat(64),version,()->events.authorizeWrite(access),()->events.ingestEvent(access,new IngestEventCommand(asset,rule,"SYNTHETIC RACE",Severity.DISASTER,MonitorState.PROBLEM,rule+"-seed",now,true)));
   var attempt=events.eventDetail(access,seed.id()).autoTicketAttempt().orElseThrow();var latch=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
   try{
    var automatic=executor.submit(()->{latch.await();try{platform.idempotentAuthorized(actor,tenant,"POST /integration/race/auto/"+rule,UUID.randomUUID().toString(),"b".repeat(64),version,()->events.authorizeWrite(access),()->events.ingestEvent(access,new IngestEventCommand(asset,rule,"SYNTHETIC RACE",Severity.DISASTER,MonitorState.PROBLEM,rule+"-retry",now.plusSeconds(1),false)));return 200;}catch(DomainException e){return e.status;}});
    var manual=executor.submit(()->{latch.await();try{platform.idempotentAuthorized(actor,tenant,"POST /integration/race/manual/"+rule,UUID.randomUUID().toString(),"c".repeat(64),version,()->events.authorizeWrite(access),()->events.manualCreateAutoTicket(access,new ManualAutoTicketCommand(attempt.id(),attempt.version(),"SYNTHETIC_CONCURRENT_REVIEW")));return 200;}catch(DomainException e){return e.status;}});
    latch.countDown();var statuses=List.of(automatic.get(20,java.util.concurrent.TimeUnit.SECONDS),manual.get(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(statuses.contains(200));assertTrue(statuses.stream().allMatch(status->status==200||status==409),statuses.toString());
    var after=events.eventDetail(access,seed.id());assertNotNull(after.event().ticketId());assertEquals(after.event().ticketId(),after.autoTicketAttempt().orElseThrow().ticketId());
    try(var c=PostgresMonitoringEventCenterIntegrationTest.pool.getConnection();var q=c.prepareStatement("SELECT count(*) FROM public.mop_ticket WHERE tenant_id=?::uuid AND description LIKE '%'||?||'%'")){q.setString(1,tenant);q.setString(2,seed.id());try(var rows=q.executeQuery()){rows.next();assertEquals(1,rows.getLong(1));}}
   }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
  }
 }
 static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
 static void check(String prefix)throws Exception{
  var pool=PostgresMonitoringEventCenterIntegrationTest.pool;var normal=PostgresMonitoringEventCenterIntegrationTest.platform;var events=PostgresMonitoringEventCenterIntegrationTest.events;
  String actor=PostgresMonitoringEventCenterIntegrationTest.msp,tenant=PostgresMonitoringEventCenterIntegrationTest.TENANT,asset=PostgresMonitoringEventCenterIntegrationTest.assetA;
  long version=normal.membership(actor,tenant).orElseThrow().permissionVersion();var access=new Access(tenant,actor,version,"synthetic-cas");
  String rule="SYNTH_CAS_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);Instant now=Instant.now().minusSeconds(60);
  var initial=(EventSummary)normal.idempotentAuthorized(actor,tenant,"POST /integration/cas/seed/"+rule,UUID.randomUUID().toString(),"a".repeat(64),version,()->events.authorizeWrite(access),()->events.ingestEvent(access,new IngestEventCommand(asset,rule,"SYNTHETIC CAS",Severity.DISASTER,MonitorState.PROBLEM,rule+"-seed",now,true)));
  assertEquals(AutoTicketStatus.RETRY_SCHEDULED,events.eventDetail(access,initial.id()).autoTicketAttempt().orElseThrow().status());
  var hits=new AtomicInteger();
  DataSource injected=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(proxy,method,args)->{
   if(!method.getName().equals("getConnection"))return invoke(pool,method,args);
   Connection real=(Connection)invoke(pool,method,args);
   return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(cp,cm,ca)->{
    if(cm.getName().equals("prepareStatement")&&ca[0] instanceof String sql&&sql.startsWith(prefix)){
     PreparedStatement prepared=(PreparedStatement)invoke(real,cm,ca);
     return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(sp,sm,sa)->{if(sm.getName().equals("executeUpdate")&&hits.getAndIncrement()==0)return 0;return invoke(prepared,sm,sa);});
    }
    return invoke(real,cm,ca);
   });
  });
  var faultedPlatform=new PostgresPlatformRepository(injected,PostgresMonitoringEventCenterIntegrationTest.mapper);var faultedEvents=new PostgresMonitoringEventCenterRepository(injected,faultedPlatform);
  var before=snapshot();DomainException failure=null;
  try{faultedPlatform.idempotentAuthorized(actor,tenant,"POST /integration/cas/retry/"+rule,UUID.randomUUID().toString(),"b".repeat(64),version,()->faultedEvents.authorizeWrite(access),()->faultedEvents.ingestEvent(access,new IngestEventCommand(asset,rule,"SYNTHETIC CAS",Severity.DISASTER,MonitorState.PROBLEM,rule+"-retry",now.plusSeconds(1),false)));}
  catch(DomainException e){failure=e;}
  var observed=failure;
  assertAll(()->assertEquals(1,hits.get(),"exactly one controlled CAS miss"),()->assertNotNull(observed,"CAS miss must not be reported as successful creation"),()->assertEquals(before,snapshot(),"ticket/history/outbox/event/attempt/audit/idempotency changes must all roll back"));
  assertEquals(409,observed.status);assertEquals("VERSION_CONFLICT",observed.code);
 }
 static Map<String,String> snapshot()throws SQLException{
  var result=new LinkedHashMap<String,String>();try(var c=PostgresMonitoringEventCenterIntegrationTest.pool.getConnection()){
   for(String table:List.of("mop_ticket","mop_ticket_history","mop_operation","mop_outbox","mop_monitoring_event","mop_monitoring_event_member","mop_event_noise_record","mop_autoticket_attempt","mop_audit","mop_idempotency")){
    try(var s=c.prepareStatement("SELECT count(*)::text||'|'||md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM public."+table+" t");var rows=s.executeQuery()){rows.next();result.put(table,rows.getString(1));}
   }
  }return result;
 }
}
