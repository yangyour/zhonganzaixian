package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.notification.TicketNotificationRepository;
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

/** Real designated-PG verification: outbox facts become intents plus recipient-owned in-app records exactly once. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_TICKET_NOTIFICATION_TESTS",matches="true")
class PostgresTicketNotificationIntegrationTest {
    static HikariDataSource pool; static PostgresPlatformRepository platform; static PostgresTicketWorkflowRepository workflow; static PostgresTicketNotificationRepository notifications;
    static final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules(); static final String TENANT="55000000-0000-4000-8000-000000000001";
    static String viewer,msp,other; static long viewerVersion,mspVersion,otherVersion;
    @BeforeAll static void connect(){
        var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);pool=new HikariDataSource(cfg);
        platform=new PostgresPlatformRepository(pool,mapper);workflow=new PostgresTicketWorkflowRepository(pool,platform,mapper);notifications=new PostgresTicketNotificationRepository(pool,platform,mapper);
        assertEquals(List.of(),workflow.readinessProblems());assertEquals(List.of(),notifications.readinessProblems());
        viewer=platform.users().stream().filter(u->Set.of("viewer","tadmin").contains(platform.membership(u.id(),TENANT).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        var internals=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(Membership::active).map(Membership::role).orElse("").equals("msp")).map(FixtureUser::id).toList();
        msp=internals.get(0);
        viewerVersion=platform.membership(viewer,TENANT).orElseThrow().permissionVersion();mspVersion=platform.membership(msp,TENANT).orElseThrow().permissionVersion();
        other=platform.users().stream().filter(u->!u.id().equals(viewer)&&Set.of("viewer","tadmin").contains(platform.membership(u.id(),TENANT).filter(Membership::active).map(Membership::role).orElse(""))).map(FixtureUser::id).findFirst().orElse(null);
        if(other!=null)otherVersion=platform.membership(other,TENANT).orElseThrow().permissionVersion();
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static TicketWorkflowRepository.Access wf(String actor,long version){return new TicketWorkflowRepository.Access(TENANT,actor,version,UUID.randomUUID().toString());}
    static TicketNotificationRepository.Access nf(String actor,long version){return new TicketNotificationRepository.Access(TENANT,actor,version,UUID.randomUUID().toString());}
    static String register(){
        String key="notification-integration-"+UUID.randomUUID(),text="SYNTHETIC_D11_NOTIFICATION_"+UUID.randomUUID();
        var report=new CustomerReport("D11 synthetic notification",text,"其他","individual","Synthetic reporter","13800138000","normal",null);
        return ((CustomerReportRegistration)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/notification-registration",key,"a".repeat(64),viewerVersion,()->{},()->platform.registerCustomerReport(TENANT,viewer,report))).ticketId();
    }
    static Object internal(String ticket,String route,java.util.function.Supplier<Object> work){var a=wf(msp,mspVersion);return platform.idempotentAuthorized(msp,TENANT,route+ticket,UUID.randomUUID().toString(),"b".repeat(64),mspVersion,()->workflow.authorizeCommand(a,ticket,false),work);}
    static int drain(){int total=0,n;do{n=notifications.relay(TENANT,100);total+=n;}while(n==100);return total;}

    @Test void outboxFactsBecomeIntentsAndRecipientOwnedMessagesExactlyOnce(){
        drain();
        String ticket=register();var a=wf(msp,mspVersion);
        internal(ticket,"POST /integration/notification/activate/",()->workflow.activate(a,ticket,0,"P2",Tier.STANDARD,"SYNTHETIC_D11_ACTIVATE",(x,p,t,start)->TicketSlaSnapshot.freeze("P2",Tier.STANDARD,start,Duration.ofMinutes(30),Duration.ofHours(4),null,48L,null,List.of(),Map.of())));
        internal(ticket,"POST /integration/notification/accept/",()->workflow.transition(a,ticket,new WorkflowLifecycle.Command(Action.ACCEPT,1,null,"accepted",null,false)));
        internal(ticket,"POST /integration/notification/start/",()->workflow.transition(a,ticket,new WorkflowLifecycle.Command(Action.START,2,null,"started",null,false)));
        internal(ticket,"POST /integration/notification/resolve/",()->workflow.transition(a,ticket,new WorkflowLifecycle.Command(Action.RESOLVE,3,null,"已核对现场网络线路并更换故障设备完成连通性验证，等待客户复核。",null,false)));
        int outbox=count("SELECT count(*) FROM public.mop_ticket_workflow_outbox WHERE tenant_id=?::uuid AND ticket_id=?::uuid",TENANT,ticket);
        assertEquals(4,outbox);
        assertTrue(drain()>=outbox);assertEquals(0,notifications.relay(TENANT,100));
        assertEquals(outbox,count("SELECT count(*) FROM public.mop_notification_intent WHERE tenant_id=?::uuid AND ticket_id=?::uuid",TENANT,ticket));
        assertEquals(1,count("SELECT count(*) FROM public.mop_notification_intent WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND event_type='ticket.opened' AND recipient_status='PARTIAL' AND pending_reasons @> '[\"Q15_ONCALL_QUEUE_RECIPIENTS\"]'",TENANT,ticket));
        assertEquals(0,count("SELECT count(*) FROM public.mop_notification_intent WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND external_dispatch<>'NOT_CONFIGURED'",TENANT,ticket));
        assertEquals(4,count("SELECT count(*) FROM public.mop_notification_message WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND recipient_id=?::uuid AND audience='CUSTOMER'",TENANT,ticket,viewer));
        assertEquals(0,count("SELECT count(*) FROM public.mop_notification_message WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND recipient_id<>?::uuid",TENANT,ticket,viewer));

        var customer=nf(viewer,viewerVersion);var page=notifications.messages(customer,50,null,null,true);
        var resolved=page.items().stream().filter(m->m.ticketId().equals(ticket)&&m.eventType().equals("ticket.resolved")).findFirst().orElseThrow();
        assertEquals("CUSTOMER",resolved.audience());assertNull(resolved.readAt());assertFalse(resolved.body().contains(msp));
        var read=(TicketNotificationRepository.Message)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/notification/read/"+resolved.id(),UUID.randomUUID().toString(),"c".repeat(64),viewerVersion,()->notifications.authorizeRead(customer,resolved.id()),()->notifications.markRead(customer,resolved.id(),0));
        assertEquals(1,read.version());assertNotNull(read.readAt());
        var again=(TicketNotificationRepository.Message)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/notification/read-again/"+resolved.id(),UUID.randomUUID().toString(),"d".repeat(64),viewerVersion,()->notifications.authorizeRead(customer,resolved.id()),()->notifications.markRead(customer,resolved.id(),1));
        assertEquals(read.readAt(),again.readAt());
        assertEquals(409,assertThrows(DomainException.class,()->platform.idempotentAuthorized(viewer,TENANT,"POST /integration/notification/read-stale/"+resolved.id(),UUID.randomUUID().toString(),"e".repeat(64),viewerVersion,()->notifications.authorizeRead(customer,resolved.id()),()->notifications.markRead(customer,resolved.id(),0))).status);

        var foreign=new TicketNotificationRepository.Access("55000000-0000-4000-8000-000000000002",viewer,viewerVersion,UUID.randomUUID().toString());
        assertTrue(Set.of(401,404).contains(assertThrows(DomainException.class,()->platform.idempotentAuthorized(viewer,"55000000-0000-4000-8000-000000000002","POST /integration/notification/foreign/"+resolved.id(),UUID.randomUUID().toString(),"f".repeat(64),viewerVersion,()->notifications.authorizeRead(foreign,resolved.id()),()->notifications.markRead(foreign,resolved.id(),1))).status));
        if(other!=null){var stranger=nf(other,otherVersion);assertEquals(404,assertThrows(DomainException.class,()->platform.idempotentAuthorized(other,TENANT,"POST /integration/notification/stranger/"+resolved.id(),UUID.randomUUID().toString(),"g".repeat(64),otherVersion,()->notifications.authorizeRead(stranger,resolved.id()),()->notifications.markRead(stranger,resolved.id(),1))).status);
            assertTrue(notifications.messages(stranger,50,null,null,false).items().stream().noneMatch(m->m.ticketId().equals(ticket)));}

        var ledger=notifications.intents(nf(msp,mspVersion),100,null,null);
        assertTrue(ledger.items().stream().anyMatch(i->i.ticketId().equals(ticket)&&i.eventType().equals("ticket.opened")&&i.externalBlockers().containsAll(List.of("Q14_CHANNEL_MATRIX","Q18_PROVIDER_CREDENTIALS"))));
        assertEquals(403,assertThrows(DomainException.class,()->notifications.intents(customer,10,null,null)).status);
    }

    @Test void guardsRejectForgedOrMutatedRecords(){
        String ticket=register();var a=wf(msp,mspVersion);
        internal(ticket,"POST /integration/notification/activate-guard/",()->workflow.activate(a,ticket,0,"P3",Tier.BASIC,"SYNTHETIC_D11_GUARD",(x,p,t,start)->TicketSlaSnapshot.freeze("P3",Tier.BASIC,start,Duration.ofHours(4),null,null,48L,null,List.of(),Map.of("recovery","SYNTHETIC_UNSET"))));
        drain();
        String message=one("SELECT id::text FROM public.mop_notification_message WHERE tenant_id=?::uuid AND ticket_id=?::uuid LIMIT 1",TENANT,ticket);
        String intent=one("SELECT id::text FROM public.mop_notification_intent WHERE tenant_id=?::uuid AND ticket_id=?::uuid LIMIT 1",TENANT,ticket);
        assertThrows(SQLException.class,()->exec("UPDATE public.mop_notification_message SET version=1,read_at=created_at-interval '1 second' WHERE id=?::uuid",message));
        assertThrows(SQLException.class,()->exec("UPDATE public.mop_notification_intent SET recipient_count=recipient_count WHERE id=?::uuid",intent));
        assertThrows(SQLException.class,()->exec("DELETE FROM public.mop_notification_message WHERE id=?::uuid",message));
        assertThrows(SQLException.class,()->exec("INSERT INTO public.mop_notification_message(id,tenant_id,intent_id,ticket_id,recipient_id,audience,title,body,created_at) SELECT gen_random_uuid(),tenant_id,id,ticket_id,?::uuid,'INTERNAL','forged','forged',created_at FROM public.mop_notification_intent WHERE id=?::uuid",msp,intent));
        assertEquals(1,count("SELECT count(*) FROM public.mop_notification_intent WHERE tenant_id=?::uuid AND ticket_id=?::uuid",TENANT,ticket));
    }

    static void exec(String sql,String... args)throws SQLException{try(Connection c=pool.getConnection();var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setString(i+1,args[i]);s.executeUpdate();}}
    static String one(String sql,String... args){try(Connection c=pool.getConnection();var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setString(i+1,args[i]);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}catch(SQLException e){throw new AssertionError(e);}}
    static int count(String sql,String... args){try(Connection c=pool.getConnection();var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setString(i+1,args[i]);try(var r=s.executeQuery()){r.next();return r.getInt(1);}}catch(SQLException e){throw new AssertionError(e);}}
}
