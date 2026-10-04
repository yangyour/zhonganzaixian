package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionWorksheetRepository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static cn.mop.persistence.PostgresInspectionWorksheetIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Existing authorized PG, owned synthetic worksheets. Instrumentation delegates all data to real PG. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresInspectionSourceCaptureIntegrationTest {
    final PostgresInspectionWorksheetIntegrationTest f=new PostgresInspectionWorksheetIntegrationTest();
    @BeforeAll static void connectSource()throws Exception{connect();}
    @AfterAll static void closeSource(){close();}
    @BeforeEach void setup(){f.source();}
    @AfterEach void cleanup(){f.archiveOnlyOwnedDrafts();}
    static Selection selected(Entry e){return new Selection(e.id(),e.version());}
    @Test void capturesExactHistoricalContentWithUnknownsAndNoWrites()throws Exception{
        var one=f.create(empty());var before=counts();var capture=repository.captureSources(admin(),List.of(selected(one)));
        assertEquals(InspectionSourceSnapshot.fromEntry(one),capture.sources().get(0));assertEquals(1,capture.sourcesWithUnknownInspectionTime().size());assertEquals(1,capture.incompletelyEnteredSources().size());assertEquals(before,counts());
        assertEquals("CONFIG_PENDING",capture.generationStatus());assertNull(capture.periodTimeZone());assertNull(capture.tenantAssetPopulation());assertFalse(capture.formalReportGeneratable());assertFalse(capture.reportPublished());
    }
    @Test void oldRevisionHashRemainsStableAfterSourceHeadAndTemplateChange(){
        var one=f.create(filled("原始😀"));var original=repository.captureSources(admin(),List.of(selected(one)));var two=f.update(one,filled("后来"));f.archive(two);var changed=f.templateChange(f.template,false);f.templateChange(changed,true);
        assertEquals(original,repository.captureSources(admin(),List.of(selected(one))));assertEquals(original.fingerprint(),repository.captureSources(admin(),List.of(selected(one))).fingerprint());assertFalse(original.sources().get(0).revisionArchived());
    }
    @Test void doesNotCollapseMultipleWorksheetsOrVersionsForSameAsset(){
        var one=f.create(empty());var two=f.update(one,filled(null));var other=f.create(filled(""));var archived=f.archive(two);
        var selections=List.of(selected(archived),selected(other),selected(one),selected(two));var captured=repository.captureSources(admin(),selections);var reversed=new ArrayList<>(selections);Collections.reverse(reversed);
        assertEquals(captured,repository.captureSources(admin(),reversed));assertEquals(4,captured.selectedSourceCount());assertEquals(1,captured.distinctSelectedAssets());assertEquals(4,captured.assetsWithMultipleSources().get(UUID.fromString(asset)).size());assertEquals(1,captured.archivedSelectedRevisions().size());assertEquals(1,captured.sourcesWithUnknownInspectionTime().size());
        assertThrows(UnsupportedOperationException.class,()->captured.sources().clear());
    }
    @Test void emptySelectionStillChecksAuthorizationAndDoesNotMeanZeroPopulation()throws Exception{
        var before=counts();var result=repository.captureSources(admin(),List.of());assertEquals(0,result.selectedSourceCount());assertNull(result.tenantAssetPopulation());assertFalse(result.formalReportGeneratable());assertEquals(before,counts());
        assertEquals(403,assertThrows(DomainException.class,()->repository.captureSources(access(actor("viewer"),A),List.of())).status);
    }
    @Test void foreignAndMissingRevisionAre404WithNoPartialResultOrWrites()throws Exception{
        var one=f.create(empty());var before=counts();assertEquals(404,assertThrows(DomainException.class,()->repository.captureSources(access(admin,A),List.of(selected(one)))).status);
        for(var missing:List.of(new Selection(UUID.randomUUID().toString(),1),new Selection(one.id(),2))){assertEquals(404,assertThrows(DomainException.class,()->repository.captureSources(admin(),List.of(selected(one),missing))).status);}assertEquals(before,counts());
    }
    @Test void duplicateNullAndCountBoundsRejectWithoutWrites()throws Exception{
        var one=f.create(empty());var before=counts();for(var selections:Arrays.asList(null,Arrays.asList((Selection)null),List.of(selected(one),selected(one))))assertEquals(400,assertThrows(DomainException.class,()->repository.captureSources(admin(),selections)).status);
        assertEquals(413,assertThrows(DomainException.class,()->repository.captureSources(admin(),Collections.nCopies(1001,selected(one)))).status);assertEquals(before,counts());
    }
    @Test void staleMissingMembershipAndNonAdminRolesAreRechecked()throws Exception{
        var one=f.create(empty());var a=admin();var before=counts();for(String role:List.of("viewer","msp","tadmin"))assertEquals(403,assertThrows(DomainException.class,()->repository.captureSources(access(actor(role),A),List.of(selected(one)))).status);
        for(var denied:List.of(new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1,a.traceId()),new Access(UUID.randomUUID().toString(),B,1,a.traceId())))assertEquals(401,assertThrows(DomainException.class,()->repository.captureSources(denied,List.of(selected(one)))).status);assertEquals(before,counts());
    }
    @Test void freshRepositoryResolvesSameImmutableSource(){var one=f.create(filled(null));var other=new PostgresInspectionWorksheetRepository(pool,new PostgresPlatformRepository(pool,mapper));assertEquals(repository.captureSources(admin(),List.of(selected(one))),other.captureSources(admin(),List.of(selected(one))));}
    @Test void cannotCaptureInsideProtectedWriteTransaction()throws Exception{var one=f.create(empty());var a=admin();var before=counts();assertThrows(IllegalStateException.class,()->command(a,one.id(),"POST /integration/capture-nested",UUID.randomUUID().toString(),"a".repeat(64),()->repository.captureSources(a,List.of(selected(one)))));assertEquals(before,counts());}
    @Test void repeatableReadDoesNotMixInRevisionCommittedAfterAuthorization(){
        var one=f.create(empty());var fired=new AtomicBoolean();var instrumented=instrument(sql->{if(sql.startsWith("WITH requested")&&fired.compareAndSet(false,true))f.update(one,filled("committed after snapshot"));});
        assertEquals(404,assertThrows(DomainException.class,()->instrumented.captureSources(admin(),List.of(new Selection(one.id(),2)))).status);assertTrue(fired.get());assertEquals(2,repository.captureSources(admin(),List.of(new Selection(one.id(),2))).sources().get(0).version());
    }
    @Test void readOnlyRepeatableReadAndSharedTemplateCacheAreActuallyUsed(){
        var first=f.create(empty());var second=f.create(filled(null));var reads=new AtomicInteger();var instrumented=instrument(sql->{if(sql.startsWith("SELECT * FROM public.mop_inspection_template_revision"))reads.incrementAndGet();});
        assertEquals(2,instrumented.captureSources(admin(),List.of(selected(first),selected(second))).selectedSourceCount());assertEquals(1,reads.get());
    }
    @Test void realUncompressedTemplateBudgetRejectsBeforeLoadingFullContent()throws Exception{
        var items=new ArrayList<InspectionTemplate.Item>();for(int i=0;i<100;i++)items.add(new InspectionTemplate.Item("large_"+i,"合成","非业务容量测试","😀".repeat(2000)));
        var definition=new InspectionTemplate("synthetic_large","SYNTHETIC_WORKSHEET_SOURCE_BUDGET",items);var a=templateAccess();var prior=f.template;
        f.template=(InspectionTemplateRepository.Entry)platform.idempotentAuthorized(a.actorId(),a.tenantId(),"PATCH /integration/source-budget-template",UUID.randomUUID().toString(),definition.fingerprint(),a.permissionVersion(),()->templates.authorizeWrite(a,prior.id()),()->templates.update(a,prior.id(),prior.version(),definition));
        var entry=f.create(empty());var selections=new ArrayList<Selection>();selections.add(selected(entry));for(int i=1;i<21;i++){entry=f.update(entry,empty());selections.add(selected(entry));}
        var before=counts();var fullReads=new AtomicInteger();var reader=instrument(sql->{if(sql.startsWith("SELECT * FROM public.mop_inspection_template_revision")||sql.contains("SELECT h.id::text"))fullReads.incrementAndGet();});
        assertEquals(413,assertThrows(DomainException.class,()->reader.captureSources(admin(),selections)).status);assertEquals(0,fullReads.get());assertEquals(before,counts());
        assertEquals(1,repository.captureSources(admin(),List.of(selections.get(0))).selectedSourceCount());
    }
    @Test void corruptedReadHashFailsClosedWithoutChangingPersistedSource()throws Exception{
        var one=f.create(filled("unchanged actual database"));var before=counts();
        DataSource wrapped=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(proxy,method,args)->{
            if(method.getName().equals("getConnection")){var real=pool.getConnection();return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(cp,cm,ca)->{
                if(cm.getName().equals("prepareStatement")&&ca[0] instanceof String sql&&sql.startsWith("WITH requested")&&sql.contains("SELECT h.id::text")){
                    var statement=(PreparedStatement)invoke(cm,real,ca);return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(sp,sm,sa)->{
                        var value=invoke(sm,statement,sa);if(sm.getName().equals("executeQuery")){var rows=(ResultSet)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(rp,rm,ra)->rm.getName().equals("getString")&&"content_sha256".equals(ra[0])?"0".repeat(64):invoke(rm,rows,ra));}return value;
                    });
                }return invoke(cm,real,ca);
            });}return invoke(method,pool,args);
        });
        var reader=new PostgresInspectionWorksheetRepository(wrapped,new PostgresPlatformRepository(wrapped,mapper));assertEquals(503,assertThrows(DomainException.class,()->reader.captureSources(admin(),List.of(selected(one)))).status);
        assertEquals(before,counts());assertEquals(InspectionSourceSnapshot.fromEntry(one),repository.captureSources(admin(),List.of(selected(one))).sources().get(0));
    }
    static Object invoke(Method method,Object target,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    static PostgresInspectionWorksheetRepository instrument(java.util.function.Consumer<String> onSql){
        DataSource wrapped=(DataSource)Proxy.newProxyInstance(PostgresInspectionSourceCaptureIntegrationTest.class.getClassLoader(),new Class[]{DataSource.class},(proxy,method,args)->{
            if(method.getName().equals("getConnection")){var real=pool.getConnection();return Proxy.newProxyInstance(PostgresInspectionSourceCaptureIntegrationTest.class.getClassLoader(),new Class[]{Connection.class},(cp,cm,ca)->{
                if(cm.getName().equals("prepareStatement")&&ca[0] instanceof String sql){assertTrue(real.isReadOnly());assertFalse(real.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,real.getTransactionIsolation());onSql.accept(sql);}
                try{return cm.invoke(real,ca);}catch(InvocationTargetException e){throw e.getCause();}
            });}try{return method.invoke(pool,args);}catch(InvocationTargetException e){throw e.getCause();}
        });return new PostgresInspectionWorksheetRepository(wrapped,new PostgresPlatformRepository(wrapped,mapper));
    }
}
