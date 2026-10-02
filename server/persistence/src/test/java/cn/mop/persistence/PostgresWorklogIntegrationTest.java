package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.worklog.*;
import cn.mop.domain.worklog.WorklogRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real runtime-role PG, V8 explicitly pre-applied. No identity creation, role mutation, migration or external provider. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresWorklogIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;static ObjectMapper mapper;static PostgresPlatformRepository platform;static PostgresWorklogRepository worklogs;static String engineer,viewer,admin;
    @BeforeAll static void connect(){
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);platform=new PostgresPlatformRepository(pool,mapper);worklogs=new PostgresWorklogRepository(pool,platform);
        engineer=actor("msp");viewer=actor("viewer");admin=actor("padmin");assertEquals(List.of(),worklogs.readinessProblems());
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String actor(String role){return platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals(role)).isPresent()).findFirst().orElseThrow().id();}
    static Access access(String actor){return new Access(actor,TENANT,platform.membership(actor,TENANT).orElseThrow().permissionVersion(),UUID.randomUUID().toString());}
    static Access engineer(){return access(engineer);}
    static ManualWorklog facts(int minutes){Instant start=Instant.parse("2026-10-02T00:00:00Z");return new ManualWorklog(ManualWorklog.Kind.REMOTE,start,start.plus(Duration.ofMinutes(minutes)),"虚构实填工时测试");}
    static String ticket(){String key=UUID.randomUUID().toString();var report=new CustomerReport("WORKLOG-TEST-"+key,"虚构工时测试父登记","网络不通","individual","虚构联系人","+00 000","normal",null,List.of());
        var registration=(CustomerReportRegistration)platform.idempotent(viewer,TENANT,"POST /integration/worklog-parent",key,"a".repeat(64),()->{
            var value=platform.registerCustomerReport(TENANT,viewer,report);platform.audit(viewer,TENANT,"integration.worklog_parent.create","ticket",value.ticketId(),"SUCCEEDED","SYNTHETIC_ONLY",key);return value;
        });return registration.ticketId();
    }
    static long ticketVersion(String ticket){return ((Number)platform.ticket(TENANT,engineer,ticket).orElseThrow().get("version")).longValue();}
    static Object command(PostgresPlatformRepository p,Access a,String route,String key,String hash,Supplier<Object> action){return p.idempotent(a.actorId(),a.tenantId(),route,key,hash,a.permissionVersion(),action);}
    static Entry create(String ticket,ManualWorklog facts){Access a=engineer();return (Entry)command(platform,a,"POST /integration/worklogs/"+ticket,UUID.randomUUID().toString(),"a".repeat(64),()->worklogs.create(a,ticket,ticketVersion(ticket),facts));}
    static Entry update(String ticket,Entry before,ManualWorklog facts){Access a=engineer();return (Entry)command(platform,a,"PATCH /integration/worklogs/"+before.id(),UUID.randomUUID().toString(),"b".repeat(64),()->worklogs.update(a,ticket,before.id(),before.version(),facts));}
    static long audits(String id)throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_audit WHERE tenant_id::text=? AND payload->>'resource_type'='worklog' AND payload->>'resource_id'=?")){s.setString(1,TENANT);s.setString(2,id);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    static JsonNode json(Object value)throws Exception{return mapper.readTree(mapper.writeValueAsBytes(value));}

    @Test void createUpdateAndIndependentReadKeepRawFactsAndImmutableHistory()throws Exception{
        String ticket=ticket();Entry first=create(ticket,facts(49*60));assertEquals(1,first.version());assertEquals(engineer,first.ownerId());assertEquals(49*3600000L,first.facts().elapsedMillis());
        Entry second=update(ticket,first,new ManualWorklog(ManualWorklog.Kind.ONSITE,facts(0).startedAt(),facts(0).startedAt(),null));assertEquals(2,second.version());assertEquals(0,second.facts().elapsedMillis());assertEquals(first.createdAt(),second.createdAt());assertEquals(ticket,second.ticketId());
        List<Revision> history=worklogs.historyOwn(engineer(),ticket,first.id(),10,null);assertEquals(List.of(2L,1L),history.stream().map(Revision::version).toList());assertEquals(first.facts(),history.get(1).facts());assertEquals("CREATE",history.get(1).action());assertEquals("UPDATE",history.get(0).action());assertEquals(2,audits(first.id()));
        assertEquals(second,new PostgresWorklogRepository(pool,new PostgresPlatformRepository(pool,mapper)).getOwn(engineer(),ticket,first.id()));
    }
    @Test void worklogMutationsNeverChangeTicketStateSlaOrTicketVersion(){String ticket=ticket();var before=platform.ticket(TENANT,engineer,ticket).orElseThrow();Entry first=create(ticket,facts(30));update(ticket,first,facts(60));assertEquals(before,platform.ticket(TENANT,engineer,ticket).orElseThrow());}
    @Test void bothCreationAndRevisionReplayReturnOriginalJsonWithoutExtraHistoryOrAudit()throws Exception{
        String ticket=ticket(),key=UUID.randomUUID().toString();Access a=engineer();long parentVersion=ticketVersion(ticket);
        Object first=command(platform,a,"POST /integration/worklog-replay",key,"a".repeat(64),()->worklogs.create(a,ticket,parentVersion,facts(30)));
        Object replay=command(platform,a,"POST /integration/worklog-replay",key,"a".repeat(64),()->{throw new AssertionError("Replay must not execute again");});assertEquals(json(first),json(replay));Entry value=(Entry)first;assertEquals(1,audits(value.id()));
        String updateKey=UUID.randomUUID().toString();Object changed=command(platform,a,"PATCH /integration/worklog-replay/"+value.id(),updateKey,"b".repeat(64),()->worklogs.update(a,ticket,value.id(),1,facts(60)));
        Object changedReplay=command(platform,a,"PATCH /integration/worklog-replay/"+value.id(),updateKey,"b".repeat(64),()->{throw new AssertionError("Replay must not execute again");});assertEquals(json(changed),json(changedReplay));assertEquals(2,worklogs.historyOwn(a,ticket,value.id(),10,null).size());assertEquals(2,audits(value.id()));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(DomainException.class,()->command(platform,a,"PATCH /integration/worklog-replay/"+value.id(),updateKey,"c".repeat(64),()->null)).code);
    }
    @Test void parentAndEntryVersionConflictsDoNotWrite(){String ticket=ticket();Access a=engineer();assertEquals(409,assertThrows(DomainException.class,()->command(platform,a,"POST /integration/worklog-conflict",UUID.randomUUID().toString(),"a".repeat(64),()->worklogs.create(a,ticket,ticketVersion(ticket)+1,facts(10)))).status);assertEquals(List.of(),worklogs.listOwn(a,ticket,10,null,null));Entry first=create(ticket,facts(10)),second=update(ticket,first,facts(20));assertEquals(409,assertThrows(DomainException.class,()->update(ticket,first,facts(30))).status);assertEquals(second,worklogs.getOwn(a,ticket,first.id()));}
    @Test void callerMustUseIdempotentTransactionAndEngineerOwnRole(){String ticket=ticket();assertThrows(IllegalStateException.class,()->worklogs.create(engineer(),ticket,1,facts(10)));for(String actor:List.of(viewer,admin)){Access a=access(actor);assertEquals(403,assertThrows(DomainException.class,()->worklogs.listOwn(a,ticket,10,null,null)).status);assertEquals(403,assertThrows(DomainException.class,()->command(platform,a,"POST /integration/forbidden-worklog",UUID.randomUUID().toString(),"a".repeat(64),()->worklogs.create(a,ticket,1,facts(10)))).status);}}
    @Test void wrongPermissionVersionAndUnknownActorCannotReadOrWrite(){String ticket=ticket();Access a=engineer(),stale=new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1,a.traceId());assertEquals(401,assertThrows(DomainException.class,()->worklogs.listOwn(stale,ticket,10,null,null)).status);assertEquals(401,assertThrows(DomainException.class,()->worklogs.listOwn(new Access(UUID.randomUUID().toString(),TENANT,1,"synthetic"),ticket,10,null,null)).status);}
    @Test void foreignParentAndDifferentTicketScopeHideExistingRecords(){String firstTicket=ticket(),secondTicket=ticket();Entry first=create(firstTicket,facts(10));Access a=engineer();assertEquals(404,assertThrows(DomainException.class,()->worklogs.getOwn(a,secondTicket,first.id())).status);assertEquals(404,assertThrows(DomainException.class,()->worklogs.historyOwn(a,secondTicket,first.id(),10,null)).status);assertEquals(404,assertThrows(DomainException.class,()->worklogs.getOwn(a,UUID.randomUUID().toString(),first.id())).status);}
    @Test void historicalOtherOwnerFixtureIsHiddenFromCurrentEngineer()throws Exception{
        String ticket=ticket(),id=UUID.randomUUID().toString();
        // Synthetic ownership fixture only; no viewer account is promoted or allowed through the API.
        try(var c=pool.getConnection()){
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("INSERT INTO public.mop_worklog(id,tenant_id,ticket_id,owner_id,version) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,1)")){s.setString(1,id);s.setString(2,TENANT);s.setString(3,ticket);s.setString(4,viewer);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO public.mop_worklog_revision(tenant_id,worklog_id,version,actor_id,action,kind,started_at,ended_at,note) VALUES (?::uuid,?::uuid,1,?::uuid,'CREATE','REMOTE',?::timestamptz,?::timestamptz,'SYNTHETIC_HISTORICAL_OWNER')")){s.setString(1,TENANT);s.setString(2,id);s.setString(3,viewer);s.setString(4,"2026-10-02T00:00:00Z");s.setString(5,"2026-10-02T01:00:00Z");s.executeUpdate();}c.commit();
        }
        assertEquals(List.of(),worklogs.listOwn(engineer(),ticket,10,null,null));assertEquals(404,assertThrows(DomainException.class,()->worklogs.getOwn(engineer(),ticket,id)).status);
        Access a=engineer();assertEquals(404,assertThrows(DomainException.class,()->command(platform,a,"PATCH /integration/foreign-owned-worklog",UUID.randomUUID().toString(),"a".repeat(64),()->worklogs.update(a,ticket,id,1,facts(20)))).status);
    }
    @Test void downstreamFailureRollsBackEntryRevisionAuditAndReplay()throws Exception{
        String ticket=ticket(),key=UUID.randomUUID().toString();Access a=engineer();String[] created={null};
        assertThrows(IllegalStateException.class,()->command(platform,a,"POST /integration/worklog-rollback",key,"a".repeat(64),()->{Entry value=worklogs.create(a,ticket,1,facts(10));created[0]=value.id();throw new IllegalStateException("synthetic downstream failure");}));
        assertEquals(List.of(),worklogs.listOwn(a,ticket,10,null,null));assertEquals(0,audits(created[0]));Object retry=command(platform,a,"POST /integration/worklog-rollback",key,"b".repeat(64),()->worklogs.create(a,ticket,1,facts(20)));assertEquals(20*60000L,((Entry)retry).facts().elapsedMillis());
    }
    @Test void concurrentRevisionsProduceOneSuccessAndOneConflict()throws Exception{
        String ticket=ticket();Entry first=create(ticket,facts(10));var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{List<Future<Object>> pending=new ArrayList<>();for(int duration:List.of(20,30))pending.add(executor.submit(()->{var p=new PostgresPlatformRepository(pool,mapper);var w=new PostgresWorklogRepository(pool,p);Access a=engineer();ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));try{return command(p,a,"PATCH /integration/worklog-race/"+first.id(),UUID.randomUUID().toString(),"c".repeat(64),()->w.update(a,ticket,first.id(),1,facts(duration)));}catch(DomainException e){return e;}}));assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();List<Object> outcomes=new ArrayList<>();for(var result:pending)outcomes.add(result.get(15,TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter(Entry.class::isInstance).count());assertEquals(1,outcomes.stream().filter(x->x instanceof DomainException e&&e.status==409).count());assertEquals(2,worklogs.getOwn(engineer(),ticket,first.id()).version());assertEquals(2,worklogs.historyOwn(engineer(),ticket,first.id(),10,null).size());assertEquals(2,audits(first.id()));}finally{executor.shutdownNow();}
    }
    @Test void ownListAndImmutableHistoryUseStableBoundedKeysetPaging(){
        String ticket=ticket();for(int i=0;i<12;i++)create(ticket,facts(i));Access a=engineer();var first=worklogs.listOwn(a,ticket,10,null,null);assertEquals(10,first.size());Entry tail=first.get(9);var second=worklogs.listOwn(a,ticket,10,tail.createdAt(),tail.id());assertEquals(2,second.size());assertTrue(second.stream().noneMatch(x->first.stream().anyMatch(y->x.id().equals(y.id()))));
        Entry entry=first.get(0);for(int i=0;i<4;i++)entry=update(ticket,entry,facts(i));var history=worklogs.historyOwn(a,ticket,entry.id(),3,null);assertEquals(List.of(5L,4L,3L),history.stream().map(Revision::version).toList());assertEquals(List.of(2L,1L),worklogs.historyOwn(a,ticket,entry.id(),3,3L).stream().map(Revision::version).toList());
    }
    @Test void protocolBoundaryDatesRoundTripWithoutLegacyCalendarOrPrecisionLoss(){
        String ticket=ticket();var facts=new ManualWorklog(ManualWorklog.Kind.INSPECTION,ManualWorklog.parseInstant("0001-01-01T00:00:00Z"),ManualWorklog.parseInstant("9999-12-31T23:59:59.999Z"),null);Entry entry=create(ticket,facts);assertEquals(facts,worklogs.getOwn(engineer(),ticket,entry.id()).facts());
    }
}
