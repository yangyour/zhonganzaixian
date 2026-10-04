package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionSourceCollectionRepository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static cn.mop.persistence.PostgresInspectionWorksheetIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
/** Existing real runtime PG; retained immutable records are explicitly synthetic, never formal reports. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresInspectionSourceCollectionRepositoryIntegrationTest {
    final PostgresInspectionWorksheetIntegrationTest f=new PostgresInspectionWorksheetIntegrationTest();
    static PostgresInspectionSourceCollectionRepository collections;
    @BeforeAll static void start()throws Exception{connect();collections=new PostgresInspectionSourceCollectionRepository(pool,platform);assertEquals(List.of(),collections.readinessProblems());}
    @AfterAll static void stop(){close();}
    @BeforeEach void setup(){f.source();}
    @AfterEach void cleanup(){f.archiveOnlyOwnedDrafts();}
    static Access who(){var a=admin();return new Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId());}
    static Access who(String actor,String tenant){var a=access(actor,tenant);return new Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId());}
    static Source source(InspectionWorksheetRepository.Entry e){return new Source(e.id(),e.version(),InspectionSourceSnapshot.fromEntry(e).fingerprint());}
    static Creation input(Source...sources){return new Creation("SYNTHETIC_SOURCE_COLLECTION_REPOSITORY",List.of(sources));}
    static Object write(Access a,String key,Creation input){return command(a,key,()->collections.create(a,0,input));}
    static Object command(Access a,String key,Supplier<Object> work){return platform.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/source-collections",key,"a".repeat(64),a.permissionVersion(),()->collections.authorizeWrite(a),work);}
    static Summary save(Creation input){return (Summary)write(who(),UUID.randomUUID().toString(),input);}
    static List<Long> totals()throws Exception{var values=new ArrayList<Long>();try(var c=pool.getConnection();var s=c.createStatement()){for(String table:List.of("mop_inspection_source_collection","mop_inspection_source_collection_item","mop_audit","mop_idempotency")){try(var r=s.executeQuery("SELECT count(*) FROM public."+table)){assertTrue(r.next());values.add(r.getLong(1));}}}return values;}
    @Test void savesDocumentItemsAuditReceiptAtomicallyAndFreshReadVerifies()throws Exception{var one=f.create(filled("😀\n来源"));var before=totals();var saved=save(input(source(one)));assertEquals(List.of(before.get(0)+1,before.get(1)+1,before.get(2)+1,before.get(3)+1),totals());assertEquals(1,saved.version());assertEquals(1,saved.sourceCount());var fresh=new PostgresInspectionSourceCollectionRepository(pool,new PostgresPlatformRepository(pool,mapper));var stored=fresh.get(who(),saved.id());assertEquals(saved,stored.summary());assertEquals(InspectionSourceSnapshot.fromEntry(one),stored.manifest().sources().get(0));assertFalse(stored.manifest().formalReportGeneratable());assertFalse(stored.manifest().reportPublished());}
    @Test void preservesEveryExplicitVersionAndArchiveFlagAfterParentChanges(){var one=f.create(empty());var two=f.update(one,filled(null));var archived=f.archive(two);var saved=save(input(source(archived),source(one),source(two)));var original=collections.get(who(),saved.id());assertEquals(3,original.manifest().selectedSourceCount());assertEquals(1,original.manifest().archivedSelectedRevisions().size());f.templateChange(f.template,false);assertEquals(original,collections.get(who(),saved.id()));}
    @Test void emptySelectionIsNotKnownPopulationOrApprovedReport(){var saved=save(input());var manifest=collections.get(who(),saved.id()).manifest();assertEquals(0,saved.sourceCount());assertNull(manifest.tenantAssetPopulation());assertNull(manifest.periodTimeZone());assertEquals("CONFIG_PENDING",manifest.generationStatus());assertFalse(manifest.formalReportGeneratable());}
    @Test void expectedSourceHashConflictDoesNotWriteOrSelectLatest()throws Exception{var one=f.create(empty());f.update(one,filled("new"));var before=totals();var wrong=new Source(one.id(),one.version(),"0".repeat(64));assertEquals(409,assertThrows(DomainException.class,()->save(input(wrong))).status);assertEquals(before,totals());assertEquals(one.version(),collections.get(who(),save(input(source(one))).id()).manifest().sources().get(0).version());}
    @Test void missingOrForeignSourcesAndCollectionStay404WithoutPartialWrite()throws Exception{var one=f.create(empty());var before=totals();assertEquals(404,assertThrows(DomainException.class,()->save(input(source(one),new Source(UUID.randomUUID().toString(),1,"a".repeat(64))))).status);assertEquals(404,assertThrows(DomainException.class,()->write(who(admin,A),UUID.randomUUID().toString(),input(source(one)))).status);assertEquals(before,totals());var saved=save(input(source(one)));assertEquals(404,assertThrows(DomainException.class,()->collections.get(who(admin,A),saved.id())).status);assertEquals(404,assertThrows(DomainException.class,()->collections.get(who(),UUID.randomUUID().toString())).status);}
    @Test void currentRolesAndStaleMembershipFailBeforeWrites()throws Exception{var before=totals();for(String role:List.of("viewer","msp","tadmin")){var a=who(actor(role),A);assertEquals(403,assertThrows(DomainException.class,()->write(a,UUID.randomUUID().toString(),input())).status);assertEquals(403,assertThrows(DomainException.class,()->collections.list(a,10,null,null)).status);}var a=who();assertEquals(401,assertThrows(DomainException.class,()->write(new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1,a.traceId()),UUID.randomUUID().toString(),input())).status);assertEquals(before,totals());}
    @Test void originalReceiptSurvivesSourceArchiveAndRechecksCurrentAuthorization()throws Exception{var one=f.create(empty());var a=who();var key=UUID.randomUUID().toString();var original=write(a,key,input(source(one)));f.archive(one);f.templateChange(f.template,true);var before=totals();assertEquals(json(original),json(command(a,key,()->{throw new AssertionError("duplicate");})));assertEquals(before,totals());var stale=new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1,a.traceId());assertEquals(401,assertThrows(DomainException.class,()->command(stale,key,()->null)).status);assertEquals(before,totals());}
    @Test void callerFailureRollsBackDocumentItemsAuditAndReceipt()throws Exception{var one=f.create(empty());var a=who();var before=totals();assertThrows(IllegalStateException.class,()->command(a,UUID.randomUUID().toString(),()->{collections.create(a,0,input(source(one)));throw new IllegalStateException("Synthetic caller rollback");}));assertEquals(before,totals());}
    @Test void rejectsUnprotectedWritesNestedReadsAndInvalidCreateVersion()throws Exception{var a=who();var before=totals();assertThrows(IllegalStateException.class,()->collections.create(a,0,input()));assertThrows(IllegalStateException.class,()->collections.authorizeWrite(a));assertThrows(IllegalStateException.class,()->command(a,UUID.randomUUID().toString(),()->collections.list(a,10,null,null)));assertEquals(400,assertThrows(DomainException.class,()->command(a,UUID.randomUUID().toString(),()->collections.create(a,1,input()))).status);assertEquals(before,totals());}
    @Test void successfulReadsAreReadOnlyAndPaginationHasNoDuplicate()throws Exception{var saved=new ArrayList<Summary>();for(int i=0;i<3;i++)saved.add(save(input()));var before=totals();var first=collections.list(who(),2,null,null);assertEquals(2,first.size());var tail=first.get(1);var next=collections.list(who(),2,tail.recordedAt(),tail.id());var ids=new HashSet<String>();first.forEach(s->assertTrue(ids.add(s.id())));next.forEach(s->assertTrue(ids.add(s.id())));assertTrue(ids.containsAll(saved.stream().map(Summary::id).toList()));collections.get(who(),saved.get(0).id());assertEquals(before,totals());}
    @Test void invalidCursorLimitAndTraceCannotRead(){var a=who();for(int limit:new int[]{0,22})assertEquals(400,assertThrows(DomainException.class,()->collections.list(a,limit,null,null)).status);assertEquals(400,assertThrows(DomainException.class,()->collections.list(a,10,Instant.EPOCH,null)).status);assertEquals(400,assertThrows(DomainException.class,()->collections.list(a,10,Instant.EPOCH.plusNanos(1),UUID.randomUUID().toString())).status);assertEquals(400,assertThrows(DomainException.class,()->collections.list(new Access(a.actorId(),a.tenantId(),a.permissionVersion(),"bad trace"),10,null,null)).status);}
    @Test void sameKeyConcurrentConnectionsProduceOneCommittedCollection()throws Exception{var one=f.create(empty());var a=who();var key=UUID.randomUUID().toString();var before=totals();var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);try{Callable<com.fasterxml.jackson.databind.JsonNode> action=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return json(write(a,key,input(source(one))));};var x=executor.submit(action);var y=executor.submit(action);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertEquals(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));}finally{executor.shutdownNow();}assertEquals(List.of(before.get(0)+1,before.get(1)+1,before.get(2)+1,before.get(3)+1),totals());}
    @Test void actualDeferredItemConstraintFailureRollsBackDocumentAuditAndReceipt()throws Exception{fault(false);}
    @Test void actualAuditSqlFailureRollsBackDocumentItemsAndReceipt()throws Exception{fault(true);}
    void fault(boolean audit)throws Exception{
        var one=f.create(empty());var before=totals();
        javax.sql.DataSource wrapped=wrap((sql,statement,connection)->{
            boolean target=audit?sql.startsWith("INSERT INTO public.mop_audit"):sql.startsWith("INSERT INTO public.mop_inspection_source_collection_item");if(!target)return statement;
            return (PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,args)->{
                if(audit&&m.getName().equals("executeUpdate")){try(var fail=connection.createStatement()){fail.execute("SELECT 1/0");}throw new AssertionError("real SQL division must fail");}
                if(!audit&&m.getName().equals("setString")&&Integer.valueOf(6).equals(args[0]))args=new Object[]{6,"0".repeat(64)};
                return PostgresInspectionSourceCaptureIntegrationTest.invoke(m,statement,args);
            });
        });
        var p=new PostgresPlatformRepository(wrapped,mapper);var r=new PostgresInspectionSourceCollectionRepository(wrapped,p);var a=who();
        assertEquals(503,assertThrows(DomainException.class,()->p.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/source-collection-fault",UUID.randomUUID().toString(),"a".repeat(64),a.permissionVersion(),()->r.authorizeWrite(a),()->r.create(a,0,input(source(one))))).status);assertEquals(before,totals());
    }
    @Test void damagedBytesAtReadFailClosedWithoutChangingActualStoredDocument()throws Exception{
        var one=f.create(empty());var saved=save(input(source(one)));var original=collections.get(who(),saved.id());var before=totals();
        var wrapped=wrap((sql,statement,connection)->{
            if(!sql.contains(",document FROM public.mop_inspection_source_collection"))return statement;
            assertTrue(connection.isReadOnly());assertFalse(connection.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());
            return (PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,args)->{
                Object value=PostgresInspectionSourceCaptureIntegrationTest.invoke(m,statement,args);
                if(m.getName().equals("executeQuery")){var rows=(ResultSet)value;return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(rp,rm,ra)->{
                    Object cell=PostgresInspectionSourceCaptureIntegrationTest.invoke(rm,rows,ra);if(rm.getName().equals("getBytes")&&"document".equals(ra[0])){byte[] damaged=((byte[])cell).clone();damaged[0]^=1;return damaged;}return cell;
                });}return value;
            });
        });
        var reader=new PostgresInspectionSourceCollectionRepository(wrapped,new PostgresPlatformRepository(wrapped,mapper));assertEquals(503,assertThrows(DomainException.class,()->reader.get(who(),saved.id())).status);assertEquals(before,totals());assertEquals(original,collections.get(who(),saved.id()));
    }
    @Test void metadataListDoesNotFetchDocumentOrClaimBodyVerification()throws Exception{
        var saved=save(input());var before=totals();var queries=new java.util.concurrent.atomic.AtomicInteger();
        var wrapped=wrap((sql,s,c)->{assertTrue(c.isReadOnly());assertFalse(c.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());if(sql.contains("FROM public.mop_inspection_source_collection")){queries.incrementAndGet();assertFalse(sql.contains(",document FROM"));}return s;});
        var reader=new PostgresInspectionSourceCollectionRepository(wrapped,new PostgresPlatformRepository(wrapped,mapper));assertEquals(saved,reader.list(who(),1,null,null).get(0));assertEquals(1,queries.get());assertEquals(before,totals());
    }
    @Test void deniedAuthorizationStillPrecedesPersistedReceipt()throws Exception{
        var a=who();String key=UUID.randomUUID().toString();write(a,key,input());var before=totals();
        assertEquals(403,assertThrows(DomainException.class,()->platform.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/source-collections",key,"a".repeat(64),a.permissionVersion(),()->{throw new DomainException(403,"ACTION_FORBIDDEN","Synthetic current authorization denial");},()->null)).status);assertEquals(before,totals());
    }
    @Test void collectionTimestampCannotPredateFrozenSource()throws Exception{
        var saved=save(input(source(f.create(empty()))));var before=totals();
        var wrapped=wrap((sql,statement,connection)->{
            if(!sql.contains(",document FROM public.mop_inspection_source_collection"))return statement;
            return (PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,args)->{
                Object value=PostgresInspectionSourceCaptureIntegrationTest.invoke(m,statement,args);if(m.getName().equals("executeQuery")){var rows=(ResultSet)value;return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(rp,rm,ra)->rm.getName().equals("getObject")&&"recorded_at".equals(ra[0])?java.time.OffsetDateTime.ofInstant(Instant.EPOCH,java.time.ZoneOffset.UTC):PostgresInspectionSourceCaptureIntegrationTest.invoke(rm,rows,ra));}return value;
            });
        });var reader=new PostgresInspectionSourceCollectionRepository(wrapped,new PostgresPlatformRepository(wrapped,mapper));assertEquals(503,assertThrows(DomainException.class,()->reader.get(who(),saved.id())).status);assertEquals(before,totals());
    }
    @FunctionalInterface interface PreparedWrapper{PreparedStatement apply(String sql,PreparedStatement statement,Connection connection)throws Exception;}
    javax.sql.DataSource wrap(PreparedWrapper hook){return (javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{javax.sql.DataSource.class},(p,m,args)->{
        if(m.getName().equals("getConnection")){var real=pool.getConnection();return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(cp,cm,ca)->{
            Object value=PostgresInspectionSourceCaptureIntegrationTest.invoke(cm,real,ca);if(cm.getName().equals("prepareStatement")&&ca[0] instanceof String sql)return hook.apply(sql,(PreparedStatement)value,real);return value;
        });}return PostgresInspectionSourceCaptureIntegrationTest.invoke(m,pool,args);
    });}

}
