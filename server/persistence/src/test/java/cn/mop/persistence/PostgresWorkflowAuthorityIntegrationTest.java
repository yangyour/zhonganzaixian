package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Requires a fresh, explicitly authorized disposable PostgreSQL database and synthetic grants. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_WORKFLOW_AUTHORITY_TESTS",matches="true")
class PostgresWorkflowAuthorityIntegrationTest {
 static final String TENANT="55000000-0000-4000-8000-000000000001";
 static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresTicketWorkflowRepository legacy;static PostgresTicketLifecycleRepository revision;
 static String actor,viewer;static long actorVersion,viewerVersion;
 @BeforeAll static void connect(){
  assertEquals("dot-integration-fresh-pg17-11",System.getenv("MOP_TEST_ENVIRONMENT_NAME"));
  var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);
  var mapper=new ObjectMapper().findAndRegisterModules();platform=new PostgresPlatformRepository(pool,mapper);legacy=new PostgresTicketWorkflowRepository(pool,platform,mapper);revision=new PostgresTicketLifecycleRepository(pool,platform,mapper,true);
  actor=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).map(m->m.role().equals("msp")&&m.capabilities().contains("tickets:lifecycle_write")).orElse(false)).findFirst().orElseThrow().id();
  viewer=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).map(m->m.role().equals("viewer")).orElse(false)).findFirst().orElseThrow().id();actorVersion=platform.membership(actor,TENANT).orElseThrow().permissionVersion();viewerVersion=platform.membership(viewer,TENANT).orElseThrow().permissionVersion();
  assertEquals(List.of(),legacy.readinessProblems());assertEquals(List.of(),revision.readinessProblems());
 }
 @AfterAll static void close(){if(pool!=null)pool.close();}
 static String register(){
  var report=new CustomerReport("SYNTHETIC_AUTHORITY", "SYNTHETIC_AUTHORITY_"+UUID.randomUUID(),"其他","individual","Synthetic reporter","13800138000","normal",null);
  return ((CustomerReportRegistration)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/authority/register",UUID.randomUUID().toString(),"a".repeat(64),viewerVersion,()->{},()->platform.registerCustomerReport(TENANT,viewer,report))).ticketId();
 }
 static Object openLegacy(String ticket){
  var access=new TicketWorkflowRepository.Access(TENANT,actor,actorVersion,UUID.randomUUID().toString());
  return platform.idempotentAuthorized(actor,TENANT,"POST /integration/authority/legacy/"+ticket,UUID.randomUUID().toString(),"b".repeat(64),actorVersion,()->legacy.authorizeCommand(access,ticket,false),()->legacy.activate(access,ticket,0,"P1",Tier.STANDARD,"SYNTHETIC_AUTHORITY",(a,p,t,start)->TicketSlaSnapshot.freeze("P1",Tier.STANDARD,start,Duration.ofMinutes(10),Duration.ofHours(2),null,48L,null,List.of(),Map.of())));
 }
 static Object openRevision(String ticket){
  var access=new TicketLifecycleRepository.Access(actor,TENANT,actorVersion,UUID.randomUUID().toString());Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
  String policy="{\"enabled\":true,\"configuration_release_ref\":\"SYNTHETIC_TEST_ONLY\",\"service_tier\":\"STANDARD\",\"priority\":\"P1\",\"solution_min_chars\":20,\"confirmation_timeout_hours\":48,\"reopen_window_hours\":24,\"manager_threshold\":3}";
  return platform.idempotentAuthorized(actor,TENANT,"POST /integration/authority/revision/"+ticket,UUID.randomUUID().toString(),"c".repeat(64),actorVersion,()->revision.authorizeCurrentAccess(access,ticket,false),()->revision.start(access,ticket,policy,now.plusSeconds(600),now.plusSeconds(7200)));
 }
 static String model(String ticket)throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement("SELECT model FROM public.mop_ticket_workflow_authority WHERE tenant_id=?::uuid AND ticket_id=?::uuid")){s.setString(1,TENANT);s.setString(2,ticket);try(var r=s.executeQuery()){assertTrue(r.next());String v=r.getString(1);assertFalse(r.next());return v;}}}
 static long count(String table,String ticket)throws SQLException{assertTrue(Set.of("mop_ticket_workflow_event","mop_ticket_lifecycle_revision","mop_ticket_workflow_authority").contains(table));try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public."+table+" WHERE tenant_id=?::uuid AND ticket_id=?::uuid")){s.setString(1,TENANT);s.setString(2,ticket);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
 @Test void legacyTicketCannotAlsoBecomeRevisionTicket()throws Exception{String ticket=register();openLegacy(ticket);var failure=assertThrows(DomainException.class,()->openRevision(ticket));assertEquals(409,failure.status);assertEquals("WORKFLOW_MODEL_CONFLICT",failure.code);assertEquals("WORKFLOW_V33",model(ticket));assertEquals(1,count("mop_ticket_workflow_event",ticket));assertEquals(0,count("mop_ticket_lifecycle_revision",ticket));}
 @Test void revisionTicketCannotAlsoBecomeLegacyTicket()throws Exception{String ticket=register();openRevision(ticket);var failure=assertThrows(DomainException.class,()->openLegacy(ticket));assertEquals(409,failure.status);assertEquals("WORKFLOW_MODEL_CONFLICT",failure.code);assertEquals("REVISION_V42",model(ticket));assertEquals(0,count("mop_ticket_workflow_event",ticket));assertEquals(1,count("mop_ticket_lifecycle_revision",ticket));}
 @Test void simultaneousCreationHasExactlyOneWinnerAndNoSplitHistory()throws Exception{
  String ticket=register();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
  try{
   Callable<Integer> a=()->{start.await();try{openLegacy(ticket);return 200;}catch(DomainException e){return e.status;}};
   Callable<Integer> b=()->{start.await();try{openRevision(ticket);return 200;}catch(DomainException e){return e.status;}};
   var one=executor.submit(a);var two=executor.submit(b);start.countDown();var statuses=new ArrayList<>(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
   assertEquals(1,count("mop_ticket_workflow_event",ticket)+count("mop_ticket_lifecycle_revision",ticket));assertEquals(1,count("mop_ticket_workflow_authority",ticket));
  }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
 }
 @Test void runtimeCannotPreclaimOrRewriteAuthorityDirectly()throws Exception{
  String ticket=register();try(var c=pool.getConnection();var s=c.prepareStatement("INSERT INTO public.mop_ticket_workflow_authority(tenant_id,ticket_id,model) VALUES(?::uuid,?::uuid,'REVISION_V42')")){s.setString(1,TENANT);s.setString(2,ticket);assertThrows(SQLException.class,s::executeUpdate);}
  assertEquals(0,count("mop_ticket_workflow_authority",ticket));openLegacy(ticket);
  try(var c=pool.getConnection();var s=c.prepareStatement("DELETE FROM public.mop_ticket_workflow_authority WHERE tenant_id=?::uuid AND ticket_id=?::uuid")){s.setString(1,TENANT);s.setString(2,ticket);assertThrows(SQLException.class,s::executeUpdate);}
  assertEquals("WORKFLOW_V33",model(ticket));
 }
}
