package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MOP_RUN_AUTOTICKET_RETRY_TESTS",matches="true")
class PostgresAutoTicketRetryIntegrationTest {
 @BeforeAll static void connect()throws Exception{assertEquals("dot-integration-fresh-pg17-11",System.getenv("MOP_TEST_ENVIRONMENT_NAME"));PostgresMonitoringEventCenterIntegrationTest.connect();}
 @AfterAll static void close(){PostgresMonitoringEventCenterIntegrationTest.close();}
 static Access access(){var p=PostgresMonitoringEventCenterIntegrationTest.platform;String actor=PostgresMonitoringEventCenterIntegrationTest.msp,tenant=PostgresMonitoringEventCenterIntegrationTest.TENANT;return new Access(tenant,actor,p.membership(actor,tenant).orElseThrow().permissionVersion(),"synthetic-retry");}
 static EventSummary signal(Access a,String rule,String suffix,MonitorState phase,boolean failure,Instant time){var p=PostgresMonitoringEventCenterIntegrationTest.platform;var e=PostgresMonitoringEventCenterIntegrationTest.events;return (EventSummary)p.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/registration-retry/"+rule+"/"+suffix,UUID.randomUUID().toString(),"a".repeat(64),a.permissionVersion(),()->e.authorizeWrite(a),()->e.ingestEvent(a,new IngestEventCommand(PostgresMonitoringEventCenterIntegrationTest.assetA,rule,"SYNTHETIC_RETRY",Severity.DISASTER,phase,rule+"-"+suffix,time,failure)));}
 static String rule(){return "SYNTH_RETRY_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);}
 static AutoTicketAttempt attempt(Access a,EventSummary event){return PostgresMonitoringEventCenterIntegrationTest.events.eventDetail(a,event.id()).autoTicketAttempt().orElseThrow();}
 static AutoTicketAttempt retry(Access a,AutoTicketAttempt current){var p=PostgresMonitoringEventCenterIntegrationTest.platform;var e=PostgresMonitoringEventCenterIntegrationTest.events;return (AutoTicketAttempt)p.idempotentAuthorized(a.actorId(),a.tenantId(),"WORKER /integration/registration-retry/"+current.id(),UUID.randomUUID().toString(),"b".repeat(64),a.permissionVersion(),()->e.authorizeWrite(a),()->e.retryRegistration(a,current.id(),current.version()));}
 @Test void futureDeadlineAndRepeatedSignalsCannotSpendBudgetThenDueRetryCreatesOnce()throws Exception{
  var a=access();String rule=rule();Instant time=Instant.now().minusSeconds(10);var event=signal(a,rule,"seed",MonitorState.PROBLEM,true,time);var first=attempt(a,event);var e=PostgresMonitoringEventCenterIntegrationTest.events;
  assertFalse(e.dueRegistrationRetries(a,25).stream().anyMatch(v->v.id().equals(first.id())));
  signal(a,rule,"early",MonitorState.PROBLEM,false,time.plusSeconds(1));var afterSignal=attempt(a,event);assertEquals(first.version(),afterSignal.version());assertEquals(1,afterSignal.attemptCount());assertNull(afterSignal.ticketId());
  var before=PostgresAutoTicketCasIntegrationTest.snapshot();assertEquals("AUTOTICKET_NOT_DUE",assertThrows(DomainException.class,()->retry(a,afterSignal)).code);assertEquals(before,PostgresAutoTicketCasIntegrationTest.snapshot());
  PostgresAutoTicketCasIntegrationTest.forceDue(event.id());var due=attempt(a,event);assertTrue(e.dueRegistrationRetries(a,25).stream().anyMatch(v->v.id().equals(due.id())));
  var result=retry(a,due);assertEquals(AutoTicketStatus.SUCCEEDED,result.status());assertEquals(2,result.attemptCount());assertNotNull(result.ticketId());assertNull(result.nextRetryAt());assertFalse(e.dueRegistrationRetries(a,25).stream().anyMatch(v->v.id().equals(due.id())));
  assertEquals(409,assertThrows(DomainException.class,()->retry(a,due)).status);
 }
 @Test void recoveredEventIsSkippedAtExecutionTime()throws Exception{
  var a=access();String rule=rule();Instant time=Instant.now().minusSeconds(10);var event=signal(a,rule,"seed",MonitorState.PROBLEM,true,time);
  signal(a,rule,"recovery",MonitorState.RECOVERED,false,time.plusSeconds(1));PostgresAutoTicketCasIntegrationTest.forceDue(event.id());var r=retry(a,attempt(a,event));assertEquals(AutoTicketStatus.SUPPRESSED_SKIPPED,r.status());assertNull(r.ticketId());assertNull(r.nextRetryAt());
 }
 @Test void maintenanceAddedAfterInitialFailureIsRecheckedBeforeRegistration()throws Exception{
  var a=access();var p=PostgresMonitoringEventCenterIntegrationTest.platform;var e=PostgresMonitoringEventCenterIntegrationTest.events;Instant time=Instant.now();var event=signal(a,rule(),"seed",MonitorState.PROBLEM,true,time.minusSeconds(10));
  var window=(MaintenanceWindow)p.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/retry-maintenance/"+event.id(),UUID.randomUUID().toString(),"c".repeat(64),a.permissionVersion(),()->e.authorizeWrite(a),()->e.createMaintenanceWindow(a,new CreateMaintenanceWindowCommand("SYNTHETIC_RETRY_MAINTENANCE",event.assetId(),time.minusSeconds(1),time.plusSeconds(600),"SUPPRESS_NOTIFY",false,"SYNTHETIC_TEST_ONLY")));
  try{PostgresAutoTicketCasIntegrationTest.forceDue(event.id());var r=retry(a,attempt(a,event));assertEquals(AutoTicketStatus.SUPPRESSED_SKIPPED,r.status());assertNull(r.ticketId());}
  finally{p.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/retry-maintenance-end/"+window.id(),UUID.randomUUID().toString(),"d".repeat(64),a.permissionVersion(),()->e.authorizeWrite(a),()->e.updateMaintenanceWindow(a,new UpdateMaintenanceWindowCommand(window.id(),window.version(),"END",null)));}
 }
 @Test void stalePermissionAndCrossTenantCannotClaimAttempt()throws Exception{
  var a=access();var event=signal(a,rule(),"seed",MonitorState.PROBLEM,true,Instant.now().minusSeconds(10));PostgresAutoTicketCasIntegrationTest.forceDue(event.id());var current=attempt(a,event);var before=PostgresAutoTicketCasIntegrationTest.snapshot();
  assertEquals(401,assertThrows(DomainException.class,()->retry(new Access(a.tenantId(),a.actorId(),a.permissionVersion()+1,a.traceId()),current)).status);assertEquals(before,PostgresAutoTicketCasIntegrationTest.snapshot());
  String other="55000000-0000-4000-8000-000000000002";var member=PostgresMonitoringEventCenterIntegrationTest.platform.membership(a.actorId(),other).orElseThrow();var otherAccess=new Access(other,a.actorId(),member.permissionVersion(),a.traceId());assertEquals(404,assertThrows(DomainException.class,()->retry(otherAccess,current)).status);
  assertEquals(AutoTicketStatus.SUCCEEDED,retry(a,current).status());
 }
 @Test void generatedRegistrationHasCompleteHistoryAndNoInventedContact()throws Exception{
  var a=access();var event=signal(a,rule(),"seed",MonitorState.PROBLEM,true,Instant.now().minusSeconds(10));PostgresAutoTicketCasIntegrationTest.forceDue(event.id());var registered=retry(a,attempt(a,event));
  var ticket=PostgresMonitoringEventCenterIntegrationTest.platform.ticket(a.tenantId(),a.actorId(),registered.ticketId()).orElseThrow();assertEquals("",ticket.get("contact_phone"));assertEquals("UNVERIFIED",ticket.get("contact_binding_status"));
  var history=(Map<?,?>)((List<?>)ticket.get("history")).get(0);assertEquals(Set.of("action","actor_id","occurred_at","version"),history.keySet());assertEquals("MONITORING_REGISTER",history.get("action"));assertEquals(a.actorId(),history.get("actor_id"));assertEquals(ticket.get("created_at"),history.get("occurred_at"));
 }
 @Test void twoConcurrentProcessorsCreateExactlyOneRegistration()throws Exception{
  var a=access();var event=signal(a,rule(),"seed",MonitorState.PROBLEM,true,Instant.now().minusSeconds(10));PostgresAutoTicketCasIntegrationTest.forceDue(event.id());var current=attempt(a,event);var latch=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{Callable<Integer> job=()->{latch.await();try{retry(a,current);return 200;}catch(DomainException e){return e.status;}};var first=pool.submit(job);var second=pool.submit(job);latch.countDown();var statuses=new ArrayList<>(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(AutoTicketStatus.SUCCEEDED,attempt(a,event).status());}
  finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
 }
}
