package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.persistence.PostgresWeComRepository.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated PG, synthetic records only. No provider message is sent by this test. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_WECOM_PG_TESTS",matches="true")
class PostgresWeComIntegrationTest {
    static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresWeComRepository repository;
    static final String TENANT="55000000-0000-4000-8000-000000000001",FOREIGN="55000000-0000-4000-8000-000000000002";
    static String admin,viewer;static Access access;
    @BeforeAll static void connect(){var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);pool=new HikariDataSource(cfg);platform=new PostgresPlatformRepository(pool,new ObjectMapper().findAndRegisterModules());repository=new PostgresWeComRepository(pool,platform);assertEquals(List.of(),repository.readinessProblems());admin=platform.users().stream().map(FixtureUser::id).filter(id->platform.membership(id,TENANT).filter(Membership::active).map(Membership::role).orElse("").equals("padmin")).findFirst().orElseThrow();viewer=platform.users().stream().map(FixtureUser::id).filter(id->platform.membership(id,TENANT).filter(Membership::active).map(Membership::role).orElse("").equals("viewer")).findFirst().orElseThrow();access=new Access(TENANT,admin,platform.membership(admin,TENANT).orElseThrow().permissionVersion(),UUID.randomUUID().toString());}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    String corp(){return "ww"+UUID.randomUUID().toString().replace("-","").substring(0,16);}
    Object command(String route,String key,Runnable authorization,Supplier<Object> action){return platform.idempotentAuthorized(admin,TENANT,route,key,"c".repeat(64),access.permissionVersion(),authorization,action);}
    @Test void verifiedBindingsAreExplicitVersionedAndDisableRevokesSessions()throws Exception{
        String corp=corp(),member="SYNTHETIC_"+UUID.randomUUID().toString().replace("-","");
        long oldRevision=revision(viewer);
        var binding=(Map<?,?>)command("PUT /integration/wecom/bind",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,corp,member,0,true));
        assertEquals(viewer,repository.login(TENANT,corp,member.toLowerCase(Locale.ROOT)).orElseThrow().userId());assertTrue(repository.login(FOREIGN,corp,member).isEmpty());
        assertEquals(409,assertThrows(DomainException.class,()->command("PUT /integration/wecom/stale",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,corp,member,0,false))).status);
        command("PUT /integration/wecom/disable",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,corp,member,1,false));
        assertTrue(repository.login(TENANT,corp,member).isEmpty());assertEquals(oldRevision+1,revision(viewer));
        assertThrows(SQLException.class,()->execute("UPDATE public.mop_wecom_binding SET active=true,version=99 WHERE id=?::uuid",binding.get("id")));
        assertThrows(SQLException.class,()->execute("DELETE FROM public.mop_wecom_binding WHERE id=?::uuid",binding.get("id")));
    }
    @Test void enqueueIsIdempotentAndProviderAcceptanceIsNeverDelivered(){
        String corp=corp(),key=UUID.randomUUID().toString();
        Supplier<Object> action=()->repository.enqueueTest(access,corp,1000001,"SYNTHETIC_Member","SYNTHETIC protocol persistence only");
        var first=(Map<?,?>)command("POST /integration/wecom/test",key,()->repository.authorizeSend(access,null),action);
        var repeated=new ObjectMapper().convertValue(command("POST /integration/wecom/test",key,()->repository.authorizeSend(access,null),action),Map.class);
        assertEquals(first.get("id"),repeated.get("id"));assertEquals("QUEUED",first.get("status"));
        var claim=repository.claim(TENANT,corp,1000001).orElseThrow();assertTrue(claim.authorized());assertTrue(repository.claim(TENANT,corp,1000001).isEmpty());
        repository.finish(claim,"ACCEPTED","SYNTHETIC_PROVIDER_ACCEPTANCE",null,0);
        var result=repository.delivery(access,claim.id(),true);assertEquals("ACCEPTED",result.get("status"));assertEquals(2L,result.get("version"));
        assertThrows(DomainException.class,()->repository.finish(claim,"FAILED",null,"SYNTHETIC_LATE_RESULT",1));
        assertTrue(repository.claim(TENANT,corp,1000001).isEmpty());
    }
    @Test void concurrentWorkersClaimOnceAndUnknownIsTerminal()throws Exception{
        String corp=corp();var row=(Map<?,?>)command("POST /integration/wecom/concurrency",UUID.randomUUID().toString(),()->repository.authorizeSend(access,null),()->repository.enqueueTest(access,corp,1000001,"SYNTHETIC_Member","SYNTHETIC unknown outcome"));
        var executor=Executors.newFixedThreadPool(2);try{
            var first=executor.submit(()->repository.claim(TENANT,corp,1000001));var second=executor.submit(()->repository.claim(TENANT,corp,1000001));
            var a=first.get();var b=second.get();assertNotEquals(a.isPresent(),b.isPresent());var claim=a.orElseGet(b::orElseThrow);
            repository.finish(claim,"UNKNOWN",null,"SYNTHETIC_NETWORK_LOSS",null);assertEquals("UNKNOWN",repository.delivery(access,row.get("id").toString(),true).get("status"));assertTrue(repository.claim(TENANT,corp,1000001).isEmpty());
        }finally{executor.shutdownNow();}
    }
    @Test void browserCorrelationRequiresBothSecretsIsSingleUseAndTenantScoped(){
        String state=UUID.randomUUID().toString().replace("-","").repeat(2),browser=UUID.randomUUID().toString().replace("-","").repeat(2),corp=corp();
        repository.createLoginState(state,browser,TENANT,corp,1000001);
        assertFalse(repository.consumeLoginState(state,"a".repeat(64),TENANT,corp,1000001));assertFalse(repository.consumeLoginState(state,browser,FOREIGN,corp,1000001));
        assertTrue(repository.consumeLoginState(state,browser,TENANT,corp,1000001));assertFalse(repository.consumeLoginState(state,browser,TENANT,corp,1000001));
    }
    @Test void ownWorkflowNotificationUsesExplicitBindingAndDeduplicatesAcrossDifferentRequestKeys(){
        String corp=corp(),member="SYNTHETIC_"+UUID.randomUUID().toString().replace("-","");long viewerVersion=platform.membership(viewer,TENANT).orElseThrow().permissionVersion();
        var own=new Access(TENANT,viewer,viewerVersion,UUID.randomUUID().toString());
        command("PUT /integration/wecom/source-bind",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,corp,member,0,true));
        var report=new CustomerReport("SYNTHETIC WeCom notification", "SYNTHETIC_D11_SOURCE_"+UUID.randomUUID(),"其他","individual","Synthetic reporter","13800138000","normal",null);
        var registration=(CustomerReportRegistration)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/wecom/report",UUID.randomUUID().toString(),"d".repeat(64),viewerVersion,()->{},()->platform.registerCustomerReport(TENANT,viewer,report));
        var workflow=new PostgresTicketWorkflowRepository(pool,platform,new ObjectMapper().findAndRegisterModules());var wa=new cn.mop.domain.ticket.TicketWorkflowRepository.Access(TENANT,admin,access.permissionVersion(),UUID.randomUUID().toString());
        command("POST /integration/wecom/activate",UUID.randomUUID().toString(),()->workflow.authorizeCommand(wa,registration.ticketId(),false),()->workflow.activate(wa,registration.ticketId(),0,"P2",cn.mop.domain.ticket.CustomerConfirmationWindow.Tier.STANDARD,"SYNTHETIC_WECOM_SOURCE",(a,p,t,start)->cn.mop.domain.ticket.TicketSlaSnapshot.freeze("P2",cn.mop.domain.ticket.CustomerConfirmationWindow.Tier.STANDARD,start,java.time.Duration.ofMinutes(30),java.time.Duration.ofHours(4),null,48L,null,List.of(),Map.of())));
        var notifications=new PostgresTicketNotificationRepository(pool,platform,new ObjectMapper().findAndRegisterModules());while(notifications.relay(TENANT,100)==100){}
        var page=notifications.messages(new cn.mop.domain.notification.TicketNotificationRepository.Access(TENANT,viewer,viewerVersion,UUID.randomUUID().toString()),50,null,null,false);
        String message=page.items().stream().filter(m->m.ticketId().equals(registration.ticketId())).findFirst().orElseThrow().id();
        Supplier<Object> action=()->repository.enqueueNotification(own,corp,1000001,message);
        var first=(Map<?,?>)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/wecom/own-notification",UUID.randomUUID().toString(),"e".repeat(64),viewerVersion,()->repository.authorizeSend(own,message),action);
        var second=(Map<?,?>)platform.idempotentAuthorized(viewer,TENANT,"POST /integration/wecom/own-notification",UUID.randomUUID().toString(),"e".repeat(64),viewerVersion,()->repository.authorizeSend(own,message),action);
        assertEquals(first.get("id"),second.get("id"));assertEquals(message,first.get("source_message_id"));assertEquals(member,first.get("recipient"));
        command("PUT /integration/wecom/source-disable",UUID.randomUUID().toString(),()->repository.authorizeBinding(access,viewer),()->repository.bind(access,viewer,corp,member,1,false));
        var claim=repository.claim(TENANT,corp,1000001).orElseThrow();assertFalse(claim.authorized());repository.finish(claim,"CANCELLED",null,"AUTHORIZATION_OR_BINDING_CHANGED",null);
    }
    @Test void otherTenantAndOtherRecipientCannotReadDelivery(){
        String corp=corp();var row=(Map<?,?>)command("POST /integration/wecom/access",UUID.randomUUID().toString(),()->repository.authorizeSend(access,null),()->repository.enqueueTest(access,corp,1000001,"SYNTHETIC_Member","SYNTHETIC access"));
        var stranger=new Access(TENANT,viewer,platform.membership(viewer,TENANT).orElseThrow().permissionVersion(),UUID.randomUUID().toString());assertEquals(404,assertThrows(DomainException.class,()->repository.delivery(stranger,row.get("id").toString(),false)).status);
        var foreign=new Access(FOREIGN,admin,platform.membership(admin,FOREIGN).orElseThrow().permissionVersion(),UUID.randomUUID().toString());assertEquals(404,assertThrows(DomainException.class,()->repository.delivery(foreign,row.get("id").toString(),true)).status);
        assertEquals(404,assertThrows(DomainException.class,()->repository.delivery(access,row.get("id").toString(),true,corp(),1000001)).status);
        assertEquals(1,repository.deliveries(access,true,corp,1000001).size());assertTrue(repository.deliveries(access,true,corp(),1000001).isEmpty());
        var claim=repository.claim(TENANT,corp,1000001).orElseThrow();repository.finish(claim,"CANCELLED",null,"SYNTHETIC_TEST_FINISHED",null);
    }
    static long revision(String user)throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement("SELECT revision FROM public.mop_identity_security WHERE user_id=?::uuid")){s.setString(1,user);try(var r=s.executeQuery()){assertTrue(r.next());return r.getLong(1);}}}
    static void execute(String sql,Object value)throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement(sql)){s.setObject(1,value);s.executeUpdate();}}
}
