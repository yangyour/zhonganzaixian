package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.mop.persistence.PostgresInspectionWorksheetIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
/** Explicitly gated real PG checks. Never starts services or applies SQL. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresInspectionQuarterlyReportIntegrationTest {
    final PostgresInspectionWorksheetIntegrationTest f=new PostgresInspectionWorksheetIntegrationTest();
    static PostgresInspectionQuarterlyReportRepository reports;
    static PostgresInspectionSourceCollectionRepository collections;
    @BeforeAll static void start()throws Exception{connect();reports=new PostgresInspectionQuarterlyReportRepository(pool,platform);collections=new PostgresInspectionSourceCollectionRepository(pool,platform);assertEquals(List.of(),reports.readinessProblems());}
    @AfterAll static void stop(){close();}
    @BeforeEach void setup(){f.source();}
    @AfterEach void cleanup(){f.archiveOnlyOwnedDrafts();}
    static Access who(){var a=admin();return new Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId());}
    static Access who(String actor,String tenant){var a=access(actor,tenant);return new Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId());}
    static Object command(Access a,String key,Supplier<Object> work){return platform.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/quarterly-reports",key,"a".repeat(64),a.permissionVersion(),()->reports.authorizeWrite(a),work);}
    static Summary save(Creation input){var a=who();return (Summary)command(a,UUID.randomUUID().toString(),()->reports.create(a,0,input));}
    InspectionSourceCollectionRepository.Summary collection(InspectionWorksheetRepository.Entry...entries){
        var a=admin();var access=new InspectionSourceCollectionRepository.Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId());
        var sources=Arrays.stream(entries).map(e->new InspectionSourceCollectionRepository.Source(e.id(),e.version(),InspectionSourceSnapshot.fromEntry(e).fingerprint())).toList();
        return (InspectionSourceCollectionRepository.Summary)platform.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/report-sources",UUID.randomUUID().toString(),"a".repeat(64),a.permissionVersion(),()->collections.authorizeWrite(access),()->collections.create(access,0,new InspectionSourceCollectionRepository.Creation("SYNTHETIC_QUARTERLY_SOURCE",sources)));
    }
    Creation input(InspectionSourceCollectionRepository.Summary source){return new Creation("SYNTHETIC_QUARTERLY_DRAFT",source.id(),source.documentSha256(),source.sourceManifestSha256(),new InspectionQuarterlyReport.Period(2026,4),InspectionQuarterlyReport.Configuration.pending());}
    static List<Long> totals()throws Exception{var values=new ArrayList<Long>();try(var c=pool.getConnection();var s=c.createStatement()){for(String table:List.of("mop_inspection_quarterly_report","mop_audit","mop_idempotency"))try(var r=s.executeQuery("SELECT count(*) FROM public."+table)){assertTrue(r.next());values.add(r.getLong(1));}}return values;}
    @Test void snapshotAndAuditReceiptCommitAtomicallyAndFreshRepositoryRendersSameDocument()throws Exception{
        var source=collection(f.create(filled("synthetic")));var before=totals();var saved=save(input(source));assertEquals(List.of(before.get(0)+1,before.get(1)+1,before.get(2)+1),totals());
        var stored=reports.get(who(),saved.id());assertEquals(saved,stored.summary());var fresh=new PostgresInspectionQuarterlyReportRepository(pool,new PostgresPlatformRepository(pool,mapper));assertEquals(stored,fresh.get(who(),saved.id()));
        var root=mapper.readTree(stored.frozenDocument());assertEquals("DRAFT",root.path("output").path("generation_status").asText());assertTrue(root.path("configuration").path("timeZone").isNull());assertTrue(root.path("output").path("tenant_asset_population").isNull());
    }
    @Test void laterWorksheetAndTemplateChangesCannotReplaceFrozenReport(){var one=f.create(empty());var saved=save(input(collection(one)));var original=reports.get(who(),saved.id());f.archive(f.update(one,filled("later")));f.templateChange(f.template,true);assertEquals(original,reports.get(who(),saved.id()));}
    @Test void unknownExpectedSourceHashConflictsWithoutWrites()throws Exception{var input=input(collection());var before=totals();var bad=new Creation(input.label(),input.sourceCollectionId(),"0".repeat(64),input.expectedSourceManifestSha256(),input.period(),input.configuration());assertEquals(409,assertThrows(DomainException.class,()->save(bad)).status);assertEquals(before,totals());}
    @Test void crossTenantCollectionAndReportAndMissingObjectAre404()throws Exception{
        var source=collection();var saved=save(input(source));var other=who(admin,A);var before=totals();assertEquals(404,assertThrows(DomainException.class,()->reports.get(other,saved.id())).status);
        assertEquals(404,assertThrows(DomainException.class,()->command(other,UUID.randomUUID().toString(),()->reports.create(other,0,input(source)))).status);
        assertEquals(404,assertThrows(DomainException.class,()->reports.get(who(),UUID.randomUUID().toString())).status);assertEquals(before,totals());
    }
    @Test void currentAuthorizationPrecedesOriginalReceiptReplay()throws Exception{var input=input(collection());var a=who();String key=UUID.randomUUID().toString();var first=command(a,key,()->reports.create(a,0,input));var before=totals();assertEquals(json(first),json(command(a,key,()->{throw new AssertionError("must replay");})));var stale=new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1,a.traceId());assertEquals(401,assertThrows(DomainException.class,()->command(stale,key,()->null)).status);assertEquals(before,totals());}
    @Test void callerFailureRollsBackSnapshotAuditAndReceipt()throws Exception{var input=input(collection());var a=who();var before=totals();assertThrows(IllegalStateException.class,()->command(a,UUID.randomUUID().toString(),()->{reports.create(a,0,input);throw new IllegalStateException("synthetic rollback");}));assertEquals(before,totals());}
    @Test void unprotectedWritesAndNestedReadsAreRejected(){var input=input(collection());var a=who();assertThrows(IllegalStateException.class,()->reports.create(a,0,input));assertThrows(IllegalStateException.class,()->reports.authorizeWrite(a));assertThrows(IllegalStateException.class,()->command(a,UUID.randomUUID().toString(),()->reports.list(a,10,null,null)));assertEquals(400,assertThrows(DomainException.class,()->command(a,UUID.randomUUID().toString(),()->reports.create(a,1,input))).status);}
    @Test void metadataPaginationAndReadsDoNotWrite()throws Exception{var source=collection();save(input(source));save(input(source));save(input(source));var before=totals();var first=reports.list(who(),2,null,null);var last=first.get(1);var next=reports.list(who(),2,last.recordedAt(),last.id());var ids=new HashSet<String>();for(var s:first)assertTrue(ids.add(s.id()));for(var s:next)assertTrue(ids.add(s.id()));reports.get(who(),last.id());assertEquals(before,totals());}
    @Test void nonAdminAndStaleMembershipCannotCreateOrRead()throws Exception{var input=input(collection());for(String role:List.of("viewer","msp","tadmin")){var a=who(actor(role),A);assertEquals(403,assertThrows(DomainException.class,()->command(a,UUID.randomUUID().toString(),()->reports.create(a,0,input))).status);assertEquals(403,assertThrows(DomainException.class,()->reports.list(a,10,null,null)).status);}}
    @Test void structureAndRuntimeAclMatchCandidate()throws Exception{try(var c=pool.getConnection()){InspectionQuarterlyReportSchemaVerifier.verify(c);}}
    @Test void verifierRejectsSameCountWeakenedChecksAndRestoresSessionSettings()throws Exception {
        Assumptions.assumeTrue("mop_test_migrator".equals(System.getenv("MOP_MIGRATION_USERNAME"))&&System.getenv("MOP_MIGRATION_PASSWORD")!=null,
                "Explicit existing test migrator credentials required for transactional schema-tampering test");
        // pgjdbc requires the ISO output format. DMY still exercises a non-default DateStyle.
        for(String column:List.of("report_published","version")) {
            try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("MOP_MIGRATION_USERNAME"),System.getenv("MOP_MIGRATION_PASSWORD"))) {
                c.setAutoCommit(false);
                try {
                    try(var q=c.createStatement()){q.execute("SET LOCAL TIME ZONE 'Asia/Shanghai'");q.execute("SET LOCAL DateStyle='ISO, DMY'");}
                    InspectionQuarterlyReportSchemaVerifier.verify(c);assertSessionSettings(c);
                    String constraint;
                    try(var q=c.prepareStatement("SELECT conname FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND c.conkey=ARRAY[a.attnum] WHERE c.conrelid='public.mop_inspection_quarterly_report'::regclass AND c.contype='c' AND a.attname=?")) {
                        q.setString(1,column);try(var rows=q.executeQuery()){assertTrue(rows.next());constraint=rows.getString(1);assertFalse(rows.next());}
                    }
                    assertTrue(constraint.matches("[a-z0-9_]+"));
                    try(var q=c.createStatement()) {
                        q.execute("ALTER TABLE public.mop_inspection_quarterly_report DROP CONSTRAINT "+constraint+", ADD CONSTRAINT "+constraint+" CHECK (true)");
                        try(var rows=q.executeQuery("SELECT count(*) FROM pg_constraint WHERE conrelid='public.mop_inspection_quarterly_report'::regclass AND contype='c'")){assertTrue(rows.next());assertEquals(15,rows.getInt(1));}
                    }
                    assertThrows(IllegalStateException.class,()->InspectionQuarterlyReportSchemaVerifier.verify(c));
                    assertSessionSettings(c);
                } finally { c.rollback(); }
                InspectionQuarterlyReportSchemaVerifier.verify(c);
            }
        }
    }
    private static void assertSessionSettings(Connection c)throws SQLException {
        try(var q=c.createStatement();var rows=q.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")) {
            assertTrue(rows.next());assertEquals("Asia/Shanghai",rows.getString(1));assertEquals("ISO, DMY",rows.getString(2));
        }
    }
    @Test void runtimeCannotUpdateDeleteOrTruncateSnapshot()throws Exception{var saved=save(input(collection()));for(String sql:List.of("UPDATE public.mop_inspection_quarterly_report SET label='forged' WHERE id='"+saved.id()+"'","DELETE FROM public.mop_inspection_quarterly_report WHERE id='"+saved.id()+"'","TRUNCATE public.mop_inspection_quarterly_report"))try(var c=pool.getConnection()){c.setAutoCommit(false);try(var s=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->s.execute(sql)).getSQLState());}finally{c.rollback();}}}
    @Test void databaseRejectsChangedSourceHashPeriodFingerprintAndFalsePublication()throws Exception{
        var saved=save(input(collection()));
        rejectCopy(saved,"source_document_sha256","repeat('f',64)","23514");
        rejectCopy(saved,"source_collection_id","'"+UUID.randomUUID()+"'::uuid","23503");
        rejectCopy(saved,"report_sha256","repeat('f',64)","23514");
        rejectCopy(saved,"year","2025","23514");
        rejectCopy(saved,"report_published","true","23514");
        rejectCopy(saved,"configuration_source","'APPROVED'","23514");
    }
    void rejectCopy(Summary saved,String field,String expression,String sqlState)throws Exception{
        var columns=List.of("id","tenant_id","created_by","label","recorded_at","version","source_collection_id","source_document_sha256","source_manifest_sha256","year","quarter","report_sha256","document","document_format","configuration_source","generation_status","report_published","report_publishable","evidence_access_granted");
        var values=columns.stream().map(c->c.equals("id")?"'"+UUID.randomUUID()+"'::uuid":c.equals(field)?expression:c).toList();
        try(var c=pool.getConnection()){c.setAutoCommit(false);try(var statement=c.prepareStatement("INSERT INTO public.mop_inspection_quarterly_report("+String.join(",",columns)+") SELECT "+String.join(",",values)+" FROM public.mop_inspection_quarterly_report WHERE id=?::uuid")){statement.setString(1,saved.id());assertEquals(sqlState,assertThrows(SQLException.class,statement::executeUpdate).getSQLState());}finally{c.rollback();}}
    }
    @Test void metadataListDoesNotLoadFrozenBodies()throws Exception{
        var saved=save(input(collection()));var helper=new PostgresInspectionSourceCollectionRepositoryIntegrationTest();var count=new java.util.concurrent.atomic.AtomicInteger();
        var wrapped=helper.wrap((sql,statement,connection)->{assertTrue(connection.isReadOnly());if(sql.contains("FROM public.mop_inspection_quarterly_report")){count.incrementAndGet();assertFalse(sql.contains(",document FROM"));}return statement;});
        var reader=new PostgresInspectionQuarterlyReportRepository(wrapped,new PostgresPlatformRepository(wrapped,mapper));assertEquals(saved,reader.list(who(),1,null,null).get(0));assertEquals(1,count.get());
    }
    @Test void corruptedFrozenOutputFailsClosedWithNoLiveFallback()throws Exception{
        var saved=save(input(collection()));var helper=new PostgresInspectionSourceCollectionRepositoryIntegrationTest();var before=totals();
        var wrapped=helper.wrap((sql,statement,connection)->{
            if(!sql.contains(",document FROM public.mop_inspection_quarterly_report"))return statement;
            return (PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,args)->{
                Object value=PostgresInspectionSourceCaptureIntegrationTest.invoke(m,statement,args);if(!m.getName().equals("executeQuery"))return value;var rows=(ResultSet)value;
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(rp,rm,ra)->{Object cell=PostgresInspectionSourceCaptureIntegrationTest.invoke(rm,rows,ra);if(rm.getName().equals("getBytes")&&"document".equals(ra[0])){byte[] bytes=((byte[])cell).clone();bytes[0]^=1;return bytes;}return cell;});
            });
        });
        var reader=new PostgresInspectionQuarterlyReportRepository(wrapped,new PostgresPlatformRepository(wrapped,mapper));assertEquals(503,assertThrows(DomainException.class,()->reader.get(who(),saved.id())).status);assertEquals(before,totals());assertEquals(saved,reports.get(who(),saved.id()).summary());
    }
    @Test void sameKeyConcurrentConnectionsProduceOneSnapshot()throws Exception{
        var input=input(collection());var a=who();String key=UUID.randomUUID().toString();var before=totals();var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try{java.util.concurrent.Callable<com.fasterxml.jackson.databind.JsonNode> task=()->{ready.countDown();assertTrue(go.await(5,java.util.concurrent.TimeUnit.SECONDS));return json(command(a,key,()->reports.create(a,0,input)));};var one=executor.submit(task);var two=executor.submit(task);assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();assertEquals(one.get(15,java.util.concurrent.TimeUnit.SECONDS),two.get(15,java.util.concurrent.TimeUnit.SECONDS));}finally{executor.shutdownNow();}
        assertEquals(List.of(before.get(0)+1,before.get(1)+1,before.get(2)+1),totals());
    }

}
