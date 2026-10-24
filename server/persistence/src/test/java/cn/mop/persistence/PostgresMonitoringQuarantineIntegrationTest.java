package cn.mop.persistence;
import cn.mop.domain.monitoring.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.persistence.PostgresMonitoringQuarantineRepository.Outcome.*;

/** Real isolated PostgreSQL; immutable synthetic held candidates intentionally remain for restart checks. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresMonitoringQuarantineIntegrationTest {
    static HikariDataSource pool;static String rawBefore;
    static final Instant SOURCE=Instant.ofEpochSecond(-1,999999999),RECEIVED=Instant.parse("2026-10-07T02:00:00.123456789Z");
    @BeforeAll static void connect()throws Exception{
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);MonitoringSimulationGuard.requireTestConfiguration(url,System.getenv("MOP_MONITORING_TEST_TENANT_ID"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(12);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);rawBefore=rawSnapshot();
    }
    @AfterAll static void close()throws Exception{if(pool!=null){try{assertEquals(rawBefore,rawSnapshot());}finally{pool.close();}}}
    static String rawSnapshot()throws Exception{try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT md5(COALESCE(string_agg(to_jsonb(t)::text,'|' ORDER BY id),'')) FROM public.mop_raw_monitoring_event t")){r.next();return r.getString(1);}}
    QuarantinedMonitoringEvent event(){return new QuarantinedMonitoringEvent(new RawMonitoringEvent.Key(UUID.randomUUID(),"unmapped-"+UUID.randomUUID(),RawMonitoringEvent.Phase.PROBLEM),new QuarantinedMonitoringEvent.Claims(null,null),QuarantinedMonitoringEvent.Reason.UNKNOWN_MAPPING,SOURCE,RECEIVED,new byte[]{0,-1,32});}
    QuarantinedMonitoringEvent changed(QuarantinedMonitoringEvent e,QuarantinedMonitoringEvent.Claims claims,QuarantinedMonitoringEvent.Reason reason,Instant source,Instant received,byte[] bytes){return new QuarantinedMonitoringEvent(e.key(),claims,reason,source,received,bytes);}
    PostgresMonitoringQuarantineRepository repo(){return new PostgresMonitoringQuarantineRepository(pool);}
    @Test void nullableUnknownAndCrossTenantClaimsPreserveExactFacts()throws Exception{
        var e=event();for(var claims:new QuarantinedMonitoringEvent.Claims[]{new QuarantinedMonitoringEvent.Claims(null,null),new QuarantinedMonitoringEvent.Claims(UUID.randomUUID(),null),new QuarantinedMonitoringEvent.Claims(null,UUID.randomUUID()),new QuarantinedMonitoringEvent.Claims(UUID.randomUUID(),UUID.randomUUID())}){
            var candidate=changed(e,claims,e.reason(),SOURCE,RECEIVED,e.payload());var receipt=repo().hold(candidate);assertEquals(HELD,receipt.outcome());
            try(var c=pool.getConnection();var s=c.prepareStatement("SELECT * FROM public.mop_monitoring_quarantine WHERE id=?")){s.setObject(1,receipt.quarantineId());try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(claims.tenantId(),r.getObject("claimed_tenant_id",UUID.class));assertEquals(claims.assetId(),r.getObject("claimed_asset_id",UUID.class));assertArrayEquals(e.payload(),r.getBytes("payload"));assertEquals(candidate.payloadSha256(),r.getString("payload_sha256"));assertEquals(candidate.candidateFingerprint(),r.getString("candidate_fingerprint"));assertEquals(-1,r.getLong("occurred_epoch_second"));assertEquals(999999999,r.getInt("occurred_nano"));assertEquals(123456789,r.getInt("received_nano"));assertEquals("HELD_UNVERIFIED",r.getString("quarantine_status"));assertFalse(r.getBoolean("dispatch_allowed"));assertFalse(r.getBoolean("source_authorized"));}}
        }
    }
    @Test void duplicateFromNewRepositoryPreservesFirstReceipt(){var e=event();var first=repo().hold(e);var second=repo().hold(changed(e,e.claims(),e.reason(),SOURCE,RECEIVED.plusSeconds(10),e.payload()));assertEquals(SAME_CANDIDATE,second.outcome());assertEquals(first.quarantineId(),second.quarantineId());assertEquals(first.firstReceivedAt(),second.firstReceivedAt());}
    @Test void sameSourceDifferentCandidatesRemainDistinct(){var e=event();Set<UUID> ids=new HashSet<>();for(var candidate:List.of(e,changed(e,e.claims(),e.reason(),SOURCE,RECEIVED,new byte[]{0,-1,32,32}),changed(e,e.claims(),e.reason(),SOURCE.plusNanos(1),RECEIVED,e.payload()),changed(e,new QuarantinedMonitoringEvent.Claims(UUID.randomUUID(),null),e.reason(),SOURCE,RECEIVED,e.payload()),changed(e,e.claims(),QuarantinedMonitoringEvent.Reason.SOURCE_KEY_CONFLICT,SOURCE,RECEIVED,e.payload()))){var r=repo().hold(candidate);assertEquals(HELD,r.outcome());ids.add(r.quarantineId());}assertEquals(5,ids.size());}
    @Test void eightConcurrentDuplicatesHaveOneCommittedWinner()throws Exception{
        var e=event();var executor=Executors.newFixedThreadPool(8);var ready=new CountDownLatch(8);var go=new CountDownLatch(1);var jobs=new ArrayList<Future<PostgresMonitoringQuarantineRepository.Receipt>>();try{for(int i=0;i<8;i++){final int n=i;jobs.add(executor.submit(()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return repo().hold(changed(e,e.claims(),e.reason(),SOURCE,RECEIVED.plusNanos(n),e.payload()));}));}assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();var results=new ArrayList<PostgresMonitoringQuarantineRepository.Receipt>();for(var job:jobs)results.add(job.get(20,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(r->r.outcome()==HELD).count());assertEquals(1,results.stream().map(PostgresMonitoringQuarantineRepository.Receipt::quarantineId).distinct().count());assertEquals(1,results.stream().map(PostgresMonitoringQuarantineRepository.Receipt::firstReceivedAt).distinct().count());}finally{go.countDown();executor.shutdownNow();}
    }
    @Test void concurrentSameSourceDifferentBytesAreBothRetained()throws Exception{
        var e=event();var executor=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);try{var a=executor.submit(()->{go.await();return repo().hold(e);});var b=executor.submit(()->{go.await();return repo().hold(changed(e,e.claims(),e.reason(),SOURCE,RECEIVED,new byte[]{1}));});go.countDown();var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);assertEquals(HELD,first.outcome());assertEquals(HELD,second.outcome());assertNotEquals(first.quarantineId(),second.quarantineId());}finally{go.countDown();executor.shutdownNow();}
    }
    @Test void allJavaInstantPrecisionSurvives(){var e=event();var candidate=changed(e,e.claims(),e.reason(),Instant.MAX,Instant.MIN,e.payload());assertEquals(HELD,repo().hold(candidate).outcome());assertEquals(Instant.MIN,repo().hold(candidate).firstReceivedAt());}
    @Test void sourceKeysAreDistinctEvenWithEqualContent(){var e=event();Set<UUID> ids=new HashSet<>();for(var key:List.of(e.key(),new RawMonitoringEvent.Key(UUID.randomUUID(),e.key().sourceEventId(),e.key().phase()),new RawMonitoringEvent.Key(e.key().poolId(),"another",e.key().phase()),new RawMonitoringEvent.Key(e.key().poolId(),e.key().sourceEventId(),RawMonitoringEvent.Phase.RECOVERED))){var result=repo().hold(new QuarantinedMonitoringEvent(key,e.claims(),e.reason(),SOURCE,RECEIVED,e.payload()));assertEquals(HELD,result.outcome());ids.add(result.quarantineId());}assertEquals(4,ids.size());}
    @Test void oneMiBOriginalPayloadIsNotNormalized(){var e=event();byte[] raw=new byte[RawMonitoringEvent.MAX_PAYLOAD_BYTES];Arrays.fill(raw,(byte)0xff);var c=changed(e,e.claims(),e.reason(),SOURCE,RECEIVED,raw);assertEquals(HELD,repo().hold(c).outcome());assertEquals(SAME_CANDIDATE,repo().hold(c).outcome());}
    @Test void directSqlCannotClaimDispatchOrAcceptMalformedFacts()throws Exception{
        try(var c=pool.getConnection()){c.setAutoCommit(false);for(String clause:List.of("source_event_id=''","source_event_id=' trailing '","source_event_id=E'control\\nvalue'","source_event_id=repeat('😀',129)","source_phase='ACTIVE'","quarantine_reason='APPROVED'","occurred_nano=-1","received_nano=1000000000","occurred_epoch_second=31556889864403200","payload=''::bytea","payload=decode(repeat('00',1048577),'hex')","quarantine_status='RELEASED'","dispatch_allowed=true","source_authorized=true")){
            var point=c.setSavepoint();String[] pair=clause.split("=",2);var cols=new ArrayList<>(List.of("id","pool_id","source_event_id","source_phase","quarantine_reason","occurred_epoch_second","occurred_nano","received_epoch_second","received_nano","payload"));var vals=new ArrayList<>(List.of("gen_random_uuid()","gen_random_uuid()","'test'","'PROBLEM'","'UNKNOWN_MAPPING'","0","1","0","1","decode('01','hex')"));int ix=cols.indexOf(pair[0]);if(ix<0){cols.add(pair[0]);vals.add(pair[1]);}else vals.set(ix,pair[1]);String sql="INSERT INTO public.mop_monitoring_quarantine("+String.join(",",cols)+") VALUES ("+String.join(",",vals)+")";try(var s=c.createStatement()){assertEquals("23514",assertThrows(SQLException.class,()->s.execute(sql)).getSQLState(),clause);}c.rollback(point);
        }c.rollback();}
    }
    @Test void runtimeCannotMutateAndHasNoPublicOrColumnWriteGrant()throws Exception{
        try(var c=pool.getConnection()){c.setAutoCommit(false);for(String sql:List.of("UPDATE public.mop_monitoring_quarantine SET source_event_id='x' WHERE false","DELETE FROM public.mop_monitoring_quarantine WHERE false","TRUNCATE public.mop_monitoring_quarantine")){var point=c.setSavepoint();try(var s=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->s.execute(sql)).getSQLState());}c.rollback(point);}TestSchemaMigration.verifyRuntimeAcl(c,"mop_monitoring_quarantine",Set.of());c.rollback();}
    }
    javax.sql.DataSource failingCommit(boolean after){return (javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{javax.sql.DataSource.class},(p,m,a)->{if(m.getName().equals("getConnection")){Connection real=pool.getConnection();return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(q,n,b)->{if(n.getName().equals("commit")){if(after)real.commit();throw new SQLException("PRIVATE_DRIVER_DETAIL");}try{return n.invoke(real,b);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}try{return m.invoke(pool,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
    @Test void beforeCommitFailureRollsBack(){var e=event();var failure=assertThrows(IllegalStateException.class,()->new PostgresMonitoringQuarantineRepository(failingCommit(false)).hold(e));assertFalse(failure.toString().contains("PRIVATE_DRIVER_DETAIL"));assertNull(failure.getCause());assertEquals(HELD,repo().hold(e).outcome());}
    @Test void unknownCommittedResultCanBeReconciled(){var e=event();assertThrows(IllegalStateException.class,()->new PostgresMonitoringQuarantineRepository(failingCommit(true)).hold(e));assertEquals(SAME_CANDIDATE,repo().hold(e).outcome());}
    void rejectsDrift(String sql)throws Exception{assertEquals("mop_test_migrator",System.getenv("MOP_MIGRATION_USERNAME"));try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("MOP_MIGRATION_USERNAME"),System.getenv("MOP_MIGRATION_PASSWORD"))){c.setAutoCommit(false);try{MonitoringQuarantineSchemaVerifier.verify(c);try(var s=c.createStatement()){s.execute(sql);}assertThrows(IllegalStateException.class,()->MonitoringQuarantineSchemaVerifier.verify(c));}finally{c.rollback();}MonitoringQuarantineSchemaVerifier.verify(c);}}
    @Test void weakenedChecksAreDetected()throws Exception{rejectsDrift("ALTER TABLE public.mop_monitoring_quarantine DROP CONSTRAINT mop_monitoring_quarantine_dispatch_allowed_check; ALTER TABLE public.mop_monitoring_quarantine ADD CONSTRAINT mop_monitoring_quarantine_dispatch_allowed_check CHECK(true)");}
    @Test void changedGeneratedExpressionIsDetected()throws Exception{rejectsDrift("ALTER TABLE public.mop_monitoring_quarantine ALTER COLUMN candidate_fingerprint SET EXPRESSION AS (public.mop_quarantine_fingerprint(pool_id,source_event_id,source_phase,claimed_tenant_id,claimed_asset_id,quarantine_reason,occurred_epoch_second,occurred_nano,payload)||'tampered')");}
    // V37's quarantine decisions reference this table; both fixture changes roll back.
    @Test void unloggedTableIsRejected()throws Exception{rejectsDrift("ALTER TABLE IF EXISTS public.mop_monitoring_quarantine_decision SET UNLOGGED; ALTER TABLE public.mop_monitoring_quarantine SET UNLOGGED");}
    @Test void utf16BoundaryIsIdenticalToDomain(){var e=event();var key=new RawMonitoringEvent.Key(e.key().poolId(),"😀".repeat(128),e.key().phase());assertEquals(HELD,repo().hold(new QuarantinedMonitoringEvent(key,e.claims(),e.reason(),SOURCE,RECEIVED,e.payload())).outcome());}
    @Test void storageForcesSynchronousCommitEvenIfPoolSessionIsAsync()throws Exception{
        var observed=new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource source=(javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{javax.sql.DataSource.class},(p,m,a)->{
            if(m.getName().equals("getConnection")){
                Connection real=pool.getConnection();try(var stmt=real.createStatement()){stmt.execute("SET synchronous_commit='off'");}
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(q,n,b)->{
                    if(n.getName().equals("commit")){try(var stmt=real.createStatement();var rows=stmt.executeQuery("SHOW synchronous_commit")){rows.next();if(!"on".equals(rows.getString(1)))throw new SQLException("Synchronous commit required");}real.commit();try(var stmt=real.createStatement();var rows=stmt.executeQuery("SHOW synchronous_commit")){rows.next();if(!"off".equals(rows.getString(1)))throw new SQLException("Transaction setting leaked");observed.incrementAndGet();}return null;}
                    if(n.getName().equals("close")){real.setAutoCommit(true);try(var stmt=real.createStatement()){stmt.execute("SET synchronous_commit='on'");}}
                    try{return n.invoke(real,b);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }try{return m.invoke(pool,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
        assertEquals(HELD,new PostgresMonitoringQuarantineRepository(source).hold(event()).outcome());
        assertEquals(1,observed.get());
    }

    @Test void missingDefaultIsRejected()throws Exception{rejectsDrift("ALTER TABLE public.mop_monitoring_quarantine ALTER COLUMN dispatch_allowed DROP DEFAULT");}
    @Test void inheritedChildIsRejected()throws Exception{rejectsDrift("CREATE TABLE public.drift_child () INHERITS(public.mop_monitoring_quarantine)");}
    @Test void inheritedParentIsRejected()throws Exception{rejectsDrift("CREATE TABLE public.drift_parent (); ALTER TABLE public.mop_monitoring_quarantine INHERIT public.drift_parent");}
    @Test void rewriteRuleIsRejected()throws Exception{rejectsDrift("CREATE RULE drift_rule AS ON UPDATE TO public.mop_monitoring_quarantine DO ALSO NOTHING");}
    @Test void tableDelegationGrantIsRejected()throws Exception{rejectsDrift("GRANT SELECT ON public.mop_monitoring_quarantine TO mop_test_runtime WITH GRANT OPTION");}
    @Test void columnDelegationGrantIsRejected()throws Exception{rejectsDrift("GRANT SELECT(payload) ON public.mop_monitoring_quarantine TO mop_test_runtime WITH GRANT OPTION");}
    @Test void functionDelegationGrantIsRejected()throws Exception{rejectsDrift("GRANT EXECUTE ON FUNCTION public.mop_quarantine_fingerprint(uuid,text,text,uuid,uuid,text,bigint,integer,bytea) TO mop_test_runtime WITH GRANT OPTION");}
    @Test void maintenancePrivilegeIsRejected()throws Exception{rejectsDrift("GRANT MAINTAIN ON public.mop_monitoring_quarantine TO mop_test_runtime");}
    @Test void columnReferencePrivilegeIsRejected()throws Exception{rejectsDrift("GRANT REFERENCES(payload) ON public.mop_monitoring_quarantine TO mop_test_runtime");}

}
