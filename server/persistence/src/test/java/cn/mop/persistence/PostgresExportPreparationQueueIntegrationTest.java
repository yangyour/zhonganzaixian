package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.domain.assetexport.AssetExportJobState.Filters;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only owned synthetic jobs are created. Every test moves its cutoff past earlier queued history. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class PostgresExportPreparationQueueIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001";
    static HikariDataSource pool;static PostgresPlatformRepository platform;static PostgresAssetExportRepository jobs;static ObjectMapper mapper;static String admin;
    @BeforeAll static void connect(){String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(4);pool=new HikariDataSource(cfg);mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);platform=new PostgresPlatformRepository(pool,mapper);jobs=new PostgresAssetExportRepository(pool,platform);admin=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals("padmin")).isPresent()).findFirst().orElseThrow().id();}
    @AfterAll static void close(){if(pool!=null)pool.close();}
    static Access access(){return new Access(admin,TENANT,platform.membership(admin,TENANT).orElseThrow().permissionVersion(),UUID.randomUUID().toString());}
    static Job accept(){return accept(true);}
    static Job accept(boolean apiReceipt){var a=access();String route=apiReceipt?"POST /api/v1/asset-export-jobs":"POST /integration/not-an-api-request";var result=mapper.valueToTree(platform.idempotentAuthorized(admin,TENANT,route,UUID.randomUUID().toString(),"a".repeat(64),a.permissionVersion(),()->jobs.authorizeAcceptance(a),()->{var j=jobs.accept(a,new Filters(null,null,null,"MOP-FAKE-QUEUE-"+UUID.randomUUID()));return Map.of("job_id",j.id(),"status","QUEUED","version",0,"mode","PREPARE_ONLY_NO_PUBLICATION");}));return jobs.get(a,result.path("job_id").asText());}
    static Instant cutoff()throws Exception{try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT clock_timestamp()::timestamptz(3)+interval '1 millisecond',pg_sleep(0.02)")){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    static void waitFor(Instant until)throws Exception{try(var c=pool.getConnection();var s=c.prepareStatement("SELECT pg_sleep(GREATEST(0,EXTRACT(EPOCH FROM (?::timestamptz-clock_timestamp())))+0.02)")){s.setObject(1,until.atOffset(ZoneOffset.UTC));s.execute();}}
    static void prepare(Claim c){var w=jobs.loadWork(c);jobs.prepare(c,AssetExportPreparedFile.create(c.command(),"b".repeat(64),0,w.databaseNow(),new byte[]{1,2,3}));}
    @Test void cutoffLeavesExistingQueueUntouched()throws Exception{Job old=accept();Instant since=cutoff();Job fresh=accept();var claimed=jobs.claimNextUnprepared("prospective-only",Duration.ofSeconds(30),since).orElseThrow();assertEquals(fresh.id(),claimed.jobId());assertEquals(old,jobs.get(access(),old.id()));}
    @Test void internalRepositoryWorkWithoutApiReceiptIsNotAutomaticallyConsumed()throws Exception{Instant since=cutoff();Job internal=accept(false);assertTrue(jobs.claimNextUnprepared("api-only",Duration.ofSeconds(30),since).isEmpty());assertEquals(internal,jobs.get(access(),internal.id()));}
    @Test void preparedBytesAreNeverReclaimedByAutomaticQueue()throws Exception{Instant since=cutoff();Job j=accept();var c=jobs.claimNextUnprepared("prepared-skip",Duration.ofSeconds(2),since).orElseThrow();assertEquals(j.id(),c.jobId());prepare(c);var original=jobs.progress(access(),j.id());waitFor(c.leaseUntil());assertTrue(jobs.claimNextUnprepared("must-not-reclaim",Duration.ofSeconds(30),since).isEmpty());assertEquals(original,jobs.progress(access(),j.id()));}
    @Test void expiredUnpreparedClaimCanRecoverWithNewFence()throws Exception{Instant since=cutoff();Job j=accept();var first=jobs.claimNextUnprepared("first-process",Duration.ofSeconds(1),since).orElseThrow();waitFor(first.leaseUntil());var second=jobs.claimNextUnprepared("replacement-process",Duration.ofSeconds(30),since).orElseThrow();assertEquals(j.id(),second.jobId());assertEquals(first.fencingToken()+1,second.fencingToken());assertEquals(409,assertThrows(DomainException.class,()->jobs.loadWork(first)).status);}
    @Test void activeLeaseIsNotClaimedAgain()throws Exception{Instant since=cutoff();accept();jobs.claimNextUnprepared("active",Duration.ofSeconds(30),since).orElseThrow();assertTrue(jobs.claimNextUnprepared("other",Duration.ofSeconds(30),since).isEmpty());}
    @Test void concurrentConsumersSelectOneWinner()throws Exception{Instant since=cutoff();Job j=accept();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);try{var first=executor.submit(()->{start.await();return jobs.claimNextUnprepared("one",Duration.ofSeconds(30),since);});var second=executor.submit(()->{start.await();return jobs.claimNextUnprepared("two",Duration.ofSeconds(30),since);});start.countDown();var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);assertEquals(1,(a.isPresent()?1:0)+(b.isPresent()?1:0));assertEquals(j.id(),a.orElseGet(b::orElseThrow).jobId());}finally{executor.shutdownNow();}}
    @Test void confirmedInputLimitEndsOnlyCurrentUnpreparedJob()throws Exception{Instant since=cutoff();Job j=accept();var c=jobs.claimNextUnprepared("input-limit",Duration.ofSeconds(30),since).orElseThrow();jobs.rejectPreparationInputLimit(c);var p=jobs.progress(access(),j.id());assertEquals(AssetExportJobState.State.FAILED,p.job().state());assertEquals(AssetExportJobState.Failure.INPUT_LIMIT,p.error());assertNull(p.prepared());assertTrue(jobs.claimNextUnprepared("no-implicit-retry",Duration.ofSeconds(30),since).isEmpty());}
    @Test void preparedFileAndStaleClaimCannotBeConvertedToInputFailure()throws Exception{Instant since=cutoff();Job j=accept();var c=jobs.claimNextUnprepared("retain-file",Duration.ofSeconds(2),since).orElseThrow();prepare(c);var before=jobs.progress(access(),j.id());assertEquals(409,assertThrows(DomainException.class,()->jobs.rejectPreparationInputLimit(c)).status);waitFor(c.leaseUntil());assertEquals(409,assertThrows(DomainException.class,()->jobs.rejectPreparationInputLimit(c)).status);assertEquals(before,jobs.progress(access(),j.id()));}
    @Test void nullAndSubMillisecondCutoffsAreRejectedBeforeQuery(){assertEquals(400,assertThrows(DomainException.class,()->jobs.claimNextUnprepared("queue",Duration.ofSeconds(30),null)).status);assertEquals(400,assertThrows(DomainException.class,()->jobs.claimNextUnprepared("queue",Duration.ofSeconds(30),Instant.parse("2026-10-14T00:00:00.000000001Z"))).status);}
}
