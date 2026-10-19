package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.change.*;
import cn.mop.domain.change.ChangeNoticeApproval.*;
import cn.mop.domain.change.ChangeNoticeApprovalRepository.Access;
import cn.mop.domain.configuration.RuntimeConfigurationRepository.Command;
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

/** Existing explicitly configured real PG only. Never migrates or creates a replacement database. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_CHANGE_NOTICE_APPROVAL_TESTS",matches="true")
class PostgresChangeNoticeApprovalIntegrationTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    static final String KEY=ChangeNoticeApprovalRepository.REVIEW_POLICY_KEY,HASH="a".repeat(64),MARKER="SYNTHETIC_D10_APPROVAL_STORAGE";
    static HikariDataSource pool; static ObjectMapper mapper; static PostgresPlatformRepository platform;
    static PostgresChangeNoticeDraftRepository drafts; static PostgresChangeNoticeApprovalRepository approvals; static PostgresRuntimeConfigurationRepository runtime;
    static String applicant,reviewer,asset; static boolean policyOwned;
    @BeforeAll static void connect()throws Exception {
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url,"Explicit existing test PostgreSQL URL required");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));assertEquals("mop_test_runtime",System.getenv("SPRING_DATASOURCE_USERNAME"));
        HikariConfig cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(6);cfg.setConnectionTimeout(5000);
        pool=new HikariDataSource(cfg); mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        platform=new PostgresPlatformRepository(pool,mapper); drafts=new PostgresChangeNoticeDraftRepository(pool,platform); approvals=new PostgresChangeNoticeApprovalRepository(pool,platform,mapper);runtime=new PostgresRuntimeConfigurationRepository(pool,platform,mapper);
        applicant=platform.users().stream().filter(u->u.displayName().equals("平台管理员（测试身份）")).findFirst().orElseThrow().id();
        reviewer=platform.users().stream().filter(u->u.displayName().equals("配置复核管理员（测试身份）")).findFirst().orElseThrow().id();
        asset=scalar("SELECT id::text FROM public.mop_asset WHERE tenant_id='"+TENANT+"' ORDER BY id LIMIT 1");
        assertEquals(List.of(),approvals.readinessProblems(),"V40 must already be explicitly applied by authorized migration workflow");
        // Do not replace any real/non-test configuration. Check latest including scheduled releases.
        var history=runtime.history(configAccess(),KEY,9007199254740991L);
        assertTrue(history.isEmpty()||history.stream().allMatch(r->MARKER.equals(r.get("reason"))&&applicant.equals(r.get("published_by"))),"Refuse to replace non-test Q09 policy");
        policyOwned=true;configure();
    }
    @AfterAll static void close(){try{if(pool!=null&&policyOwned)disable();}finally{if(pool!=null)pool.close();}}
    static Access access(String user){return new Access(user,TENANT,platform.membership(user,TENANT).orElseThrow().permissionVersion(),UUID.randomUUID().toString());}
    static cn.mop.domain.configuration.RuntimeConfigurationRepository.Access configAccess(){return new cn.mop.domain.configuration.RuntimeConfigurationRepository.Access(TENANT,applicant,access(applicant).permissionVersion(),UUID.randomUUID().toString());}
    static Object command(String actor,Supplier<Object> work){var a=access(actor);return platform.idempotentAuthorized(actor,TENANT,"POST /integration/d10/config",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->{},work);}
    @SuppressWarnings("unchecked") static String approvedPolicy(Object value){
        Map<String,Object> proposal=(Map<String,Object>)command(applicant,()->platform.createConfigurationRevision(TENANT,applicant,KEY,value,MARKER,List.of(Map.of("reference","document-ref:synthetic-d10","title",MARKER,"sha256",HASH))));
        String id=proposal.get("id").toString();command(applicant,()->platform.transitionConfigurationRevision(TENANT,applicant,id,1,"SUBMIT",null));command(reviewer,()->platform.transitionConfigurationRevision(TENANT,reviewer,id,2,"APPROVE",MARKER));
        return id;
    }
    static void configure(){String id=approvedPolicy(Map.of("reviewer_user_ids",List.of(reviewer)));publish(new Command(policyVersion(),"PUBLISH",id,3L,null,"IMMEDIATE",null,null,MARKER));}

    static long policyVersion(){return ((Number)runtime.read(configAccess(),KEY).get("latest_version")).longValue();}
    static void publish(Command cmd){var a=configAccess();platform.idempotentAuthorized(applicant,TENANT,"POST /integration/d10/policy",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->runtime.authorizeCommand(a,KEY),()->runtime.publish(a,KEY,cmd,HASH));}
    static void disable(){publish(new Command(policyVersion(),"DISABLE",null,null,null,"IMMEDIATE",null,null,MARKER));}
    static ChangeNoticeDraftRepository.Access draftAccess(){var a=access(applicant);return new ChangeNoticeDraftRepository.Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId());}
    static ChangeNoticeDraftRepository.Snapshot create(){var a=draftAccess();var draft=new ChangeNoticeDraft(UUID.fromString(TENANT),UUID.fromString(applicant),null,MARKER,Instant.parse("2026-10-20T00:00:00Z"),Instant.parse("2026-10-20T01:00:00Z"),List.of(UUID.fromString(asset)));
        return mapper.convertValue(platform.idempotentAuthorized(applicant,TENANT,"POST /integration/d10/draft",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->drafts.authorizeCurrentAccess(a,null),()->drafts.create(a,draft)),ChangeNoticeDraftRepository.Snapshot.class);
    }
    static Snapshot transition(Access a,String id,long revision,long expected,Action action,String note,String key){return mapper.convertValue(platform.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/d10/"+id+"/"+revision+"/"+action,key,HASH,a.permissionVersion(),()->approvals.authorizeCurrentAccess(a,id,revision,action),()->approvals.transition(a,id,revision,expected,action,note)),Snapshot.class);}
    static Snapshot act(String actor,String id,long expected,Action action){return transition(access(actor),id,1,expected,action,action==Action.REJECT?"SYNTHETIC rejection":null,UUID.randomUUID().toString());}
    static String scalar(String sql)throws SQLException {try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    @Test void exactRevisionApprovalSurvivesRepositoryRecreationWithoutActivating(){
        var draft=create();assertEquals(State.DRAFT,approvals.get(access(applicant),draft.id(),1).state());act(applicant,draft.id(),1,Action.SUBMIT);var approved=act(reviewer,draft.id(),2,Action.APPROVE);
        var restarted=new PostgresChangeNoticeApprovalRepository(pool,new PostgresPlatformRepository(pool,mapper),mapper);
        assertEquals(approved,restarted.get(access(applicant),draft.id(),1));assertEquals(draft.draft().fingerprint(),approved.fingerprint());assertFalse(approved.effective());assertFalse(approved.deviceExecutionAllowed());assertFalse(approved.alertSuppressionAllowed());
        var a=draftAccess();var changed=new ChangeNoticeDraft(draft.draft().tenantId(),draft.draft().applicantId(),null,MARKER+" changed",draft.draft().plannedStartAt(),draft.draft().plannedEndAt(),draft.draft().assetIds());
        platform.idempotentAuthorized(applicant,TENANT,"PATCH /integration/d10/draft",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->drafts.authorizeCurrentAccess(a,draft.id()),()->drafts.update(a,draft.id(),1,changed));
        assertEquals(approved,restarted.get(access(applicant),draft.id(),1));assertEquals(State.DRAFT,restarted.get(access(applicant),draft.id(),2).state());
        assertEquals(List.of(3L,2L),restarted.history(access(applicant),draft.id(),1,10,null).stream().map(Event::version).toList());
        assertEquals(List.of(2L),restarted.history(access(applicant),draft.id(),1,10,3L).stream().map(Event::version).toList());
    }
    @Test void changedOrArchivedHeadBlocksStaleApprovalAndDirectSql()throws Exception {
        for(boolean archive:List.of(false,true)) {
            var d=create();act(applicant,d.id(),1,Action.SUBMIT);var a=draftAccess();
            platform.idempotentAuthorized(applicant,TENANT,"PATCH /integration/d10/stale-head",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->drafts.authorizeCurrentAccess(a,d.id()),()->archive?drafts.archive(a,d.id(),1):drafts.update(a,d.id(),1,d.draft()));
            assertEquals(409,assertThrows(DomainException.class,()->act(reviewer,d.id(),2,Action.APPROVE)).status);
            try(var c=pool.getConnection()){c.setAutoCommit(false);try(var s=c.prepareStatement("INSERT INTO public.mop_change_notice_approval_event(tenant_id,notice_id,draft_revision,content_sha256,version,from_state,to_state,action,actor_id,permission_version) VALUES (?::uuid,?::uuid,1,?,3,'IN_REVIEW','WITHDRAWN','WITHDRAW',?::uuid,?)")) {
                s.setString(1,TENANT);s.setString(2,d.id());s.setString(3,d.draft().fingerprint());s.setString(4,applicant);s.setLong(5,a.permissionVersion());assertThrows(SQLException.class,s::executeUpdate);
            }finally{c.rollback();}}
            assertEquals(State.IN_REVIEW,approvals.get(access(applicant),d.id(),1).state());
        }
    }
    @Test void replayAndCasAreProtected(){var draft=create();var a=access(applicant);String key=UUID.randomUUID().toString();var submitted=transition(a,draft.id(),1,1,Action.SUBMIT,null,key);assertEquals(submitted,transition(a,draft.id(),1,1,Action.SUBMIT,null,key));assertEquals(submitted,approvals.get(a,draft.id(),1));assertEquals(submitted.submittedAt(),approvals.history(a,draft.id(),1,10,null).get(0).occurredAt());assertEquals(409,assertThrows(DomainException.class,()->act(applicant,draft.id(),1,Action.WITHDRAW)).status);assertEquals(1,approvals.history(a,draft.id(),1,10,null).size());}
    @Test void selfReviewOtherApplicantAndStaleMembershipAreRejected(){var d=create();act(applicant,d.id(),1,Action.SUBMIT);assertEquals(403,assertThrows(DomainException.class,()->act(applicant,d.id(),2,Action.APPROVE)).status);assertEquals(403,assertThrows(DomainException.class,()->act(reviewer,d.id(),2,Action.WITHDRAW)).status);var a=access(applicant);var stale=new Access(a.actorId(),a.tenantId(),a.permissionVersion()+1,a.traceId());assertEquals(401,assertThrows(DomainException.class,()->approvals.get(stale,d.id(),1)).status);var foreign=new Access(applicant,OTHER,platform.membership(applicant,OTHER).orElseThrow().permissionVersion(),a.traceId());assertEquals(404,assertThrows(DomainException.class,()->approvals.get(foreign,d.id(),1)).status);}
    @Test void disabledPolicyBlocksReviewAndReceiptReplay(){var d=create();act(applicant,d.id(),1,Action.SUBMIT);String key=UUID.randomUUID().toString();var a=access(reviewer);transition(a,d.id(),1,2,Action.APPROVE,null,key);disable();try{assertEquals(422,assertThrows(DomainException.class,()->transition(a,d.id(),1,2,Action.APPROVE,null,key)).status);}finally{configure();}}
    static void directReviewRejected(String notice,String fingerprint,String policy)throws Exception {
        try(var c=pool.getConnection()){c.setAutoCommit(false);try(var s=c.prepareStatement("INSERT INTO public.mop_change_notice_approval_event(tenant_id,notice_id,draft_revision,content_sha256,version,from_state,to_state,action,actor_id,permission_version,review_policy_release_id) VALUES (?::uuid,?::uuid,1,?,3,'IN_REVIEW','APPROVED','APPROVE',?::uuid,?,?::uuid)")) {
            s.setString(1,TENANT);s.setString(2,notice);s.setString(3,fingerprint);s.setString(4,reviewer);s.setLong(5,access(reviewer).permissionVersion());s.setString(6,policy);assertThrows(SQLException.class,s::executeUpdate);
        }finally{c.rollback();}}
    }
    @Test void malformedPolicyBlocksRepositoryAndDirectSql()throws Exception {
        var d=create();act(applicant,d.id(),1,Action.SUBMIT);
        String id=approvedPolicy(Map.of("role","padmin"));publish(new Command(policyVersion(),"PUBLISH",id,3L,null,"IMMEDIATE",null,null,MARKER));
        try {assertEquals(422,assertThrows(DomainException.class,()->act(reviewer,d.id(),2,Action.APPROVE)).status);
            String policy=((Map<?,?>)runtime.read(configAccess(),KEY).get("selected_release")).get("id").toString();directReviewRejected(d.id(),d.draft().fingerprint(),policy);
        }finally{configure();}
    }
    @Test void scheduledOnlyPolicyDoesNotRestoreDisabledAuthority()throws Exception {
        var d=create();act(applicant,d.id(),1,Action.SUBMIT);String old=((Map<?,?>)runtime.read(configAccess(),KEY).get("selected_release")).get("id").toString();disable();
        String id=approvedPolicy(Map.of("reviewer_user_ids",List.of(reviewer)));Instant future=Instant.parse(runtime.read(configAccess(),KEY).get("observed_at").toString()).plusSeconds(86400);
        publish(new Command(policyVersion(),"PUBLISH",id,3L,null,"SCHEDULED",future,null,MARKER));
        try {assertEquals(422,assertThrows(DomainException.class,()->act(reviewer,d.id(),2,Action.APPROVE)).status);directReviewRejected(d.id(),d.draft().fingerprint(),old);}finally{configure();}
    }
    @Test void expiredNewestReleaseDoesNotResurrectOlderAuthority()throws Exception {
        var d=create();act(applicant,d.id(),1,Action.SUBMIT);String old=((Map<?,?>)runtime.read(configAccess(),KEY).get("selected_release")).get("id").toString();
        String id=approvedPolicy(Map.of("reviewer_user_ids",List.of(reviewer)));Instant expires=Instant.parse(runtime.read(configAccess(),KEY).get("observed_at").toString()).plusSeconds(2);
        publish(new Command(policyVersion(),"PUBLISH",id,3L,null,"IMMEDIATE",null,expires,MARKER));
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!"EXPIRED".equals(runtime.read(configAccess(),KEY).get("status"))&&System.nanoTime()<deadline)Thread.sleep(20);
            assertEquals("EXPIRED",runtime.read(configAccess(),KEY).get("status"));assertEquals(422,assertThrows(DomainException.class,()->act(reviewer,d.id(),2,Action.APPROVE)).status);directReviewRejected(d.id(),d.draft().fingerprint(),old);
        }finally{configure();}
    }
    @Test void failedCallerRollsBackEventAuditAndReceipt(){var d=create();var a=access(applicant);String count;
        try{count=scalar("SELECT count(*)::text FROM public.mop_audit");}catch(SQLException e){throw new RuntimeException(e);}
        assertThrows(IllegalStateException.class,()->platform.idempotentAuthorized(applicant,TENANT,"POST /integration/d10/rollback",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->approvals.authorizeCurrentAccess(a,d.id(),1,Action.SUBMIT),()->{approvals.transition(a,d.id(),1,1,Action.SUBMIT,null);throw new IllegalStateException("synthetic failure");}));
        assertEquals(State.DRAFT,approvals.get(a,d.id(),1).state());try{assertEquals(count,scalar("SELECT count(*)::text FROM public.mop_audit"));}catch(SQLException e){throw new RuntimeException(e);}
    }
    @Test void concurrentDifferentKeysWithSameExpectedVersionHaveOneWinner()throws Exception {var d=create();var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);try{Callable<Integer> work=()->{gate.await();try{act(applicant,d.id(),1,Action.SUBMIT);return 200;}catch(DomainException e){return e.status;}};var first=executor.submit(work);var second=executor.submit(work);gate.countDown();var results=new ArrayList<>(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)));Collections.sort(results);assertEquals(List.of(200,409),results);}finally{executor.shutdownNow();}}
    static Object pause(Object value,CountDownLatch entered,CountDownLatch release) {
        entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("Race transaction release timed out");return value;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    static void blockedThenRejected(ExecutorService executor,Future<?> blocker,CountDownLatch entered,CountDownLatch release,Supplier<Object> contender,int status)throws Exception {
        assertTrue(entered.await(10,TimeUnit.SECONDS));var attempted=new CountDownLatch(1);Future<Integer> result=executor.submit(()->{attempted.countDown();try{contender.get();return 200;}catch(DomainException e){return e.status;}});
        assertTrue(attempted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->result.get(150,TimeUnit.MILLISECONDS),"Contender must wait for the held transaction lock");
        release.countDown();blocker.get(15,TimeUnit.SECONDS);assertEquals(status,result.get(15,TimeUnit.SECONDS));
    }
    @Test void policyDisableWinsAgainstWaitingReview()throws Exception {
        var d=create();act(applicant,d.id(),1,Action.SUBMIT);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        var a=configAccess();var cmd=new Command(policyVersion(),"DISABLE",null,null,null,"IMMEDIATE",null,null,MARKER);
        try {var blocker=executor.submit(()->platform.idempotentAuthorized(applicant,TENANT,"POST /integration/d10/disable-race",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->runtime.authorizeCommand(a,KEY),()->pause(runtime.publish(a,KEY,cmd,HASH),entered,release)));
            blockedThenRejected(executor,blocker,entered,release,()->act(reviewer,d.id(),2,Action.APPROVE),422);
        }finally{release.countDown();executor.shutdown();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));configure();}
        assertEquals(State.IN_REVIEW,approvals.get(access(applicant),d.id(),1).state());
    }
    @Test void draftEditWinsAgainstWaitingReview()throws Exception {
        var d=create();act(applicant,d.id(),1,Action.SUBMIT);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);var a=draftAccess();
        try {var blocker=executor.submit(()->platform.idempotentAuthorized(applicant,TENANT,"PATCH /integration/d10/edit-race",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->drafts.authorizeCurrentAccess(a,d.id()),()->pause(drafts.update(a,d.id(),1,d.draft()),entered,release)));
            blockedThenRejected(executor,blocker,entered,release,()->act(reviewer,d.id(),2,Action.APPROVE),409);
        }finally{release.countDown();executor.shutdown();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
        assertEquals(State.IN_REVIEW,approvals.get(access(applicant),d.id(),1).state());
    }
    @Test void membershipRevocationWinsAgainstWaitingReview()throws Exception {
        var d=create();act(applicant,d.id(),1,Action.SUBMIT);Access original=access(reviewer);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {var blocker=executor.submit(()->{
            try(var c=pool.getConnection()){c.setAutoCommit(false);try(var s=c.prepareStatement("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid AND active")) {
                s.setString(1,TENANT);s.setString(2,reviewer);assertEquals(1,s.executeUpdate());pause(null,entered,release);c.commit();
            }catch(Throwable failure){c.rollback();throw failure;}}return null;
        });blockedThenRejected(executor,blocker,entered,release,()->transition(original,d.id(),1,2,Action.APPROVE,null,UUID.randomUUID().toString()),401);
        }finally{
            release.countDown();executor.shutdown();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));
            try(var c=pool.getConnection();var s=c.prepareStatement("UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid AND NOT active")){s.setString(1,TENANT);s.setString(2,reviewer);s.executeUpdate();}
        }
        assertEquals(State.IN_REVIEW,approvals.get(access(applicant),d.id(),1).state());
    }
    @Test void sqlRuntimeCannotRewriteDeleteOrForgeBinding()throws Exception {var d=create();act(applicant,d.id(),1,Action.SUBMIT);
        for(String sql:List.of("UPDATE public.mop_change_notice_approval_event SET note='tamper' WHERE notice_id='"+d.id()+"'","DELETE FROM public.mop_change_notice_approval_event WHERE notice_id='"+d.id()+"'","INSERT INTO public.mop_change_notice_approval_event(tenant_id,notice_id,draft_revision,content_sha256,version,from_state,to_state,action,actor_id,permission_version) VALUES ('"+TENANT+"','"+d.id()+"',1,'"+"0".repeat(64)+"',3,'IN_REVIEW','WITHDRAWN','WITHDRAW','"+applicant+"',"+access(applicant).permissionVersion()+")")) {
            try(var c=pool.getConnection()){c.setAutoCommit(false);try(var s=c.createStatement()){assertThrows(SQLException.class,()->s.execute(sql));}finally{c.rollback();}}
        }
        assertEquals(State.IN_REVIEW,approvals.get(access(applicant),d.id(),1).state());
    }
    @Test void serverDefaultTimestampIsMillisecondAlignedAndNeverRoundsIntoFuture()throws Exception {
        var d=create();
        for(int i=0;i<20;i++)try(var c=pool.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("INSERT INTO public.mop_change_notice_approval_event(tenant_id,notice_id,draft_revision,content_sha256,version,from_state,to_state,action,actor_id,permission_version) VALUES (?::uuid,?::uuid,1,?,2,'DRAFT','IN_REVIEW','SUBMIT',?::uuid,?) RETURNING occurred_at<=clock_timestamp(),mod(extract(microseconds FROM occurred_at),1000)=0")) {
                s.setString(1,TENANT);s.setString(2,d.id());s.setString(3,d.draft().fingerprint());s.setString(4,applicant);s.setLong(5,access(applicant).permissionVersion());
                try(var r=s.executeQuery()){assertTrue(r.next());assertTrue(r.getBoolean(1));assertTrue(r.getBoolean(2));}
            }finally{c.rollback();}
        }
        assertEquals(State.DRAFT,approvals.get(access(applicant),d.id(),1).state());
    }
    @Test void databaseReplacesCallerBackdateAndFutureWithAuthoritativeReviewTime()throws Exception {
        var d=create();act(applicant,d.id(),1,Action.SUBMIT);String policy=((Map<?,?>)runtime.read(configAccess(),KEY).get("selected_release")).get("id").toString();
        for(String supplied:List.of("2000-01-01T00:00:00Z","9999-12-31T15:59:59.999Z"))try(var c=pool.getConnection()) {
            c.setAutoCommit(false);
            try {Instant start;try(var q=c.createStatement();var r=q.executeQuery("SELECT date_trunc('milliseconds',clock_timestamp())")){assertTrue(r.next());start=r.getObject(1,OffsetDateTime.class).toInstant();}
                try(var q=c.prepareStatement("INSERT INTO public.mop_change_notice_approval_event(tenant_id,notice_id,draft_revision,content_sha256,version,from_state,to_state,action,actor_id,permission_version,review_policy_release_id,occurred_at) VALUES (?::uuid,?::uuid,1,?,3,'IN_REVIEW','APPROVED','APPROVE',?::uuid,?,?::uuid,?) RETURNING occurred_at,occurred_at<=clock_timestamp()")) {
                    q.setString(1,TENANT);q.setString(2,d.id());q.setString(3,d.draft().fingerprint());q.setString(4,reviewer);q.setLong(5,access(reviewer).permissionVersion());q.setString(6,policy);q.setObject(7,OffsetDateTime.parse(supplied));
                    try(var r=q.executeQuery()){assertTrue(r.next());Instant actual=r.getObject(1,OffsetDateTime.class).toInstant();assertFalse(actual.isBefore(start));assertNotEquals(Instant.parse(supplied),actual);assertTrue(r.getBoolean(2));}
                }
            }finally{c.rollback();}
        }
        assertEquals(State.IN_REVIEW,approvals.get(access(applicant),d.id(),1).state());
    }
    @Test void viewDerivesCurrentActionsAndDoesNotAppendAuditOrReceipts()throws Exception {
        var d=create();String audit=scalar("SELECT count(*)::text FROM public.mop_audit"),receipts=scalar("SELECT count(*)::text FROM public.mop_idempotency");
        assertEquals(List.of(Action.SUBMIT,Action.WITHDRAW),approvals.view(access(applicant),d.id(),1).allowedActions());assertEquals(List.of(),approvals.view(access(reviewer),d.id(),1).allowedActions());
        assertEquals(audit,scalar("SELECT count(*)::text FROM public.mop_audit"));assertEquals(receipts,scalar("SELECT count(*)::text FROM public.mop_idempotency"));
        act(applicant,d.id(),1,Action.SUBMIT);assertEquals(List.of(Action.WITHDRAW),approvals.view(access(applicant),d.id(),1).allowedActions());assertEquals(List.of(Action.APPROVE,Action.REJECT),approvals.view(access(reviewer),d.id(),1).allowedActions());
        act(reviewer,d.id(),2,Action.APPROVE);assertEquals(List.of(),approvals.view(access(applicant),d.id(),1).allowedActions());assertEquals(List.of(),approvals.view(access(reviewer),d.id(),1).allowedActions());
    }
    @Test void viewNeverOffersActionsForArchivedOrSupersededRevision(){
        var d=create();var a=draftAccess();platform.idempotentAuthorized(applicant,TENANT,"POST /integration/d10/view-archive",UUID.randomUUID().toString(),HASH,a.permissionVersion(),()->drafts.authorizeCurrentAccess(a,d.id()),()->drafts.archive(a,d.id(),1));
        assertEquals(List.of(),approvals.view(access(applicant),d.id(),1).allowedActions());assertEquals(List.of(),approvals.view(access(applicant),d.id(),2).allowedActions());
    }
    @Test void viewFailsClosedForMissingCurrentPolicy(){var d=create();act(applicant,d.id(),1,Action.SUBMIT);disable();try{assertEquals(List.of(),approvals.view(access(reviewer),d.id(),1).allowedActions());assertEquals(List.of(Action.WITHDRAW),approvals.view(access(applicant),d.id(),1).allowedActions());}finally{configure();}}
    @Test void verifierRejectsSameCountWeakenedCheckAndRollbackRestoresSchema()throws Exception {
        Assumptions.assumeTrue("mop_test_migrator".equals(System.getenv("MOP_MIGRATION_USERNAME"))&&System.getenv("MOP_MIGRATION_PASSWORD")!=null,"Explicit existing test migrator credentials required for transactional schema-tampering test");
        try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("MOP_MIGRATION_USERNAME"),System.getenv("MOP_MIGRATION_PASSWORD"))) {
            c.setAutoCommit(false);
            try {
                try(var settings=c.createStatement()){settings.execute("SET LOCAL TIME ZONE 'Asia/Shanghai'");settings.execute("SET LOCAL DateStyle='ISO, DMY'");}
                ChangeNoticeApprovalSchemaVerifier.verify(c);String constraint;
                try(var settings=c.createStatement();var rows=settings.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")){assertTrue(rows.next());assertEquals("Asia/Shanghai",rows.getString(1));assertEquals("ISO, DMY",rows.getString(2));}
                try(var q=c.createStatement();var r=q.executeQuery("SELECT c.conname FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND c.conkey=ARRAY[a.attnum] WHERE c.conrelid='public.mop_change_notice_approval_event'::regclass AND c.contype='c' AND a.attname='effective'")){assertTrue(r.next());constraint=r.getString(1);assertFalse(r.next());}
                assertTrue(constraint.matches("[a-z0-9_]+"));
                try(var q=c.createStatement()){q.execute("ALTER TABLE public.mop_change_notice_approval_event DROP CONSTRAINT "+constraint+", ADD CONSTRAINT "+constraint+" CHECK (true)");}
                assertThrows(IllegalStateException.class,()->ChangeNoticeApprovalSchemaVerifier.verify(c));
            }catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
            c.rollback();
            ChangeNoticeApprovalSchemaVerifier.verify(c);
        }
    }
    @Test void unprotectedWritesAreRejected(){var d=create();assertThrows(IllegalStateException.class,()->approvals.transition(access(applicant),d.id(),1,1,Action.SUBMIT,null));}
}
