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
class PostgresOwnWorklogReaderIntegrationTest {
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
    static String ticket(){String key=UUID.randomUUID().toString();var report=new CustomerReport("OWN_WORKLOG_READER_TEST-"+key,"虚构工时测试父登记","网络不通","individual","虚构联系人","+00 000","normal",null,List.of());
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

    static final OwnWorklogQuery ALL=new OwnWorklogQuery(null,null,null,null);
    static OwnWorklogQuery query(String ticket,ManualWorklog.Kind kind,Instant from,Instant before){return new OwnWorklogQuery(ticket==null?null:UUID.fromString(ticket),kind,from,before);}
    static List<Entry> read(OwnWorklogQuery q){return worklogs.listOwnAcrossTickets(engineer(),q,21,null,null);}
    static ManualWorklog interval(ManualWorklog.Kind kind,Instant from,Instant to){return new ManualWorklog(kind,from,to,"SYNTHETIC_OWN_QUERY_INTERVAL");}
    static List<Long> counts()throws SQLException{var values=new ArrayList<Long>();try(var c=pool.getConnection()){for(String table:List.of("mop_worklog","mop_worklog_revision","mop_ticket","mop_audit","mop_idempotency"))try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM public."+table)){assertTrue(r.next());values.add(r.getLong(1));}}return values;}
    @Test void currentEngineerCanReadOwnCurrentRecordsAcrossTwoParents(){String a=ticket(),b=ticket();var first=create(a,facts(30));var second=create(b,facts(49*60));var rows=read(ALL);assertTrue(rows.contains(first));assertTrue(rows.contains(second));assertTrue(rows.stream().allMatch(v->v.ownerId().equals(engineer)&&v.tenantId().equals(TENANT)));assertEquals(49*3600000L,rows.stream().filter(v->v.id().equals(second.id())).findFirst().orElseThrow().facts().elapsedMillis());}
    @Test void optionalTicketAndKindFiltersRemainExplicit(){String a=ticket(),b=ticket();var remote=create(a,facts(10));var onsite=create(a,interval(ManualWorklog.Kind.ONSITE,facts(0).startedAt(),facts(30).endedAt()));create(b,facts(10));assertEquals(List.of(remote),read(query(a,ManualWorklog.Kind.REMOTE,null,null)));assertEquals(List.of(onsite),read(query(a,ManualWorklog.Kind.ONSITE,null,null)));assertEquals(2,read(query(a,null,null,null)).size());}
    @Test void startWindowIsInclusiveExclusiveAndNeverClipsOrDefaultsDuration(){String t=ticket();Instant from=Instant.parse("2040-03-01T00:00:00Z"),before=from.plusSeconds(3600);var zero=create(t,interval(ManualWorklog.Kind.REMOTE,from,from));var longRecord=create(t,interval(ManualWorklog.Kind.REMOTE,from.plusMillis(1),before.plusSeconds(49*3600)));var excluded=create(t,interval(ManualWorklog.Kind.REMOTE,before,before));create(t,interval(ManualWorklog.Kind.REMOTE,from.minusMillis(1),from.plusSeconds(10)));var rows=read(query(t,null,from,before));assertEquals(2,rows.size());assertTrue(rows.contains(zero));assertTrue(rows.contains(longRecord));assertFalse(rows.contains(excluded));assertEquals(0,zero.facts().elapsedMillis());assertEquals(before.plusSeconds(49*3600),rows.stream().filter(v->v.id().equals(longRecord.id())).findFirst().orElseThrow().facts().endedAt());}
    @Test void filtersUseCurrentRevisionWithoutRepeatingOldFacts(){String t=ticket();var old=create(t,facts(10));Instant from=Instant.parse("2042-01-01T00:00:00Z");var changed=update(t,old,interval(ManualWorklog.Kind.INSPECTION,from,from.plusSeconds(12)));assertEquals(List.of(),read(query(t,ManualWorklog.Kind.REMOTE,null,null)));assertEquals(List.of(changed),read(query(t,ManualWorklog.Kind.INSPECTION,from,from.plusSeconds(1))));assertEquals(2,worklogs.historyOwn(engineer(),t,old.id(),10,null).size());}
    @Test void boundedCrossParentKeysetNeverDuplicatesOwnRows(){String a=ticket(),b=ticket();var ids=new HashSet<String>();for(int i=0;i<24;i++)ids.add(create(i%2==0?a:b,facts(i)).id());var seen=new HashSet<String>();Instant before=null;String id=null;for(int n:List.of(10,10,4)){var rows=worklogs.listOwnAcrossTickets(engineer(),ALL,n,before,id);assertEquals(n,rows.size());for(var v:rows)assertTrue(seen.add(v.id()));var tail=rows.get(rows.size()-1);before=tail.createdAt();id=tail.id();}assertEquals(ids,seen);}
    @Test void currentRoleAndPermissionAreRequiredWithoutAdministratorOverride(){for(String actor:List.of(admin,viewer))assertEquals(403,assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(access(actor),ALL,10,null,null)).status);var a=engineer();assertEquals(401,assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1,a.traceId()),ALL,10,null,null)).status);assertEquals(401,assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(new Access(UUID.randomUUID().toString(),TENANT,1,"unknown"),ALL,10,null,null)).status);}
    @Test void successfulAndEmptyQueriesAppendNoAuditReceiptOrBusinessRows()throws Exception{String t=ticket();create(t,facts(10));var before=counts();read(ALL);read(query(t,null,null,null));read(query(t,ManualWorklog.Kind.COMMUTE,null,null));assertEquals(before,counts());}
    @Test void absentParentIs404ButAnExistingParentWithNoOwnRowsIsEmpty(){String t=ticket();assertEquals(List.of(),read(query(t,null,null,null)));assertEquals(404,assertThrows(DomainException.class,()->read(query(UUID.randomUUID().toString(),null,null,null))).status);}
    @Test void malformedCursorOrTechnicalLimitCannotBroadenRead(){for(int n:List.of(0,22,101))assertEquals(400,assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(engineer(),ALL,n,null,null)).status);assertEquals(400,assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(engineer(),null,10,null,null)).status);assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(engineer(),ALL,10,Instant.now(),null));assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(engineer(),ALL,10,null,UUID.randomUUID().toString()));assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(engineer(),ALL,10,Instant.parse("2026-10-04T00:00:00.000001Z"),UUID.randomUUID().toString()));}
    @Test void independentReadCannotEnterProtectedWriteTransaction()throws Exception{var a=engineer();var before=counts();assertThrows(IllegalStateException.class,()->command(platform,a,"POST /integration/own-reader-nested",UUID.randomUUID().toString(),"f".repeat(64),()->worklogs.listOwnAcrossTickets(a,ALL,10,null,null)));assertEquals(before,counts());}
    @Test void historicalOtherOwnerRowsRemainInvisibleWithoutPromotingThatIdentity()throws Exception{String t=ticket(),id=UUID.randomUUID().toString();try(var c=pool.getConnection()){c.setAutoCommit(false);try(var s=c.prepareStatement("INSERT INTO public.mop_worklog(id,tenant_id,ticket_id,owner_id,version) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,1)")){s.setString(1,id);s.setString(2,TENANT);s.setString(3,t);s.setString(4,viewer);s.executeUpdate();}try(var s=c.prepareStatement("INSERT INTO public.mop_worklog_revision(tenant_id,worklog_id,version,actor_id,action,kind,started_at,ended_at,note) VALUES (?::uuid,?::uuid,1,?::uuid,'CREATE','REMOTE','2040-01-01T00:00:00Z','2040-01-01T01:00:00Z','SYNTHETIC_OWN_READER_OTHER_OWNER')")){s.setString(1,TENANT);s.setString(2,id);s.setString(3,viewer);s.executeUpdate();}c.commit();}assertEquals(List.of(),read(query(t,null,null,null)));assertTrue(read(ALL).stream().noneMatch(v->v.id().equals(id)));assertEquals(403,assertThrows(DomainException.class,()->worklogs.listOwnAcrossTickets(access(viewer),query(t,null,null,null),10,null,null)).status);}
    @Test void realForeignTenantParentIsHidden(){String parent;try(var c=pool.getConnection();var s=c.prepareStatement("SELECT id::text FROM public.mop_ticket WHERE tenant_id=?::uuid ORDER BY id LIMIT 1")){s.setString(1,OTHER);try(var r=s.executeQuery()){assertTrue(r.next());parent=r.getString(1);}}catch(SQLException e){throw new AssertionError("Existing foreign synthetic ticket required",e);}assertEquals(404,assertThrows(DomainException.class,()->read(query(parent,null,null,null))).status);}
}
