package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import java.time.Instant;
import java.util.*;
public class SeedAutoTicketRetry {
 public static void main(String[] ignored)throws Exception{
  if(!"dot-integration-fresh-pg17-11".equals(System.getenv("MOP_TEST_ENVIRONMENT_NAME"))||!"jdbc:postgresql://127.0.0.1:25432/mop_test".equals(System.getenv("SPRING_DATASOURCE_URL")))throw new IllegalStateException("Disposable environment required");
  var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
  try(var source=new HikariDataSource(cfg)){
   var mapper=new ObjectMapper().findAndRegisterModules();var p=new PostgresPlatformRepository(source,mapper);var e=new PostgresMonitoringEventCenterRepository(source,p);String tenant="55000000-0000-4000-8000-000000000001";
   String actor=p.users().stream().filter(u->p.membership(u.id(),tenant).map(m->m.role().equals("msp")).orElse(false)).findFirst().orElseThrow().id();String asset;
   try(var c=source.getConnection();var s=c.prepareStatement("SELECT id::text FROM public.mop_asset WHERE tenant_id=?::uuid ORDER BY id LIMIT 1")){s.setString(1,tenant);try(var rows=s.executeQuery()){if(!rows.next())throw new IllegalStateException();asset=rows.getString(1);}}
   var a=new Access(tenant,actor,p.membership(actor,tenant).orElseThrow().permissionVersion(),"synthetic-worker-retry");String rule="SYNTH_PROCESS_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
   var event=(EventSummary)p.idempotentAuthorized(actor,tenant,"POST /qa/retry-worker/"+rule,UUID.randomUUID().toString(),"a".repeat(64),a.permissionVersion(),()->e.authorizeWrite(a),()->e.ingestEvent(a,new IngestEventCommand(asset,rule,"SYNTHETIC_WORKER_RETRY",Severity.DISASTER,MonitorState.PROBLEM,rule,Instant.now(),true)));
   try(var c=source.getConnection();var s=c.prepareStatement("UPDATE public.mop_autoticket_attempt SET version=version+1,next_retry_at=clock_timestamp()-interval '1 second',updated_at=clock_timestamp() WHERE tenant_id=?::uuid AND event_id=?::uuid AND status='RETRY_SCHEDULED'")){s.setString(1,tenant);s.setString(2,event.id());if(s.executeUpdate()!=1)throw new IllegalStateException();}
   var attempt=e.eventDetail(a,event.id()).autoTicketAttempt().orElseThrow();System.out.println("SEED_RESULT="+mapper.writeValueAsString(Map.of("tenant_id",tenant,"actor_id",actor,"event_id",event.id(),"attempt_id",attempt.id(),"attempt_version",attempt.version(),"scope","SYNTHETIC_REGISTRATION_ONLY")));
  }
 }
}
