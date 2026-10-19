package cn.mop.persistence;

import cn.mop.domain.monitoring.RawMonitoringEvent;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.persistence.PostgresRawMonitoringEventRepository.Outcome.*;

/** Explicit real-PG tests; synthetic immutable records remain in the isolated test database. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresRawMonitoringEventIntegrationTest {
    static HikariDataSource pool; static RawMonitoringEvent.Binding binding,other; static String before;
    static final Instant SOURCE=Instant.ofEpochSecond(-1,999999999),RECEIPT=Instant.parse("2026-10-07T01:00:00.123456789Z");
    @BeforeAll static void connect()throws Exception{
        var cfg=new HikariConfig();String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(12);cfg.setConnectionTimeout(5000);pool=new HikariDataSource(cfg);
        try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT DISTINCT ON(tenant_id) tenant_id,id FROM public.mop_asset ORDER BY tenant_id,id")){assertTrue(r.next());binding=new RawMonitoringEvent.Binding(r.getObject(1,UUID.class),r.getObject(2,UUID.class));assertTrue(r.next());other=new RawMonitoringEvent.Binding(r.getObject(1,UUID.class),r.getObject(2,UUID.class));}
        before=assetSnapshot();
    }
    @AfterAll static void close()throws Exception{if(pool!=null){try{assertEquals(before,assetSnapshot());}finally{pool.close();}}}
    static String assetSnapshot()throws Exception{try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT md5(string_agg(to_jsonb(a)::text,'|' ORDER BY id)) FROM public.mop_asset a")){r.next();return r.getString(1);}}
    RawMonitoringEvent event(){return new RawMonitoringEvent(new RawMonitoringEvent.Key(UUID.randomUUID(),"合成-"+UUID.randomUUID(),RawMonitoringEvent.Phase.PROBLEM),binding,SOURCE,RECEIPT,new byte[]{0,-1,10,32});}
    RawMonitoringEvent changed(RawMonitoringEvent e,RawMonitoringEvent.Binding b,Instant source,Instant received,byte[] bytes){return new RawMonitoringEvent(e.key(),b,source,received,bytes);}
    PostgresRawMonitoringEventRepository repo(){return new PostgresRawMonitoringEventRepository(pool);}
    @Test void exactBytesNanoTimesAndSqlFingerprintRoundTrip()throws Exception{
        var e=event();var receipt=repo().store(e);assertEquals(STORED,receipt.outcome());assertEquals(RECEIPT,receipt.firstReceivedAt());
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT * FROM public.mop_raw_monitoring_event WHERE id=?")){s.setObject(1,receipt.storedId());try(var r=s.executeQuery()){assertTrue(r.next());assertArrayEquals(e.payload(),r.getBytes("payload"));assertEquals(e.payloadSha256(),r.getString("payload_sha256"));assertEquals(e.contentFingerprint(),r.getString("content_fingerprint"));assertEquals(-1,r.getLong("occurred_epoch_second"));assertEquals(999999999,r.getInt("occurred_nano"));assertEquals(123456789,r.getInt("received_nano"));assertEquals("UNVERIFIED",r.getString("source_authorization_status"));assertEquals("STORED_ONLY",r.getString("storage_status"));}}
    }
    @Test void duplicateAfterNewRepositoryPreservesOriginalReceipt(){var e=event();var first=repo().store(e);var replay=repo().store(changed(e,binding,SOURCE,RECEIPT.plusSeconds(100),e.payload()));assertEquals(SAME_CONTENT,replay.outcome());assertEquals(first.storedId(),replay.storedId());assertEquals(first.firstReceivedAt(),replay.firstReceivedAt());}
    @Test void payloadWhitespaceSourceTimeAndBindingConflictsNeverOverwrite(){var e=event();var first=repo().store(e);for(var changed:List.of(changed(e,binding,SOURCE,RECEIPT,new byte[]{0,-1,10,32,32}),changed(e,binding,SOURCE.plusNanos(1),RECEIPT,e.payload()),changed(e,other,SOURCE,RECEIPT,e.payload()))){var result=repo().store(changed);assertEquals(CONFLICT_REQUIRES_REVIEW,result.outcome());assertNull(result.storedId());assertNull(result.firstReceivedAt());}assertEquals(first.storedId(),repo().store(e).storedId());}
    @Test void concurrentSameContentHasExactlyOneWinner()throws Exception{
        var e=event();var executor=Executors.newFixedThreadPool(8);var ready=new CountDownLatch(8);var go=new CountDownLatch(1);var futures=new ArrayList<Future<PostgresRawMonitoringEventRepository.Receipt>>();
        try{for(int i=0;i<8;i++){final int n=i;futures.add(executor.submit(()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return repo().store(changed(e,binding,SOURCE,RECEIPT.plusNanos(n),e.payload()));}));}assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();var results=new ArrayList<PostgresRawMonitoringEventRepository.Receipt>();for(var f:futures)results.add(f.get(20,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(r->r.outcome()==STORED).count());assertEquals(1,results.stream().map(PostgresRawMonitoringEventRepository.Receipt::storedId).distinct().count());assertEquals(1,results.stream().map(PostgresRawMonitoringEventRepository.Receipt::firstReceivedAt).distinct().count());}finally{go.countDown();executor.shutdownNow();}
    }
    @Test void concurrentDifferentFactsHaveOneWinnerAndOneNonDisclosingConflict()throws Exception{
        var e=event();var executor=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        try{var a=executor.submit(()->{go.await();return repo().store(e);});var b=executor.submit(()->{go.await();return repo().store(changed(e,other,SOURCE,RECEIPT,new byte[]{1}));});go.countDown();var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(Set.of(STORED,CONFLICT_REQUIRES_REVIEW),new HashSet<>(results.stream().map(PostgresRawMonitoringEventRepository.Receipt::outcome).toList()));var conflict=results.stream().filter(r->r.outcome()==CONFLICT_REQUIRES_REVIEW).findFirst().orElseThrow();assertNull(conflict.storedId());}finally{go.countDown();executor.shutdownNow();}
    }
    @Test void poolPhaseAndSourceIdAreIndependent(){var e=event();Set<UUID> ids=new HashSet<>();for(var key:List.of(e.key(),new RawMonitoringEvent.Key(UUID.randomUUID(),e.key().sourceEventId(),e.key().phase()),new RawMonitoringEvent.Key(e.key().poolId(),e.key().sourceEventId(),RawMonitoringEvent.Phase.RECOVERED),new RawMonitoringEvent.Key(e.key().poolId(),"another",e.key().phase()))){var r=repo().store(new RawMonitoringEvent(key,binding,SOURCE,RECEIPT,e.payload()));assertEquals(STORED,r.outcome());ids.add(r.storedId());}assertEquals(4,ids.size());}
    @Test void sourceClockMayBeAheadAndExtremeInstantsAreLossless(){var e=event();assertEquals(STORED,repo().store(changed(e,binding,Instant.MAX,Instant.MIN,e.payload())).outcome());assertEquals(SAME_CONTENT,repo().store(changed(e,binding,Instant.MAX,Instant.MIN,e.payload())).outcome());}
    @Test void differentTenantAssetIsRejectedWithoutSensitiveDetails(){var e=event();var failure=assertThrows(IllegalStateException.class,()->repo().store(changed(e,new RawMonitoringEvent.Binding(binding.tenantId(),other.assetId()),SOURCE,RECEIPT,e.payload())));assertFalse(failure.toString().contains(e.key().sourceEventId()));assertNull(failure.getCause());assertEquals(STORED,repo().store(e).outcome());}
    @Test void databaseRejectsInvalidDirectSqlAndRuntimeMutation()throws Exception{
        try(var c=pool.getConnection()){c.setAutoCommit(false);
            for(String clause:List.of("source_event_id=''","source_event_id=' leading'","source_event_id=E'bad\\nvalue'","source_event_id=repeat('😀',129)","source_phase='ACTIVE'","occurred_nano=-1","received_nano=1000000000","occurred_epoch_second=31556889864403200","payload=''::bytea","payload=decode(repeat('00',1048577),'hex')","source_authorization_status='VERIFIED'","storage_status='ACCEPTED'")){
                var point=c.setSavepoint();var e=event();String[] v=clause.split("=",2);String columns="id,pool_id,source_event_id,source_phase,tenant_id,asset_id,occurred_epoch_second,occurred_nano,received_epoch_second,received_nano,payload";String values="gen_random_uuid(),gen_random_uuid(),'source','PROBLEM','"+binding.tenantId()+"'::uuid,'"+binding.assetId()+"'::uuid,0,1,0,1,decode('01','hex')";
                var cols=new ArrayList<>(List.of(columns.split(",")));var vals=new ArrayList<>(List.of("gen_random_uuid()","gen_random_uuid()","'source'","'PROBLEM'","'"+binding.tenantId()+"'::uuid","'"+binding.assetId()+"'::uuid","0","1","0","1","decode('01','hex')"));int ix=cols.indexOf(v[0]);if(ix<0){cols.add(v[0]);vals.add(v[1]);}else vals.set(ix,v[1]);String query="INSERT INTO public.mop_raw_monitoring_event("+String.join(",",cols)+") VALUES ("+String.join(",",vals)+")";
                try(var s=c.createStatement()){assertEquals("23514",assertThrows(SQLException.class,()->s.executeUpdate(query)).getSQLState(),clause);}c.rollback(point);
            }
            for(String query:List.of("UPDATE public.mop_raw_monitoring_event SET source_event_id='x' WHERE false","DELETE FROM public.mop_raw_monitoring_event WHERE false","TRUNCATE public.mop_raw_monitoring_event")){var point=c.setSavepoint();try(var s=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->s.executeUpdate(query)).getSQLState());}c.rollback(point);}c.rollback();
        }
    }
    @Test void utf16BoundaryMatchesDomainAndFingerprint(){var e=event();var key=new RawMonitoringEvent.Key(e.key().poolId(),"😀".repeat(128),e.key().phase());assertEquals(STORED,repo().store(new RawMonitoringEvent(key,binding,SOURCE,RECEIPT,e.payload())).outcome());}
    @Test void oneMiBPayloadIsPreserved(){var e=event();byte[] bytes=new byte[RawMonitoringEvent.MAX_PAYLOAD_BYTES];Arrays.fill(bytes,(byte)0xfe);var event=changed(e,binding,SOURCE,RECEIPT,bytes);assertEquals(STORED,repo().store(event).outcome());assertEquals(SAME_CONTENT,repo().store(event).outcome());}
    javax.sql.DataSource failingCommit(boolean afterCommit){return (javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{javax.sql.DataSource.class},(proxy,method,args)->{
        if(method.getName().equals("getConnection")){
            Connection real=pool.getConnection();return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("commit")){if(afterCommit)real.commit();throw new SQLException("SYNTHETIC_PRIVATE_DETAILS","08006");}
                try{return m.invoke(real,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        }try{return method.invoke(pool,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    });}
    @Test void failedCommitRollsBackAndNeverReturnsSuccess(){var e=event();var repository=new PostgresRawMonitoringEventRepository(failingCommit(false));var failure=assertThrows(IllegalStateException.class,()->repository.store(e));assertFalse(failure.toString().contains("SYNTHETIC_PRIVATE_DETAILS"));assertEquals(STORED,repo().store(e).outcome());}
    @Test void uncertainCommittedOutcomeIsSafelyReconciledByReplay(){var e=event();assertThrows(IllegalStateException.class,()->new PostgresRawMonitoringEventRepository(failingCommit(true)).store(e));assertEquals(SAME_CONTENT,repo().store(e).outcome());}

    void rejectsCatalogDrift(String sql)throws Exception{
        assertEquals("mop_test_migrator",System.getenv("MOP_MIGRATION_USERNAME"));
        try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("MOP_MIGRATION_USERNAME"),System.getenv("MOP_MIGRATION_PASSWORD"))){
            c.setAutoCommit(false);try{RawMonitoringEventSchemaVerifier.verify(c);try(var statement=c.createStatement()){statement.execute(sql);}assertThrows(IllegalStateException.class,()->RawMonitoringEventSchemaVerifier.verify(c));}finally{c.rollback();}
            RawMonitoringEventSchemaVerifier.verify(c);
        }
    }
    @Test void verifierRejectsWeakenedCheckWithSameCount()throws Exception{rejectsCatalogDrift("ALTER TABLE public.mop_raw_monitoring_event DROP CONSTRAINT mop_raw_monitoring_event_payload_check; ALTER TABLE public.mop_raw_monitoring_event ADD CONSTRAINT mop_raw_monitoring_event_payload_check CHECK(true)");}
    @Test void verifierRejectsTamperedGeneratedExpression()throws Exception{rejectsCatalogDrift("ALTER TABLE public.mop_raw_monitoring_event ALTER COLUMN content_fingerprint SET EXPRESSION AS (public.mop_raw_event_fingerprint(pool_id,source_event_id,source_phase,tenant_id,asset_id,occurred_epoch_second,occurred_nano,payload)||'tampered')");}
    // V37 adds a logged FK dependent. Downgrade that test fixture first in the same
    // rollback-only transaction so this test reaches the target verifier, not PostgreSQL's FK guard.
    @Test void verifierRejectsUnloggedStorage()throws Exception{rejectsCatalogDrift("ALTER TABLE IF EXISTS public.mop_monitoring_event_member SET UNLOGGED; ALTER TABLE public.mop_raw_monitoring_event SET UNLOGGED");}
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
        assertEquals(STORED,new PostgresRawMonitoringEventRepository(source).store(event()).outcome());
        assertEquals(1,observed.get());
    }

    @Test void missingDefaultIsRejected()throws Exception{rejectsCatalogDrift("ALTER TABLE public.mop_raw_monitoring_event ALTER COLUMN storage_status DROP DEFAULT");}
    @Test void inheritedChildIsRejected()throws Exception{rejectsCatalogDrift("CREATE TABLE public.drift_child () INHERITS(public.mop_raw_monitoring_event)");}
    @Test void inheritedParentIsRejected()throws Exception{rejectsCatalogDrift("CREATE TABLE public.drift_parent (); ALTER TABLE public.mop_raw_monitoring_event INHERIT public.drift_parent");}
    @Test void rewriteRuleIsRejected()throws Exception{rejectsCatalogDrift("CREATE RULE drift_rule AS ON UPDATE TO public.mop_raw_monitoring_event DO ALSO NOTHING");}
    @Test void tableDelegationGrantIsRejected()throws Exception{rejectsCatalogDrift("GRANT SELECT ON public.mop_raw_monitoring_event TO mop_test_runtime WITH GRANT OPTION");}
    @Test void columnDelegationGrantIsRejected()throws Exception{rejectsCatalogDrift("GRANT SELECT(payload) ON public.mop_raw_monitoring_event TO mop_test_runtime WITH GRANT OPTION");}
    @Test void functionDelegationGrantIsRejected()throws Exception{rejectsCatalogDrift("GRANT EXECUTE ON FUNCTION public.mop_raw_event_fingerprint(uuid,text,text,uuid,uuid,bigint,integer,bytea) TO mop_test_runtime WITH GRANT OPTION");}
    @Test void maintenancePrivilegeIsRejected()throws Exception{rejectsCatalogDrift("GRANT MAINTAIN ON public.mop_raw_monitoring_event TO mop_test_runtime");}
    @Test void columnReferencePrivilegeIsRejected()throws Exception{rejectsCatalogDrift("GRANT REFERENCES(payload) ON public.mop_raw_monitoring_event TO mop_test_runtime");}

}
