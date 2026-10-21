package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import java.time.Instant;
import java.util.*;
public class SeedLegacyMonitoringTicket {
 public static void main(String[] ignored)throws Exception{
  if(!"dot-integration-fresh-pg17-11".equals(System.getenv("MOP_TEST_ENVIRONMENT_NAME"))||!"jdbc:postgresql://127.0.0.1:25432/mop_test".equals(System.getenv("SPRING_DATASOURCE_URL")))throw new IllegalStateException("Disposable environment required");
  var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
  try(var source=new HikariDataSource(cfg)){
   var mapper=new ObjectMapper().findAndRegisterModules();var p=new PostgresPlatformRepository(source,mapper);var e=new PostgresMonitoringEventCenterRepository(source,p);String tenant="55000000-0000-4000-8000-000000000001";
   String actor=p.users().stream().filter(u->p.membership(u.id(),tenant).map(m->m.role().equals("msp")).orElse(false)).findFirst().orElseThrow().id();String reporter=p.users().stream().filter(u->p.membership(u.id(),tenant).map(m->m.role().equals("viewer")).orElse(false)).findFirst().orElseThrow().id();String asset;
   try(var c=source.getConnection();var s=c.prepareStatement("SELECT id::text FROM public.mop_asset WHERE tenant_id=?::uuid ORDER BY id LIMIT 1")){s.setString(1,tenant);try(var rows=s.executeQuery()){rows.next();asset=rows.getString(1);}}
   var a=new Access(tenant,actor,p.membership(actor,tenant).orElseThrow().permissionVersion(),"synthetic-legacy-projection");String rule="SYNTH_LEGACY_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
   var event=(EventSummary)p.idempotentAuthorized(actor,tenant,"POST /qa/legacy-ticket/"+rule,UUID.randomUUID().toString(),"a".repeat(64),a.permissionVersion(),()->e.authorizeWrite(a),()->e.ingestEvent(a,new IngestEventCommand(asset,rule,"SYNTHETIC_LEGACY_TICKET",Severity.DISASTER,MonitorState.PROBLEM,rule,Instant.now(),false)));
   var legacy=p.ticket(tenant,actor,event.ticketId()).orElseThrow();var history=(Map<?,?>)((List<?>)legacy.get("history")).get(0);
   if(history.size()!=1||!"AUTO_CREATE_FROM_MONITORING_EVENT".equals(history.get("action")))throw new IllegalStateException("Old producer classpath was not selected");
   var report=new CustomerReport("SYNTHETIC_CUSTOMER_CONTROL","SYNTHETIC_CUSTOMER_CONTROL_NOT_REAL_DATA","其他","individual","Synthetic reporter","13800138000","normal",null);
   var registration=(CustomerReportRegistration)p.idempotentAuthorized(reporter,tenant,"POST /qa/customer-control",UUID.randomUUID().toString(),"b".repeat(64),p.membership(reporter,tenant).orElseThrow().permissionVersion(),()->{},()->p.registerCustomerReport(tenant,reporter,report));
   System.out.println("SEED_RESULT="+mapper.writeValueAsString(Map.of("tenant_id",tenant,"actor_id",actor,"legacy_ticket_id",event.ticketId(),"legacy_operation_id",legacy.get("operation_id"),"customer_ticket_id",registration.ticketId(),"scope","SYNTHETIC_LEGACY_PRODUCER_AND_CUSTOMER_CONTROL")));
  }
 }
}
