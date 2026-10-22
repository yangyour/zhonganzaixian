package cn.mop.worker.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.*;
import cn.mop.persistence.PostgresBackupArchiveRepository.*;
import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.adapters.backup.BackupEnvelopeArchive;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.worker.backup.BackupArchiveDispatch.*;

/** Actual isolated PostgreSQL, real JCE, synthetic scope authority/key provider/object storage. */
@EnabledIfEnvironmentVariable(named="MOP_BACKUP_ARCHIVE_DISPATCH_PG_TEST_ENVIRONMENT",matches="dot-backup-dispatch-disposable")
class BackupArchiveDispatchPostgresTest {
 static HikariDataSource runtime,migrator;static UUID tenant,actor;static String site;
 final AtomicBoolean allowed=new AtomicBoolean(true);final MemoryStore store=new MemoryStore();
 BackupEnvelopeEncryption crypto;BackupEnvelopeArchive archive;Access access;
 @BeforeAll static void database()throws Exception{
  String url=System.getenv("SPRING_DATASOURCE_URL");if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"))throw new IllegalStateException("Isolated database only");
  runtime=dataSource(url,"mop_test_runtime",System.getenv("SPRING_DATASOURCE_PASSWORD"));migrator=dataSource(url,"mop_test_migrator",System.getenv("MOP_MIGRATION_PASSWORD"));
  try(var c=migrator.getConnection()){
   try(var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")){assertTrue(r.next());assertEquals(50,r.getInt(1));}
   try(var q=c.createStatement();var r=q.executeQuery("SELECT tenant_id,id FROM mop_site ORDER BY tenant_id,id LIMIT 1")){assertTrue(r.next());tenant=r.getObject(1,UUID.class);site=r.getString(2);}
   try(var q=c.createStatement();var r=q.executeQuery("SELECT id FROM mop_user WHERE role_code='padmin' ORDER BY id LIMIT 1")){assertTrue(r.next());actor=r.getObject(1,UUID.class);}
  }
 }
 static HikariDataSource dataSource(String url,String user,String password){var c=new HikariConfig();c.setJdbcUrl(url);c.setUsername(user);c.setPassword(password);c.setMaximumPoolSize(4);c.setMinimumIdle(0);c.setConnectionTimeout(5000);return new HikariDataSource(c);}
 @AfterAll static void close(){if(runtime!=null)runtime.close();if(migrator!=null)migrator.close();}
 @BeforeEach void crypto(){access=new Access(tenant,actor,1);var provider=new BackupEnvelopeEncryption.DataKeyProvider(){
  public BackupEnvelopeEncryption.KeyMaterial create(BackupEnvelopeEncryption.Access a,BackupEnvelopeEncryption.Scope s){return key();}
  public BackupEnvelopeEncryption.KeyMaterial unwrap(BackupEnvelopeEncryption.Access a,BackupEnvelopeEncryption.Scope s,String ref,byte[] wrapped){if(!ref.equals("kms-key-ref:synthetic-dispatch-pg")||!Arrays.equals(wrapped,new byte[]{9}))throw new IllegalArgumentException("synthetic key mismatch");return key();}
  BackupEnvelopeEncryption.KeyMaterial key(){byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)27);return new BackupEnvelopeEncryption.KeyMaterial("kms-key-ref:synthetic-dispatch-pg",new byte[]{9},bytes);}
 };crypto=new BackupEnvelopeEncryption(provider,(a,s,o)->allowed.get()&&a.tenantId().equals(tenant)&&a.actorId().equals(actor),1024);archive=new BackupEnvelopeArchive(crypto,store,(a,s,o)->allowed.get()&&a.tenantId().equals(tenant)&&a.actorId().equals(actor));}
 final class MemoryStore implements BackupEnvelopeArchive.ObjectStore {
  final ConcurrentMap<UUID,byte[]> objects=new ConcurrentHashMap<>();final AtomicInteger puts=new AtomicInteger(),reads=new AtomicInteger();
  volatile boolean losePutResponse;volatile CountDownLatch entered,release;
  public boolean ready(){return true;}
  public void storeImmutable(BackupEnvelopeEncryption.Envelope e){puts.incrementAndGet();if(entered!=null){entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("synthetic release timeout");}catch(InterruptedException x){Thread.currentThread().interrupt();throw new IllegalStateException(x);}}if(objects.putIfAbsent(e.scope().versionId(),e.encoded())!=null)throw new IllegalStateException("synthetic immutable object exists");if(losePutResponse)throw new IllegalStateException("synthetic PUT committed but reply lost");}
  public byte[] read(BackupEnvelopeEncryption.Scope s,long bytes,String sha){reads.incrementAndGet();var value=objects.get(s.versionId());if(value==null)throw new IllegalStateException("synthetic object not observed");return value.clone();}
 }
 void authorize(Access a,Scope s){if(!allowed.get()||!a.equals(access)||!s.tenantId().equals(tenant))throw new DomainException(403,"SYNTHETIC_SCOPE_DENIED","synthetic");}
 BackupEnvelopeEncryption.Scope cryptoScope(Scope s){return new BackupEnvelopeEncryption.Scope(s.tenantId(),s.assetId(),s.runId(),s.attemptNumber(),s.versionId());}
 BackupEnvelopeEncryption.Access cryptoAccess(){return new BackupEnvelopeEncryption.Access(tenant,actor,1);}
 PostgresBackupArchiveRepository journal(){return new PostgresBackupArchiveRepository(runtime,(c,a,s,o)->authorize(a,s),(a,p)->{authorize(a,p.scope());byte[] plain=crypto.open(cryptoAccess(),cryptoScope(p.scope()),crypto.decode(p.bytes(),p.sha256(),cryptoScope(p.scope())));Arrays.fill(plain,(byte)0);},(a,source,prior,operation,ref)->{
  authorize(a,source.payload().scope());if(operation!=Action.VERIFY_OBJECT||!ref.equals("sha256:"+source.payload().sha256()))throw new DomainException(403,"SYNTHETIC_PROOF_DENIED","synthetic");
  archive.verifyExisting(cryptoAccess(),cryptoScope(source.payload().scope()),source.payload().size(),source.payload().sha256());
 });}
 BackupArchiveDispatch consumer(){return new BackupArchiveDispatch(new Binding(true,"backup-archive-dispatch:synthetic-pg"),(b,a,s,step)->authorize(a,s),journal(),crypto,archive);}
 PostgresBackupRunRepository runs(){return new PostgresBackupRunRepository(runtime,(c,t,a,r)->{if(!t.equals(tenant))throw new DomainException(403,"SYNTHETIC_SCOPE_DENIED","synthetic");},(c,r,s,l)->{if(!l.owner().equals("synthetic-dispatch"))throw new DomainException(403,"SYNTHETIC_START_DENIED","synthetic");},(c,r,s,o,f,v,e)->{throw new DomainException(403,"NO_COMPLETION_IN_THIS_TEST","synthetic");});}
 Command staged()throws Exception{
  UUID asset=UUID.randomUUID(),run=UUID.randomUUID();try(var c=migrator.getConnection();var q=c.prepareStatement("INSERT INTO mop_asset(id,tenant_id,name,type,management_ip,site_id,source) VALUES(?,?,?,'router','192.0.2.77',?,'SYNTHETIC_DISPATCH_TEST')")){q.setObject(1,asset);q.setObject(2,tenant);q.setString(3,"synthetic-dispatch-"+asset);q.setString(4,site);assertEquals(1,q.executeUpdate());}
  var leases=new PostgresBackupDeviceLeaseRepository(runtime,(c,t,a,r)->{if(!t.equals(tenant))throw new DomainException(403,"SYNTHETIC_SCOPE_DENIED","synthetic");},(c,s,e)->{throw new DomainException(403,"NO_DEVICE_STOP_PROOF","synthetic");});
  var lease=leases.acquire(tenant,asset,UUID.randomUUID(),"synthetic-dispatch",Duration.ofMinutes(5),"synthetic-dispatch-lease");var claim=new PostgresBackupRunRepository.LeaseClaim(lease.state().requestId(),"synthetic-dispatch",lease.state().fencingToken());
  runs().queue(tenant,asset,run,UUID.randomUUID(),"synthetic-queue");runs().start(tenant,asset,run,UUID.randomUUID(),0,claim,"synthetic-start");
  var scope=new Scope(tenant,asset,run,1,UUID.randomUUID());var encrypted=crypto.seal(cryptoAccess(),cryptoScope(scope),"SYNTHETIC CONFIGURATION ONLY".getBytes(java.nio.charset.StandardCharsets.UTF_8));var payload=BackupArchivePayload.parse(encrypted.encoded(),encrypted.sha256());
  assertEquals(0,journal().stage(access,UUID.randomUUID(),1,payload,"synthetic-stage").event().version());return new Command(access,scope,0,new Dispatch(UUID.randomUUID(),"synthetic-dispatch"),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"sha256:"+payload.sha256(),"synthetic-consume");
 }
 long events(Command cmd)throws Exception{try(var c=runtime.getConnection();var q=c.prepareStatement("SELECT count(*) FROM mop_backup_archive_event WHERE tenant_id=? AND version_id=?")){q.setObject(1,tenant);q.setObject(2,cmd.scope().versionId());try(var r=q.executeQuery()){r.next();return r.getLong(1);}}}
 void noCompletion(Command cmd)throws Exception{assertEquals(cn.mop.domain.backup.BackupRunState.State.RUNNING,runs().current(tenant,cmd.scope().assetId(),cmd.scope().runId()).orElseThrow().state().state());try(var c=runtime.getConnection();var q=c.prepareStatement("SELECT count(*) FROM mop_backup_version_registration WHERE tenant_id=? AND version_id=?")){q.setObject(1,tenant);q.setObject(2,cmd.scope().versionId());try(var r=q.executeQuery()){r.next();assertEquals(0,r.getLong(1));}}}
 @Test void actualJournalOriginalWriterAndVerificationCommitWithoutRunCompletion()throws Exception{var cmd=staged();var original=journal().current(access,cmd.scope()).orElseThrow().source().payload();var result=consumer().once(cmd);assertEquals(Status.ARCHIVE_VERIFICATION_RECORDED,result.status());assertEquals(2L,result.archiveEventVersion());assertEquals(3,events(cmd));assertEquals(1,store.puts.get());assertArrayEquals(original.bytes(),store.objects.get(cmd.scope().versionId()));noCompletion(cmd);var again=consumer().once(cmd);assertFalse(again.storeMethodInvokedThisCall());assertEquals(1,store.puts.get());assertEquals(3,events(cmd));}
 @Test void committedPutWithLostReplyRecoversAcrossNewConsumerWithoutSecondPut()throws Exception{var cmd=staged();store.losePutResponse=true;assertEquals(Status.RECONCILIATION_REQUIRED,consumer().once(cmd).status());assertEquals(Phase.VERIFY_REQUIRED,journal().current(access,cmd.scope()).orElseThrow().event().phase());store.losePutResponse=false;assertEquals(Status.ARCHIVE_VERIFICATION_RECORDED,consumer().once(cmd).status());assertEquals(1,store.puts.get());assertEquals(4,events(cmd));noCompletion(cmd);}
 @Test void existingDispatchWithNoObservedObjectNeverAuthorizesNewPut()throws Exception{var cmd=staged();journal().startStore(access,cmd.scope(),cmd.startCommand(),0,cmd.dispatch(),cmd.trace());assertEquals(Status.RECONCILIATION_REQUIRED,consumer().once(cmd).status());assertEquals(0,store.puts.get());assertEquals(Phase.VERIFY_REQUIRED,journal().current(access,cmd.scope()).orElseThrow().event().phase());long before=events(cmd);assertEquals(Status.RECONCILIATION_REQUIRED,consumer().once(cmd).status());assertEquals(before,events(cmd));assertEquals(0,store.puts.get());noCompletion(cmd);}
 @Test void concurrentConsumerDuringBlockedPutCanOnlyReconcileAndNeverDuplicatesStore()throws Exception{var cmd=staged();store.entered=new CountDownLatch(1);store.release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();try{var first=pool.submit(()->consumer().once(cmd));assertTrue(store.entered.await(10,TimeUnit.SECONDS));assertEquals(Status.RECONCILIATION_REQUIRED,consumer().once(cmd).status());store.release.countDown();assertEquals(Status.RECONCILIATION_REQUIRED,first.get(15,TimeUnit.SECONDS).status());assertEquals(Status.ARCHIVE_VERIFICATION_RECORDED,consumer().once(cmd).status());assertEquals(1,store.puts.get());assertEquals(4,events(cmd));noCompletion(cmd);}finally{store.release.countDown();pool.shutdownNow();}}
 @Test void revokedAuthorityAfterClaimBlocksWriteAndCannotPretendNoDispatch()throws Exception{var cmd=staged();var c=new BackupArchiveDispatch(new Binding(true,"backup-archive-dispatch:synthetic-pg"),(b,a,s,step)->{if(step==Step.STORE_ORIGINAL)allowed.set(false);authorize(a,s);},journal(),crypto,archive);assertThrows(DomainException.class,()->c.once(cmd));assertEquals(0,store.puts.get());allowed.set(true);assertEquals(Phase.STORE_STARTED,journal().current(access,cmd.scope()).orElseThrow().event().phase());assertEquals(Status.RECONCILIATION_REQUIRED,consumer().once(cmd).status());assertEquals(0,store.puts.get());noCompletion(cmd);}
}
