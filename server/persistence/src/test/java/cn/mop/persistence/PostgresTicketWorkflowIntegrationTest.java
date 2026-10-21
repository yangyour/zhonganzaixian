package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.WorkflowLifecycle.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MOP_RUN_TICKET_WORKFLOW_TESTS",matches="true")
class PostgresTicketWorkflowIntegrationTest {
    static HikariDataSource pool; static PostgresPlatformRepository platform; static PostgresTicketWorkflowRepository workflow;
    static final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules(); static final String TENANT="55000000-0000-4000-8000-000000000001";
    static String viewer,msp; static long viewerVersion,mspVersion;
    @BeforeAll static void connect(){
        var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);pool=new HikariDataSource(cfg);
        platform=new PostgresPlatformRepository(pool,mapper);workflow=new PostgresTicketWorkflowRepository(pool,platform,mapper);
        assertEquals(List.of(),workflow.readinessProblems());
        viewer=platform.users().stream().filter(u->Set.of("viewer","tadmin").contains(platform.membership(u.id(),TENANT).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        msp=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).map(Membership::role).orElse("").equals("msp")).findFirst().orElseThrow().id();
        viewerVersion=platform.membership(viewer,TENANT).orElseThrow().permissionVersion();mspVersion=platform.membership(msp,TENANT).orElseThrow().permissionVersion();
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static TicketWorkflowRepository.Access access(String actor,long version){return new TicketWorkflowRepository.Access(TENANT,actor,version,UUID.randomUUID().toString());}
    static String register() {
        String key="workflow-integration-"+UUID.randomUUID(),text="SYNTHETIC_D07_WORKFLOW_"+UUID.randomUUID();
        var report=new CustomerReport("D07 synthetic workflow",text,"其他","individual","Synthetic reporter","13800138000","normal",null);
        return ((CustomerReportRegistration)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/ticket-workflow-registration",key,"a".repeat(64),viewerVersion,()->{},()->platform.registerCustomerReport(TENANT,viewer,report))).ticketId();
    }
    static TicketSlaSnapshot policy(Instant started){return TicketSlaSnapshot.freeze("P1",Tier.STANDARD,started,Duration.ofMinutes(10),Duration.ofHours(2),null,48L,null,List.of(),Map.of());}
    static Map<String,Object> command(TicketWorkflowRepository.Access access,String ticket,String route,java.util.function.Supplier<? extends Map<String,Object>> work){return (Map<String,Object>)platform.idempotentAuthorized(access.actorId(),access.tenantId(),route+ticket,UUID.randomUUID().toString(),"b".repeat(64),access.permissionVersion(),()->workflow.authorizeCommand(access,ticket,false),()->work.get());}
    @Test void durableLifecyclePersistsAppendOnlyEventsOutboxAndCustomerProjection(){
        String ticket=register();var internal=access(msp,mspVersion);var customer=access(viewer,viewerVersion);
        var opened=command(internal,ticket,"POST /integration/workflow/activate/",()->workflow.activate(internal,ticket,0,"P1",Tier.STANDARD,"SYNTHETIC_D07_ACTIVATE",(a,p,t,start)->policy(start)));
        assertEquals("OPEN",opened.get("state"));assertEquals("postgresql",opened.get("source"));assertEquals(1L,opened.get("version"));
        var accepted=command(internal,ticket,"POST /integration/workflow/accept/",()->workflow.transition(internal,ticket,new WorkflowLifecycle.Command(Action.ACCEPT,1,null,"accepted",null,false)));assertEquals("ACCEPTED",accepted.get("state"));
        var started=command(internal,ticket,"POST /integration/workflow/start/",()->workflow.transition(internal,ticket,new WorkflowLifecycle.Command(Action.START,2,null,"started",null,false)));assertEquals("IN_PROGRESS",started.get("state"));
        var resolved=command(internal,ticket,"POST /integration/workflow/resolve/",()->workflow.transition(internal,ticket,new WorkflowLifecycle.Command(Action.RESOLVE,3,null,"已核对现场网络线路并更换故障设备完成连通性验证，等待客户复核。",null,false)));assertEquals("RESOLVED",resolved.get("state"));assertNotNull(resolved.get("confirm_due_at"));
        var customerView=workflow.read(customer,ticket);assertEquals("RESOLVED",customerView.get("state"));assertNull(customerView.get("assignee_id"));assertTrue(((List<?>)customerView.get("sla_references")).isEmpty());
        var closed=(Map<String,Object>)platform.idempotentAuthorized(customer.actorId(),customer.tenantId(),"POST /integration/workflow/close/"+ticket,UUID.randomUUID().toString(),"c".repeat(64),customer.permissionVersion(),()->workflow.authorizeCommand(customer,ticket,true),()->workflow.transition(customer,ticket,new WorkflowLifecycle.Command(Action.CLOSE,4,null,"确认已解决",null,false)));assertEquals("CLOSED",closed.get("state"));assertEquals("customer_confirm",closed.get("close_mode"));
        var reopened=(Map<String,Object>)platform.idempotentAuthorized(customer.actorId(),customer.tenantId(),"POST /integration/workflow/reopen/"+ticket,UUID.randomUUID().toString(),"d".repeat(64),customer.permissionVersion(),()->workflow.authorizeCommand(customer,ticket,true),()->workflow.transition(customer,ticket,new WorkflowLifecycle.Command(Action.REOPEN,5,null,"问题仍未解决",null,false)));assertEquals("IN_PROGRESS",reopened.get("state"));assertEquals(1,reopened.get("reopen_count"));assertEquals(resolved.get("response_due_at"),reopened.get("response_due_at"));
        assertEquals(6,count("SELECT count(*) FROM public.mop_ticket_workflow_event WHERE tenant_id=?::uuid AND ticket_id=?::uuid",TENANT,ticket));
        assertTrue(count("SELECT count(*) FROM public.mop_ticket_workflow_outbox WHERE tenant_id=?::uuid AND ticket_id=?::uuid",TENANT,ticket)>=6);
        assertEquals(0,count("SELECT count(*) FROM public.mop_ticket_history WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND version>1",TENANT,ticket));
    }
    @Test void staleVersionAndCrossTenantAreRejectedWithoutAppending(){
        String ticket=register();var internal=access(msp,mspVersion);command(internal,ticket,"POST /integration/workflow/activate-cas/",()->workflow.activate(internal,ticket,0,"P1",Tier.STANDARD,"SYNTHETIC_D07_CAS",(a,p,t,start)->policy(start)));
        assertEquals(409,assertThrows(DomainException.class,()->platform.idempotentAuthorized(internal.actorId(),internal.tenantId(),"POST /integration/workflow/stale/"+ticket,UUID.randomUUID().toString(),"e".repeat(64),internal.permissionVersion(),()->workflow.authorizeCommand(internal,ticket,false),()->workflow.transition(internal,ticket,new WorkflowLifecycle.Command(Action.ACCEPT,2,null,"stale",null,false)))).status);
        String foreignTenant="55000000-0000-4000-8000-000000000002";long foreignVersion=platform.membership(msp,foreignTenant).orElseThrow().permissionVersion();
        var foreign=new TicketWorkflowRepository.Access(foreignTenant,msp,foreignVersion,UUID.randomUUID().toString());assertEquals(404,assertThrows(DomainException.class,()->workflow.read(foreign,ticket)).status);
        assertEquals(1,count("SELECT count(*) FROM public.mop_ticket_workflow_event WHERE tenant_id=?::uuid AND ticket_id=?::uuid",TENANT,ticket));
    }
    static int count(String sql,String... args){try(Connection c=pool.getConnection();var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setString(i+1,args[i]);try(var r=s.executeQuery()){r.next();return r.getInt(1);}}catch(SQLException e){throw new AssertionError(e);}}

    static Map<String,Object> customerCommand(TicketWorkflowRepository.Access a,String ticket,String route,java.util.function.Supplier<? extends Map<String,Object>> work){return (Map<String,Object>)platform.idempotentAuthorized(a.actorId(),a.tenantId(),route+ticket,UUID.randomUUID().toString(),"b".repeat(64),a.permissionVersion(),()->workflow.authorizeCommand(a,ticket,true),()->work.get());}

    @Test void customerCloseWaitsForLinkedMonitoringReview() throws SQLException {
        String ticket=register(),eventId=UUID.randomUUID().toString();var internal=access(msp,mspVersion);var customer=access(viewer,viewerVersion);
        command(internal,ticket,"POST /integration/review-gate/open/",()->workflow.activate(internal,ticket,0,"P1",Tier.STANDARD,"SYNTHETIC_REVIEW_GATE",(a,p,t,start)->policy(start)));
        command(internal,ticket,"POST /integration/review-gate/accept/",()->workflow.transition(internal,ticket,new Command(Action.ACCEPT,1,null,null,null,false)));
        command(internal,ticket,"POST /integration/review-gate/start/",()->workflow.transition(internal,ticket,new Command(Action.START,2,null,null,null,false)));
        command(internal,ticket,"POST /integration/review-gate/resolve/",()->workflow.transition(internal,ticket,new Command(Action.RESOLVE,3,null,"已完成故障定位与修复并确认网络稳定，现请客户核对业务恢复情况。",null,false)));
        try(Connection c=pool.getConnection();var s=c.prepareStatement(
            "INSERT INTO public.mop_monitoring_event(id,tenant_id,asset_id,version,rule_code,rule_name,severity,severity_rank,fingerprint,occurrence_count,monitor_state,handling_state,suppression_state,notification_empty_state,problem_at,last_occurred_at,recovered_at,ticket_id,updated_by,updated_at) "+
            "SELECT ?::uuid,?::uuid,id,1,'SYNTH_REVIEW_GATE','SYNTHETIC_REVIEW_GATE','INFO',1,?,1,'RECOVERED','PENDING_REVIEW','NONE','NORMAL_DISPATCH_ELIGIBLE',date_trunc('milliseconds',now()),date_trunc('milliseconds',now()),date_trunc('milliseconds',now()),?::uuid,?::uuid,date_trunc('milliseconds',now()) FROM public.mop_asset WHERE tenant_id=?::uuid ORDER BY id LIMIT 1")){
            s.setString(1,eventId);s.setString(2,TENANT);s.setString(3,"SYNTH_REVIEW_"+eventId);s.setString(4,ticket);s.setString(5,msp);s.setString(6,TENANT);assertEquals(1,s.executeUpdate());
        }
        var failure=assertThrows(DomainException.class,()->customerCommand(customer,ticket,"POST /integration/review-gate/close/",()->workflow.transition(customer,ticket,new Command(Action.CLOSE,4,null,"确认已解决",null,false))));
        assertEquals(422,failure.status);
        assertEquals("RESOLVED",workflow.read(customer,ticket).get("state"));
        var events=new PostgresMonitoringEventCenterRepository(pool,platform);
        var a=new cn.mop.domain.monitoring.MonitoringEventCenterRepository.Access(TENANT,msp,mspVersion,"SYNTHETIC_REVIEW_GATE");
        var sla=workflow.readSla(customer,ticket,Tier.STANDARD);
        var recoveryClock=(Map<?,?>)sla.get("recovery_clock");
        assertEquals(events.eventDetail(a,eventId).event().recoveredAt().toString(),recoveryClock.get("completed_at"));
        assertEquals("MET",recoveryClock.get("status"));
        platform.idempotentAuthorized(msp,TENANT,"POST /integration/review-gate/event/"+eventId,UUID.randomUUID().toString(),"f".repeat(64),mspVersion,()->events.authorizeWrite(a),
            ()->events.transitionEvent(a,new cn.mop.domain.monitoring.MonitoringEventCenterRepository.TransitionEventCommand(eventId,1,"RESOLVE","已人工确认故障根因并完成设备修复，复测业务网络连续稳定正常")));
        assertEquals("CLOSED",customerCommand(customer,ticket,"POST /integration/review-gate/close-reviewed/",()->workflow.transition(customer,ticket,new Command(Action.CLOSE,4,null,"确认已解决",null,false))).get("state"));
    }
}
