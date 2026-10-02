package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.audit.AuditReader.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG current-membership read scope; test setup appends clearly synthetic records through the existing writer. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAuditReaderIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresAuditReader reader;static String admin,viewer,engineer;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(3);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);ObjectMapper mapper=new ObjectMapper();platform=new PostgresPlatformRepository(pool,mapper);reader=new PostgresAuditReader(pool,mapper);admin=actor("padmin");viewer=actor("viewer");engineer=actor("msp");}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String actor(String role){return platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals(role)).isPresent()).findFirst().orElseThrow().id();}
    static Access access(String actor){return new Access(actor,TENANT,platform.membership(actor,TENANT).orElseThrow().permissionVersion());}
    static Filters filters(String action){return new Filters(null,action,null,null,null,null,null,null);}
    static String action(){return "synthetic.audit.reader."+UUID.randomUUID();}
    static void append(String action,String resource,String trace){platform.audit(admin,TENANT,action,"synthetic_audit_reader",resource,"SUCCEEDED","SYNTHETIC_TEST_ONLY",trace);}
    static long sequence(Map<String,Object> row){return ((Number)row.get("sequence")).longValue();}
    static long count()throws SQLException{try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_audit WHERE tenant_id::text=?")){s.setString(1,TENANT);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    @Test void filteredKeysetPinsUpperSnapshotAcrossConcurrentNormalAppend(){
        String action=action(),resource=UUID.randomUUID().toString();for(int i=0;i<12;i++)append(action,resource,UUID.randomUUID().toString());Access a=access(admin);var first=reader.list(a,filters(action),10,null,null);assertEquals(10,first.items().size());assertTrue(first.hasMore());assertNotNull(first.nextBeforeSequence());assertTrue(first.items().stream().allMatch(row->row.get("tenant_id").equals(TENANT)&&row.get("action").equals(action)));
        append(action,resource,UUID.randomUUID().toString());var second=reader.list(a,filters(action),10,first.snapshotSequence(),first.nextBeforeSequence());assertEquals(2,second.items().size());assertFalse(second.hasMore());assertNull(second.nextBeforeSequence());assertEquals(first.snapshotSequence(),second.snapshotSequence());Set<Object> ids=new HashSet<>();first.items().forEach(row->assertTrue(ids.add(row.get("id"))));second.items().forEach(row->assertTrue(ids.add(row.get("id"))));assertEquals(13,reader.list(a,filters(action),15,null,null).items().size());
    }
    @Test void allExactFiltersAndHalfOpenTimeWindowUseTrustedScope(){
        String action=action(),resource=UUID.randomUUID().toString(),trace=UUID.randomUUID().toString();Instant from=Instant.now().truncatedTo(ChronoUnit.MILLIS);append(action,resource,trace);Instant until=Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        var all=new Filters(admin,action,"SUCCEEDED","synthetic_audit_reader",resource,trace,from,until);var found=reader.list(access(admin),all,10,null,null);assertEquals(1,found.items().size());assertEquals(trace,found.items().get(0).get("trace_id"));
        Instant exact=Instant.parse(found.items().get(0).get("occurred_at").toString());assertEquals(0,reader.list(access(admin),new Filters(admin,action,null,null,null,null,exact.minusSeconds(1),exact),10,null,null).items().size());assertEquals(1,reader.list(access(admin),new Filters(admin,action,null,null,null,null,exact,exact.plusSeconds(1)),10,null,null).items().size());
        assertEquals(0,reader.list(access(admin),filters("x' OR '1'='1"),10,null,null).items().size());assertEquals(0,reader.list(access(admin),new Filters(viewer,action,null,null,null,null,null,null),10,null,null).items().size());
    }
    @Test void currentRoleMembershipAndPermissionVersionAreRequiredForBothOperations(){
        for(String actor:List.of(viewer,engineer)){assertEquals(403,assertThrows(DomainException.class,()->reader.list(access(actor),filters(null),10,null,null)).status);assertEquals(403,assertThrows(DomainException.class,()->reader.verify(access(actor),1,null)).status);}
        Access a=access(admin);Access stale=new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1);assertEquals(401,assertThrows(DomainException.class,()->reader.list(stale,filters(null),10,null,null)).status);assertEquals(401,assertThrows(DomainException.class,()->reader.verify(stale,1,null)).status);
        assertEquals(401,assertThrows(DomainException.class,()->reader.list(new Access(UUID.randomUUID().toString(),TENANT,1),filters(null),10,null,null)).status);
    }
    @Test void tenantScopeNeverIncludesOtherTenantAndDoesNotInferPlatformMembership(){
        Access a=access(admin);assertTrue(reader.list(a,filters(null),100,null,null).items().stream().allMatch(row->TENANT.equals(row.get("tenant_id"))));
        if(platform.membership(admin,OTHER).isEmpty())assertEquals(401,assertThrows(DomainException.class,()->reader.list(new Access(admin,OTHER,1),filters(null),10,null,null)).status);
        else {var member=platform.membership(admin,OTHER).orElseThrow();assertTrue(reader.list(new Access(admin,OTHER,member.permissionVersion()),filters(null),100,null,null).items().stream().allMatch(row->OTHER.equals(row.get("tenant_id"))));}
    }
    @Test void rangeVerificationReturnsExplicitLocalEvidenceWithoutWriting()throws Exception{
        String action=action();for(int i=0;i<3;i++)append(action,UUID.randomUUID().toString(),UUID.randomUUID().toString());var items=reader.list(access(admin),filters(action),10,null,null).items();long start=sequence(items.get(2)),end=sequence(items.get(0)),count=count();var result=reader.verify(access(admin),start,end);assertEquals("VALID",result.status());assertEquals(true,result.valid());assertEquals(3,result.verifiedRecords());assertEquals(start,result.fromSequence());assertEquals(end,result.toSequence());assertEquals("NONE",result.failure());assertNull(result.failureSequence());assertNotNull(result.checkedAt());assertNotNull(result.anchorHash());assertNotNull(result.finalHash());assertEquals(count,count());reader.list(access(admin),filters(null),10,null,null);assertEquals(count,count());
    }
    @Test void defaultVerificationIsBoundedAndFutureRangeIsNotInvented(){
        var result=reader.verify(access(admin),1,null);assertEquals("VALID",result.status());assertTrue(result.verifiedRecords()<=1000);assertEquals(result.toSequence()<result.snapshotSequence(),result.hasMore());assertEquals(result.hasMore()?result.toSequence()+1:null,result.nextFromSequence());assertEquals(404,assertThrows(DomainException.class,()->reader.verify(access(admin),result.snapshotSequence()+1,null)).status);
    }
    @Test void invalidPageRangeAndTimestampPrecisionDoNotReadUnboundedData(){
        Access a=access(admin);assertEquals(400,assertThrows(DomainException.class,()->reader.list(a,filters(null),101,null,null)).status);assertEquals(400,assertThrows(DomainException.class,()->reader.list(a,filters(null),10,null,1L)).status);assertEquals(400,assertThrows(DomainException.class,()->reader.list(a,filters(null),10,1L,2L)).status);assertEquals(400,assertThrows(DomainException.class,()->reader.verify(a,1,1001L)).status);
        Instant now=Instant.parse("2026-10-02T00:00:00.000001Z");assertEquals(400,assertThrows(DomainException.class,()->reader.list(a,new Filters(null,null,null,null,null,null,now,null),10,null,null)).status);assertEquals(400,assertThrows(DomainException.class,()->reader.list(a,filters("\u00a0"),10,null,null)).status);
    }
}
