package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.assetdependency.AssetDependencyRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG transactions and independent connections; requires V7 pre-applied, never migrates. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresAssetDependencyIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static HikariDataSource pool;
    static ObjectMapper mapper;
    static PostgresPlatformRepository platform;
    static PostgresAssetDependencyRepository dependencies;
    static String engineer,viewer,foreign;
    @BeforeAll static void connect()throws SQLException{
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(6);config.setConnectionTimeout(5000);pool=new HikariDataSource(config);
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        platform=new PostgresPlatformRepository(pool,mapper);dependencies=new PostgresAssetDependencyRepository(pool,platform);
        engineer=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals("msp")).isPresent()).findFirst().orElseThrow().id();
        viewer=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals("viewer")).isPresent()).findFirst().orElseThrow().id();
        foreign=platform.list(OTHER).get(0).id();assertEquals(List.of(),dependencies.readinessProblems());
        try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND to_regclass('public.mop_asset_dependency_revision') IS NOT NULL")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static String asset(){
        String run=UUID.randomUUID().toString();
        return ((Asset)platform.idempotent(engineer,TENANT,"POST /integration/dependency-assets",run,"a".repeat(64),platform.membership(engineer,TENANT).orElseThrow().permissionVersion(),()->{
            Asset value=platform.insert(TENANT,engineer,"DEPENDENCY-TEST-"+run,"switch","2001:db8:"+run.substring(0,4)+":"+run.substring(4,8)+"::1","site-a-main");
            platform.audit(engineer,TENANT,"integration.asset.create","asset",value.id(),"SUCCEEDED","SYNTHETIC_DEPENDENCY_TEST",run);return value;
        })).id();
    }
    static ProposedEdge edge(String parent,String child){return new ProposedEdge(parent,child,null,null,null);}
    static Object write(PostgresPlatformRepository p,PostgresAssetDependencyRepository d,String actor,long version,List<ProposedEdge> edges,String key,String hash){return p.idempotent(actor,TENANT,"POST /integration/dependency-drafts",key,hash,p.membership(actor,TENANT).orElseThrow().permissionVersion(),()->d.append(TENANT,actor,version,edges,key));}
    static Draft add(long version,List<ProposedEdge> edges){return (Draft)write(platform,dependencies,engineer,version,edges,UUID.randomUUID().toString(),"a".repeat(64));}
    static Draft current(){return dependencies.current(TENANT,engineer);}
    static long historyEdges(long version)throws SQLException{
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_asset_dependency_edge WHERE tenant_id::text=? AND revision_version=?")){s.setString(1,TENANT);s.setLong(2,version);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}
    }
    static long auditCount(){
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_audit WHERE tenant_id::text=? AND payload->>'action'='asset_dependency.draft.append'")){
            s.setString(1,TENANT);try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }catch(SQLException failure){throw new AssertionError("Synthetic audit count unavailable");}
    }
    @Test void savesImmutableFullRevisionAndNeverInventsEffectiveValues()throws SQLException{
        String a=asset(),b=asset(),c=asset();Draft before=current();
        Draft one=add(before.version(),List.of(edge(a,b)));assertEquals(before.version()+1,one.version());assertEquals(before.edges().size()+1,one.edges().size());
        ProposedEdge saved=one.edges().stream().filter(e->e.parentId().equals(a)).findFirst().orElseThrow();assertNull(saved.proposedType());assertNull(saved.proposedSuppressionScope());assertNull(saved.proposedEnabled());
        long historical=historyEdges(one.version());Draft two=add(one.version(),List.of(new ProposedEdge(b,c,"unconfirmed-kind","unconfirmed-scope",true)));
        assertEquals(historical,historyEdges(one.version()));assertEquals(historical+1,historyEdges(two.version()));
        assertEquals(two,new PostgresAssetDependencyRepository(pool,new PostgresPlatformRepository(pool,mapper)).current(TENANT,engineer));
        try(var con=pool.getConnection();var s=con.createStatement();var r=s.executeQuery("SELECT count(*) FROM public.mop_asset_dependency_revision WHERE lifecycle_status<>'DRAFT' OR decision_status<>'pending' OR monitoring_applied")){r.next();assertEquals(0,r.getLong(1));}
    }
    @Test void completeGraphRejectsTransitiveCycleAndSelfReferenceWithoutChangingHistory(){
        String a=asset(),b=asset(),c=asset();Draft saved=add(current().version(),List.of(edge(a,b),edge(b,c)));long audits=auditCount();
        assertEquals(400,assertThrows(DomainException.class,()->add(saved.version(),List.of(edge(c,a)))).status);
        assertEquals(400,assertThrows(DomainException.class,()->add(saved.version(),List.of(edge(a,a)))).status);
        assertEquals(saved,current());assertEquals(audits,auditCount());
    }
    @Test void crossTenantAndUnknownObjectsStay404EvenWithStaleVersion(){
        String a=asset();Draft before=current();
        for(String hidden:List.of(foreign,UUID.randomUUID().toString()))assertEquals(404,assertThrows(DomainException.class,()->add(0,List.of(edge(a,hidden)))).status);
        assertEquals(before,current());
    }
    @Test void oldGraphRevisionAndDuplicatePairsCannotOverwrite(){
        String a=asset(),b=asset();Draft before=current(),saved=add(before.version(),List.of(edge(a,b)));
        assertEquals("VERSION_CONFLICT",assertThrows(DomainException.class,()->add(before.version(),List.of(edge(b,a)))).code);
        assertEquals("ASSET_DEPENDENCY_DUPLICATE",assertThrows(DomainException.class,()->add(saved.version(),List.of(new ProposedEdge(a,b,"different",null,false)))).code);
        assertEquals(saved,current());
    }
    @Test void idempotentReplayDoesNotAddRevisionOrAuditAndChangedHashIsRejected()throws Exception{
        String a=asset(),b=asset(),key=UUID.randomUUID().toString();Draft before=current();long audits=auditCount();
        Object first=write(platform,dependencies,engineer,before.version(),List.of(edge(a,b)),key,"a".repeat(64));
        Object replay=write(platform,dependencies,engineer,before.version(),List.of(edge(a,b)),key,"a".repeat(64));
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(first)),mapper.readTree(mapper.writeValueAsBytes(replay)));assertEquals(before.version()+1,current().version());assertEquals(audits+1,auditCount());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(DomainException.class,()->write(platform,dependencies,engineer,before.version(),List.of(edge(b,a)),key,"b".repeat(64))).code);
    }
    @Test void permissionAndProtectedTransactionAreRequired(){
        String a=asset(),b=asset();Draft before=current();
        assertThrows(IllegalStateException.class,()->dependencies.append(TENANT,engineer,before.version(),List.of(edge(a,b)),"outside"));
        assertEquals(403,assertThrows(DomainException.class,()->write(platform,dependencies,viewer,before.version(),List.of(edge(a,b)),UUID.randomUUID().toString(),"a".repeat(64))).status);
        assertEquals(401,assertThrows(DomainException.class,()->dependencies.current(TENANT,UUID.randomUUID().toString())).status);
        assertEquals(before,dependencies.current(TENANT,viewer));
    }
    @Test void downstreamFailureRollsBackGraphRevisionEdgesReplayAndAudit(){
        String a=asset(),b=asset(),key=UUID.randomUUID().toString();Draft before=current();long audits=auditCount();
        assertThrows(IllegalStateException.class,()->platform.idempotent(engineer,TENANT,"POST /integration/dependency-rollback",key,"a".repeat(64),()->{
            dependencies.append(TENANT,engineer,before.version(),List.of(edge(a,b)),key);throw new IllegalStateException("Synthetic rollback trigger");
        }));
        assertEquals(before,current());assertEquals(audits,auditCount());
        // The same key remains usable because its first transaction rolled back completely.
        Object retry=platform.idempotent(engineer,TENANT,"POST /integration/dependency-rollback",key,"b".repeat(64),()->dependencies.append(TENANT,engineer,before.version(),List.of(edge(a,b)),key));
        assertEquals(before.version()+1,((Draft)retry).version());
    }
    @Test void concurrentOppositeEdgesSerializeAndRetryDetectsTheCycle()throws Exception{
        String a=asset(),b=asset();long version=current().version();var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try{
            List<Future<Object>> results=new ArrayList<>();
            for(ProposedEdge proposed:List.of(edge(a,b),edge(b,a)))results.add(workers.submit(()->{
                var p=new PostgresPlatformRepository(pool,mapper);var d=new PostgresAssetDependencyRepository(pool,p);ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));
                try{return write(p,d,engineer,version,List.of(proposed),UUID.randomUUID().toString(),"c".repeat(64));}catch(DomainException failure){return failure;}
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();List<Object> outcomes=new ArrayList<>();for(var result:results)outcomes.add(result.get(15,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(Draft.class::isInstance).count());assertEquals(1,outcomes.stream().filter(o->o instanceof DomainException e&&e.code.equals("VERSION_CONFLICT")).count());
            Draft saved=current();assertEquals(version+1,saved.version());boolean forward=saved.edges().stream().anyMatch(e->e.parentId().equals(a)&&e.childId().equals(b));
            assertEquals(400,assertThrows(DomainException.class,()->add(saved.version(),List.of(forward?edge(b,a):edge(a,b)))).status);
        }finally{workers.shutdownNow();}
    }
    @Test void batchAndMetadataLimitsRejectRatherThanTruncate(){
        String a=asset(),b=asset();Draft before=current();
        for(List<ProposedEdge> input:List.of(List.<ProposedEdge>of(),Collections.nCopies(101,edge(a,b)),List.of(new ProposedEdge(a,b,"x".repeat(65),null,null)),List.of(new ProposedEdge(a,b,null,"x".repeat(129),null)),List.of(new ProposedEdge(a,b,"bad\u0000",null,null)),List.of(new ProposedEdge(a,b,"\u00a0\ufeff",null,null)),List.of(new ProposedEdge("not-a-uuid",b,null,null,null))))assertEquals(400,assertThrows(DomainException.class,()->add(before.version(),input)).status);
        assertEquals(before,current());
    }
}
