package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.persistence.PostgresEmailRepository.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated PG, synthetic mailboxes under .test only. No SMTP connection or send. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_EMAIL_PG_TESTS",matches="true")
class PostgresEmailIntegrationTest {
    static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresEmailRepository repository;
    static final String TENANT="55000000-0000-4000-8000-000000000001",FOREIGN="55000000-0000-4000-8000-000000000002";
    static String admin,viewer;static Access access;
    @BeforeAll static void connect(){var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);pool=new HikariDataSource(cfg);platform=new PostgresPlatformRepository(pool,new ObjectMapper().findAndRegisterModules());repository=new PostgresEmailRepository(pool,platform);assertEquals(List.of(),repository.readinessProblems());admin=platform.users().stream().map(FixtureUser::id).filter(id->platform.membership(id,TENANT).filter(Membership::active).map(Membership::role).orElse("").equals("padmin")).findFirst().orElseThrow();viewer=platform.users().stream().map(FixtureUser::id).filter(id->platform.membership(id,TENANT).filter(Membership::active).map(Membership::role).orElse("").equals("viewer")).findFirst().orElseThrow();access=new Access(TENANT,admin,platform.membership(admin,TENANT).orElseThrow().permissionVersion(),UUID.randomUUID().toString());}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    String sender(){return "synthetic-"+UUID.randomUUID()+"@example.test";}
    Object command(String route,String key,Runnable authorization,Supplier<Object> action){return platform.idempotentAuthorized(admin,TENANT,route,key,"c".repeat(64),access.permissionVersion(),authorization,action);}
    Map<?,?> enqueue(String sender){return (Map<?,?>)command("POST /integration/email/test",UUID.randomUUID().toString(),()->repository.authorizeSend(access,null),()->repository.enqueueTest(access,sender,"recipient@example.test","SYNTHETIC persistence only","SYNTHETIC no message is sent"));}
    @Test void bindingIsVersionedAndCannotBeDeleted()throws Exception{
        String sender=sender();var binding=(Map<?,?>)command("PUT /integration/email/bind",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,sender,"recipient@example.test",0,true));
        assertEquals(409,assertThrows(DomainException.class,()->command("PUT /integration/email/stale",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,sender,"recipient@example.test",0,false))).status);
        var changed=(Map<?,?>)command("PUT /integration/email/change",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,sender,"changed@example.test",1,true));assertEquals(2L,changed.get("version"));
        assertThrows(SQLException.class,()->execute("DELETE FROM public.mop_email_binding WHERE id=?::uuid",binding.get("id")));
        assertThrows(SQLException.class,()->execute("UPDATE public.mop_email_binding SET version=99 WHERE id=?::uuid",binding.get("id")));
    }
    @Test void idempotencyRetainsOriginalQueuedReceiptAndAcceptanceIsTerminal(){
        String sender=sender(),key=UUID.randomUUID().toString();Supplier<Object> action=()->repository.enqueueTest(access,sender,"recipient@example.test","SYNTHETIC","SYNTHETIC persistence only");
        var first=(Map<?,?>)command("POST /integration/email/idempotent",key,()->repository.authorizeSend(access,null),action);
        var claim=repository.claim(TENANT,sender).orElseThrow();assertTrue(claim.authorized());repository.finish(claim,"ACCEPTED","<synthetic@example.test>",null,250);
        var replay=new ObjectMapper().convertValue(command("POST /integration/email/idempotent",key,()->repository.authorizeSend(access,null),action),Map.class);
        assertEquals(first.get("id"),replay.get("id"));assertEquals("QUEUED",replay.get("status"));assertEquals("ACCEPTED",repository.delivery(access,claim.id(),true,sender).get("status"));assertTrue(repository.claim(TENANT,sender).isEmpty());
        assertThrows(DomainException.class,()->repository.finish(claim,"FAILED",null,"SYNTHETIC_LATE_RESULT",550));assertFalse(first.containsKey("message_text"));
    }
    @Test void concurrentWorkersClaimOnceAndUnknownIsNeverRequeued()throws Exception{
        String sender=sender();enqueue(sender);var executor=Executors.newFixedThreadPool(2);
        try{var a=executor.submit(()->repository.claim(TENANT,sender));var b=executor.submit(()->repository.claim(TENANT,sender));var first=a.get();var second=b.get();assertNotEquals(first.isPresent(),second.isPresent());var claim=first.orElseGet(second::orElseThrow);repository.finish(claim,"UNKNOWN",null,"SYNTHETIC_LOST_RESPONSE",null);assertTrue(repository.claim(TENANT,sender).isEmpty());assertEquals("UNKNOWN",repository.delivery(access,claim.id(),true,sender).get("status"));}finally{executor.shutdownNow();}
    }
    @Test void staleProcessingBecomesUnknownWithoutResend()throws Exception{
        String sender=sender(),id=UUID.randomUUID().toString();
        try(var c=pool.getConnection();var s=c.prepareStatement("INSERT INTO public.mop_email_delivery(id,tenant_id,actor_id,permission_version,sender,recipient,subject,message_text,kind,created_at) VALUES(?::uuid,?::uuid,?::uuid,?,?,?,'SYNTHETIC','SYNTHETIC historical reserved attempt','TEST',clock_timestamp()-interval '5 minutes')")){s.setString(1,id);s.setString(2,TENANT);s.setString(3,admin);s.setLong(4,access.permissionVersion());s.setString(5,sender);s.setString(6,"recipient@example.test");s.executeUpdate();}
        execute("UPDATE public.mop_email_delivery SET status='PROCESSING',version=1,attempt_id=gen_random_uuid(),started_at=clock_timestamp()-interval '3 minutes' WHERE id=?::uuid",id);
        assertTrue(repository.claim(TENANT,sender).isEmpty());assertEquals("UNKNOWN",repository.delivery(access,id,true,sender).get("status"));
    }
    @Test void recipientActorTenantAndSenderAreAllIsolated(){
        String sender=sender();var row=enqueue(sender);String id=row.get("id").toString();
        var other=new Access(TENANT,viewer,platform.membership(viewer,TENANT).orElseThrow().permissionVersion(),UUID.randomUUID().toString());assertEquals(404,assertThrows(DomainException.class,()->repository.delivery(other,id,false,sender)).status);
        var foreign=new Access(FOREIGN,admin,platform.membership(admin,FOREIGN).orElseThrow().permissionVersion(),UUID.randomUUID().toString());assertEquals(404,assertThrows(DomainException.class,()->repository.delivery(foreign,id,true,sender)).status);
        assertEquals(404,assertThrows(DomainException.class,()->repository.delivery(access,id,true,sender())).status);
        var claim=repository.claim(TENANT,sender).orElseThrow();repository.finish(claim,"CANCELLED",null,"SYNTHETIC_FINISHED",null);
    }
    @Test void ownNotificationUsesExplicitBindingDeduplicatesAndRechecksBindingVersion(){
        String sender=sender();long viewerVersion=platform.membership(viewer,TENANT).orElseThrow().permissionVersion();var own=new Access(TENANT,viewer,viewerVersion,UUID.randomUUID().toString());
        command("PUT /integration/email/source-bind",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,sender,"recipient@example.test",0,true));
        var report=new CustomerReport("SYNTHETIC email notification","SYNTHETIC_EMAIL_SOURCE_"+UUID.randomUUID(),"其他","individual","Synthetic reporter","13800138000","normal",null);
        var registration=(CustomerReportRegistration)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/email/report",UUID.randomUUID().toString(),"d".repeat(64),viewerVersion,()->{},()->platform.registerCustomerReport(TENANT,viewer,report));
        var workflow=new PostgresTicketWorkflowRepository(pool,platform,new ObjectMapper().findAndRegisterModules());var wa=new cn.mop.domain.ticket.TicketWorkflowRepository.Access(TENANT,admin,access.permissionVersion(),UUID.randomUUID().toString());
        command("POST /integration/email/activate",UUID.randomUUID().toString(),()->workflow.authorizeCommand(wa,registration.ticketId(),false),()->workflow.activate(wa,registration.ticketId(),0,"P2",cn.mop.domain.ticket.CustomerConfirmationWindow.Tier.STANDARD,"SYNTHETIC_EMAIL_SOURCE",(a,p,t,start)->cn.mop.domain.ticket.TicketSlaSnapshot.freeze("P2",cn.mop.domain.ticket.CustomerConfirmationWindow.Tier.STANDARD,start,java.time.Duration.ofMinutes(30),java.time.Duration.ofHours(4),null,48L,null,List.of(),Map.of())));
        var notifications=new PostgresTicketNotificationRepository(pool,platform,new ObjectMapper().findAndRegisterModules());while(notifications.relay(TENANT,100)==100){}
        var page=notifications.messages(new cn.mop.domain.notification.TicketNotificationRepository.Access(TENANT,viewer,viewerVersion,UUID.randomUUID().toString()),50,null,null,false);
        String message=page.items().stream().filter(m->m.ticketId().equals(registration.ticketId())).findFirst().orElseThrow().id();Supplier<Object> action=()->repository.enqueueNotification(own,sender,message);
        var first=(Map<?,?>)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/email/own",UUID.randomUUID().toString(),"e".repeat(64),viewerVersion,()->repository.authorizeSend(own,message),action);
        var second=(Map<?,?>)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/email/own",UUID.randomUUID().toString(),"e".repeat(64),viewerVersion,()->repository.authorizeSend(own,message),action);assertEquals(first.get("id"),second.get("id"));
        assertEquals("recipient@example.test",first.get("recipient"));assertEquals(message,first.get("source_message_id"));
        command("PUT /integration/email/rebind",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,sender,"changed@example.test",1,true));
        var claim=repository.claim(TENANT,sender).orElseThrow();assertFalse(claim.authorized());repository.finish(claim,"CANCELLED",null,"EMAIL_AUTHORIZATION_OR_BINDING_CHANGED",null);
    }
    static void execute(String sql,Object value)throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement(sql)){s.setObject(1,value);s.executeUpdate();}}
}
