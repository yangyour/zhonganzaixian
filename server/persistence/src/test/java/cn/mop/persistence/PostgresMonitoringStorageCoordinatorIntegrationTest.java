package cn.mop.persistence;
import cn.mop.domain.monitoring.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.persistence.PostgresMonitoringEventStorageCoordinator.*;

@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresMonitoringStorageCoordinatorIntegrationTest {
    static HikariDataSource pool;static RawMonitoringEvent.Binding binding,other;
    static final Instant SOURCE=Instant.parse("2026-10-07T00:00:00.123456789Z"),FIRST=Instant.parse("2026-10-07T00:01:00.987654321Z"),CONFLICT=FIRST.plusSeconds(60);
    @BeforeAll static void connect()throws Exception{
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(12);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);
        try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT DISTINCT ON(tenant_id) tenant_id,id FROM public.mop_asset ORDER BY tenant_id,id")){assertTrue(r.next());binding=new RawMonitoringEvent.Binding(r.getObject(1,UUID.class),r.getObject(2,UUID.class));assertTrue(r.next());other=new RawMonitoringEvent.Binding(r.getObject(1,UUID.class),r.getObject(2,UUID.class));}
    }
    @AfterAll static void close(){if(pool!=null)pool.close();}
    RawMonitoringEvent event(){return new RawMonitoringEvent(new RawMonitoringEvent.Key(UUID.randomUUID(),"coordinator-"+UUID.randomUUID(),RawMonitoringEvent.Phase.PROBLEM),binding,SOURCE,FIRST,new byte[]{0,-1,10,32});}
    RawMonitoringEvent changed(RawMonitoringEvent e,RawMonitoringEvent.Binding scope,Instant source,Instant received,byte[] bytes){return new RawMonitoringEvent(e.key(),scope,source,received,bytes);}
    RawMonitoringEvent conflict(RawMonitoringEvent e){return changed(e,binding,SOURCE,CONFLICT,new byte[]{1,2,3});}
    PostgresMonitoringEventStorageCoordinator coordinator(){return new PostgresMonitoringEventStorageCoordinator(pool);}
    UUID seed(RawMonitoringEvent e){return new PostgresRawMonitoringEventRepository(pool).store(e).storedId();}
    int count(String table,RawMonitoringEvent e)throws Exception{assertTrue(Set.of("mop_raw_monitoring_event","mop_monitoring_quarantine").contains(table));try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM ONLY public."+table+" WHERE pool_id=? AND source_event_id=? AND source_phase=?")){s.setObject(1,e.key().poolId());s.setString(2,e.key().sourceEventId());s.setString(3,e.key().phase().name());try(var r=s.executeQuery()){r.next();return r.getInt(1);}}}
    String canonical(UUID id)throws Exception{try(var c=pool.getConnection();var s=c.prepareStatement("SELECT row_to_json(t)::text FROM ONLY public.mop_raw_monitoring_event t WHERE id=?")){s.setObject(1,id);try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
    void verifyHeld(RawMonitoringEvent incoming,HeldResult result)throws Exception{
        assertFalse(result.sourceAuthorized());assertFalse(result.dispatchAllowed());
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT * FROM ONLY public.mop_monitoring_quarantine WHERE id=?")){s.setObject(1,result.receipt().quarantineId());try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(incoming.key().poolId(),r.getObject("pool_id",UUID.class));assertEquals(incoming.key().sourceEventId(),r.getString("source_event_id"));assertEquals(incoming.key().phase().name(),r.getString("source_phase"));assertArrayEquals(incoming.payload(),r.getBytes("payload"));assertEquals(incoming.binding().tenantId(),r.getObject("claimed_tenant_id",UUID.class));assertEquals(incoming.binding().assetId(),r.getObject("claimed_asset_id",UUID.class));assertEquals(incoming.occurredAt().getEpochSecond(),r.getLong("occurred_epoch_second"));assertEquals(incoming.occurredAt().getNano(),r.getInt("occurred_nano"));assertEquals("SOURCE_KEY_CONFLICT",r.getString("quarantine_reason"));assertEquals("HELD_UNVERIFIED",r.getString("quarantine_status"));assertFalse(r.getBoolean("source_authorized"));assertFalse(r.getBoolean("dispatch_allowed"));}}
    }
    @Test void freshAndSameContentStayInRawStorageOnly()throws Exception{
        var e=event();var first=assertInstanceOf(RawResult.class,coordinator().store(e));assertEquals(PostgresRawMonitoringEventRepository.Outcome.STORED,first.receipt().outcome());var second=assertInstanceOf(RawResult.class,coordinator().store(changed(e,binding,SOURCE,FIRST.plusSeconds(30),e.payload())));assertEquals(PostgresRawMonitoringEventRepository.Outcome.SAME_CONTENT,second.receipt().outcome());assertEquals(first.receipt().storedId(),second.receipt().storedId());assertEquals(FIRST,second.receipt().firstReceivedAt());assertEquals(1,count("mop_raw_monitoring_event",e));assertEquals(0,count("mop_monitoring_quarantine",e));assertFalse(second.sourceAuthorized());assertFalse(second.dispatchAllowed());
    }
    @Test void changedPayloadIsHeldAndCanonicalBytesStayUnchanged()throws Exception{var e=event();var id=seed(e);var before=canonical(id);var incoming=conflict(e);var held=assertInstanceOf(HeldResult.class,coordinator().store(incoming));verifyHeld(incoming,held);assertEquals(before,canonical(id));assertEquals(1,count("mop_monitoring_quarantine",e));}
    @Test void changedSourceNanosecondIsHeldExactly()throws Exception{var e=event();var id=seed(e);var before=canonical(id);var incoming=changed(e,binding,SOURCE.plusNanos(1),CONFLICT,e.payload());verifyHeld(incoming,assertInstanceOf(HeldResult.class,coordinator().store(incoming)));assertEquals(before,canonical(id));}
    @Test void crossTenantConflictUsesOnlyIncomingBindingAndNeverCanonicalReceipt()throws Exception{var e=event();var id=seed(e);var before=canonical(id);var incoming=changed(e,other,SOURCE,CONFLICT,e.payload());var held=assertInstanceOf(HeldResult.class,coordinator().store(incoming));verifyHeld(incoming,held);assertNotEquals(id,held.receipt().quarantineId());assertEquals(CONFLICT,held.receipt().firstReceivedAt());assertFalse(held.toString().contains(id.toString()));assertFalse(held.toString().contains(FIRST.toString()));assertEquals(before,canonical(id));}
    @Test void repeatedConflictPreservesFirstHeldReceipt()throws Exception{var e=event();seed(e);var incoming=conflict(e);var first=assertInstanceOf(HeldResult.class,coordinator().store(incoming));var second=assertInstanceOf(HeldResult.class,coordinator().store(changed(incoming,binding,SOURCE,CONFLICT.plusSeconds(100),incoming.payload())));assertEquals(PostgresMonitoringQuarantineRepository.Outcome.SAME_CANDIDATE,second.receipt().outcome());assertEquals(first.receipt().quarantineId(),second.receipt().quarantineId());assertEquals(CONFLICT,second.receipt().firstReceivedAt());assertEquals(1,count("mop_monitoring_quarantine",e));}
    @Test void concurrentVariantsHaveOneRawWinnerAndAllLosingCandidatesHeld()throws Exception{
        var base=event();var executor=Executors.newFixedThreadPool(8);var ready=new CountDownLatch(8);var go=new CountDownLatch(1);var jobs=new ArrayList<Future<Result>>();var inputs=new ArrayList<RawMonitoringEvent>();try{for(int i=0;i<8;i++){var e=changed(base,binding,SOURCE,FIRST.plusNanos(i),new byte[]{(byte)i});inputs.add(e);jobs.add(executor.submit(()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return coordinator().store(e);}));}assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();var results=new ArrayList<Result>();for(var f:jobs)results.add(f.get(20,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(RawResult.class::isInstance).count());assertEquals(7,results.stream().filter(HeldResult.class::isInstance).count());assertEquals(1,count("mop_raw_monitoring_event",base));assertEquals(7,count("mop_monitoring_quarantine",base));for(int i=0;i<8;i++){Result replay=coordinator().store(inputs.get(i));if(results.get(i) instanceof RawResult a){var b=assertInstanceOf(RawResult.class,replay);assertEquals(a.receipt().storedId(),b.receipt().storedId());}else{var a=(HeldResult)results.get(i);var b=assertInstanceOf(HeldResult.class,replay);assertEquals(a.receipt().quarantineId(),b.receipt().quarantineId());}}assertEquals(7,count("mop_monitoring_quarantine",base));}finally{go.countDown();executor.shutdownNow();}
    }
    enum Fault{NONE,GET,BEFORE_COMMIT,AFTER_COMMIT,CLOSE}
    DataSource wrapped(AtomicInteger calls,int failAt,Fault fault,AtomicInteger syncCommits){return (DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
        if(m.getName().equals("getConnection")){
            int ordinal=calls.incrementAndGet();if(ordinal==failAt&&fault==Fault.GET)throw new SQLException("PRIVATE_CONNECT");Connection real=pool.getConnection();if(syncCommits!=null)try(var s=real.createStatement()){s.execute("SET synchronous_commit='off'");}
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(q,n,b)->{
                if(n.getName().equals("commit")){
                    if(syncCommits!=null)try(var s=real.createStatement();var rows=s.executeQuery("SHOW synchronous_commit")){rows.next();if(!"on".equals(rows.getString(1)))throw new SQLException("Commit is not synchronous");syncCommits.incrementAndGet();}
                    if(ordinal==failAt&&fault==Fault.BEFORE_COMMIT)throw new SQLException("PRIVATE_COMMIT_BEFORE");
                    if(ordinal==failAt&&fault==Fault.AFTER_COMMIT){real.commit();throw new SQLException("PRIVATE_COMMIT_AFTER");}
                }
                if(n.getName().equals("close")){
                    if(syncCommits!=null){real.setAutoCommit(true);try(var s=real.createStatement()){s.execute("SET synchronous_commit='on'");}}
                    if(ordinal==failAt&&fault==Fault.CLOSE){real.close();throw new SQLException("PRIVATE_CLOSE");}
                }
                try{return n.invoke(real,b);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        }try{return m.invoke(pool,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    });}
    void failureThenRetry(int connection,Fault fault,boolean committed)throws Exception{
        var base=event();var id=seed(base);var before=canonical(id);var incoming=conflict(base);var calls=new AtomicInteger();var broken=new PostgresMonitoringEventStorageCoordinator(wrapped(calls,connection,fault,null));var failure=assertThrows(IllegalStateException.class,()->broken.store(incoming));assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains("PRIVATE"));assertEquals(connection,calls.get());assertEquals(before,canonical(id));assertEquals(committed?1:0,count("mop_monitoring_quarantine",base));var result=assertInstanceOf(HeldResult.class,coordinator().store(incoming));assertEquals(committed?PostgresMonitoringQuarantineRepository.Outcome.SAME_CANDIDATE:PostgresMonitoringQuarantineRepository.Outcome.HELD,result.receipt().outcome());verifyHeld(incoming,result);assertEquals(1,count("mop_monitoring_quarantine",base));assertEquals(before,canonical(id));
    }
    @Test void failureAcquiringSecondTransactionReturnsNoSuccessAndReplayHolds()throws Exception{failureThenRetry(2,Fault.GET,false);}
    @Test void quarantineCommitFailureRollsBackBeforeAnyHeldReceipt()throws Exception{failureThenRetry(2,Fault.BEFORE_COMMIT,false);}
    @Test void unknownCommittedHoldOutcomeIsReconciledByReplay()throws Exception{failureThenRetry(2,Fault.AFTER_COMMIT,true);}
    @Test void rawCommitFailuresNeverAttemptQuarantine()throws Exception{failureThenRetry(1,Fault.BEFORE_COMMIT,false);failureThenRetry(1,Fault.AFTER_COMMIT,false);}
    @Test void rawCloseFailureNeverAttemptsQuarantine()throws Exception{failureThenRetry(1,Fault.CLOSE,false);}
    @Test void quarantineCloseFailureIsNotReportedAsSuccess()throws Exception{failureThenRetry(2,Fault.CLOSE,true);}
    @Test void bothTransactionsPinSynchronousCommitOnSameDataSource()throws Exception{var base=event();seed(base);var calls=new AtomicInteger();var commits=new AtomicInteger();var result=new PostgresMonitoringEventStorageCoordinator(wrapped(calls,0,Fault.NONE,commits)).store(conflict(base));assertInstanceOf(HeldResult.class,result);assertEquals(2,calls.get());assertEquals(2,commits.get());}
    @Test void uncertainFreshRawResultsReconcileWithoutQuarantine()throws Exception{
        for(Fault fault:List.of(Fault.BEFORE_COMMIT,Fault.AFTER_COMMIT,Fault.CLOSE)){
            var e=event();var calls=new AtomicInteger();var broken=new PostgresMonitoringEventStorageCoordinator(wrapped(calls,1,fault,null));assertThrows(IllegalStateException.class,()->broken.store(e));assertEquals(1,calls.get());assertEquals(fault==Fault.BEFORE_COMMIT?0:1,count("mop_raw_monitoring_event",e));assertEquals(0,count("mop_monitoring_quarantine",e));var result=assertInstanceOf(RawResult.class,coordinator().store(e));assertEquals(fault==Fault.BEFORE_COMMIT?PostgresRawMonitoringEventRepository.Outcome.STORED:PostgresRawMonitoringEventRepository.Outcome.SAME_CONTENT,result.receipt().outcome());assertEquals(FIRST,result.receipt().firstReceivedAt());assertEquals(1,count("mop_raw_monitoring_event",e));assertEquals(0,count("mop_monitoring_quarantine",e));
        }
    }

}
