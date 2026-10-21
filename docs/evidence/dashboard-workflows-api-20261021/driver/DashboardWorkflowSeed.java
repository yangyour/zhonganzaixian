import cn.mop.domain.*;
import cn.mop.domain.ticket.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
public class DashboardWorkflowSeed {
 static final String T="55000000-0000-4000-8000-000000000001";
 static PostgresPlatformRepository p;static PostgresTicketWorkflowRepository w;static String admin,viewer,otherCustomer;static long av,vv,ov;
 static Object command(String actor,long version,String route,Runnable auth,Supplier<Object> work){return p.idempotentAuthorized(actor,T,route,UUID.randomUUID().toString(),"d".repeat(64),version,auth,work);}
 static void transition(String ticket,WorkflowLifecycle.Action action,long expected,boolean customer){String actor=customer?viewer:admin;long version=customer?vv:av;var a=new TicketWorkflowRepository.Access(T,actor,version,"synthetic-dashboard-seed");command(actor,version,"POST /synthetic/dashboard/"+action,()->w.authorizeCommand(a,ticket,customer),()->w.transition(a,ticket,new WorkflowLifecycle.Command(action,expected,null,"SYNTHETIC local fixture transition",null,false)));}
 public static void main(String[] args)throws Exception{
  if(!"true".equals(System.getenv("MOP_ALLOW_SYNTHETIC_DASHBOARD_SEED")))throw new IllegalStateException("Explicit disposable seed flag required");
  String url=System.getenv("SPRING_DATASOURCE_URL");if(!"jdbc:postgresql://127.0.0.1:25432/mop_test".equals(url))throw new IllegalStateException("Disposable loopback database only");
  var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
  try(var pool=new HikariDataSource(cfg)){
   var mapper=new ObjectMapper().findAndRegisterModules();p=new PostgresPlatformRepository(pool,mapper);w=new PostgresTicketWorkflowRepository(pool,p,mapper);
   admin=p.users().stream().map(FixtureUser::id).filter(id->p.membership(id,T).map(Membership::role).orElse("").equals("padmin")).findFirst().orElseThrow();viewer=p.users().stream().map(FixtureUser::id).filter(id->p.membership(id,T).map(Membership::role).orElse("").equals("viewer")).findFirst().orElseThrow();otherCustomer=p.users().stream().map(FixtureUser::id).filter(id->p.membership(id,T).map(Membership::role).orElse("").equals("tadmin")).findFirst().orElseThrow();ov=p.membership(otherCustomer,T).orElseThrow().permissionVersion();av=p.membership(admin,T).orElseThrow().permissionVersion();vv=p.membership(viewer,T).orElseThrow().permissionVersion();
   var a=new TicketWorkflowRepository.Access(T,admin,av,"synthetic-dashboard-seed");
   for(int i=0;i<13;i++){
    String actor=i==6?otherCustomer:viewer;long version=i==6?ov:vv;var report=new CustomerReport("SYNTHETIC dashboard workflow "+i,"SYNTHETIC_DASHBOARD_"+UUID.randomUUID(),"其他","individual","Synthetic reporter","13800138000","normal",null);
    var registered=(CustomerReportRegistration)command(actor,version,"POST /synthetic/dashboard/report",()->{},()->p.registerCustomerReport(T,actor,report));String ticket=registered.ticketId();if(i==12)continue;
    command(admin,av,"POST /synthetic/dashboard/activate",()->w.authorizeCommand(a,ticket,false),()->w.activate(a,ticket,0,"P2",CustomerConfirmationWindow.Tier.STANDARD,"SYNTHETIC_DASHBOARD_ONLY",(access,priority,tier,start)->TicketSlaSnapshot.freeze(priority,tier,start,Duration.ofMinutes(30),Duration.ofHours(4),null,48L,null,List.of(),Map.of())));
    if(i>=7&&i<=10)transition(ticket,WorkflowLifecycle.Action.ACCEPT,1,false);
    if(i>=8&&i<=10)transition(ticket,WorkflowLifecycle.Action.START,2,false);
    if(i>=9&&i<=10)transition(ticket,WorkflowLifecycle.Action.RESOLVE,3,false);
    if(i==10)transition(ticket,WorkflowLifecycle.Action.CLOSE,4,true);
    if(i==11)transition(ticket,WorkflowLifecycle.Action.CANCEL,1,false);
   }
   System.out.println("SYNTHETIC_DASHBOARD_SEED reports=13 activated=12 open=7 no_providers=true");
  }
 }
}
