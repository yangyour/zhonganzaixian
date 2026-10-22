package cn.mop.worker.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupRunState;
import cn.mop.persistence.PostgresBackupVersionRepository;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.worker.backup.BackupVersionRegistrationDispatch.*;

/** Actual disposable PostgreSQL + JCE; existing archive fixture and explicitly synthetic collection authority. */
@EnabledIfEnvironmentVariable(named="MOP_BACKUP_VERSION_DISPATCH_PG_TEST_ENVIRONMENT",matches="dot-backup-version-dispatch-disposable")
class BackupVersionRegistrationDispatchPostgresTest {
 final BackupArchiveDispatchPostgresTest fixture=new BackupArchiveDispatchPostgresTest();
 final AtomicBoolean completionAllowed=new AtomicBoolean(true);final AtomicInteger objectChecks=new AtomicInteger(),completionChecks=new AtomicInteger();
 static final String EXECUTION="sha256:"+"f".repeat(64);
 @BeforeAll static void database()throws Exception{BackupArchiveDispatchPostgresTest.database();}
 @AfterAll static void close(){BackupArchiveDispatchPostgresTest.close();}
 @BeforeEach void init(){fixture.crypto();}
 Command prepared()throws Exception{
  var archive=fixture.staged();assertEquals(BackupArchiveDispatch.Status.ARCHIVE_VERIFICATION_RECORDED,fixture.consumer().once(archive).status());
  var scope=archive.scope();var current=fixture.journal().current(fixture.access,scope).orElseThrow();var run=fixture.runs().current(scope.tenantId(),scope.assetId(),scope.runId()).orElseThrow();
  return new Command(new Access(fixture.access.tenantId(),fixture.access.actorId(),fixture.access.permissionVersion()),scope,UUID.randomUUID(),run.state().version(),current.event().version(),run.lease().claim(),"sha256:"+current.source().payload().sha256(),EXECUTION,"synthetic-version-dispatch");
 }
 void authorize(Access access,cn.mop.domain.backup.BackupArchivePayload.Scope scope){fixture.authorize(new cn.mop.persistence.PostgresBackupArchiveRepository.Access(access.tenantId(),access.actorId(),access.permissionVersion()),scope);}
 PostgresBackupVersionRepository registry(){return new PostgresBackupVersionRepository(BackupArchiveDispatchPostgresTest.runtime,(c,a,s,o)->authorize(a,s),(a,c,ref)->{
  authorize(a,c.scope());if(!ref.equals("sha256:"+c.encryptedSha256()))throw new DomainException(403,"SYNTHETIC_OBJECT_REF_DENIED","synthetic");objectChecks.incrementAndGet();fixture.archive.verifyExisting(fixture.cryptoAccess(),fixture.cryptoScope(c.scope()),c.encryptedBytes(),c.encryptedSha256());
 },(c,a,s,prior,lease,ref)->{
  authorize(a,s);completionChecks.incrementAndGet();if(!completionAllowed.get()||!EXECUTION.equals(ref)||prior.state()!=BackupRunState.State.RUNNING||prior.attempts().size()!=s.attemptNumber()||!lease.owner().equals("synthetic-dispatch"))throw new DomainException(403,"SYNTHETIC_COLLECTION_NOT_VERIFIED","synthetic fixture only, not production proof");
 });}
 BackupVersionRegistrationDispatch consumer(PostgresBackupVersionRepository registry){return new BackupVersionRegistrationDispatch(new Binding(true,"backup-version-dispatch:synthetic-pg"),(b,a,s,step)->authorize(a,s),registry);}
 long registered(Command c)throws Exception{try(var db=BackupArchiveDispatchPostgresTest.runtime.getConnection();var q=db.prepareStatement("SELECT count(*) FROM mop_backup_version_registration WHERE tenant_id=? AND version_id=?")){q.setObject(1,c.scope().tenantId());q.setObject(2,c.scope().versionId());try(var r=q.executeQuery()){r.next();return r.getLong(1);}}}
 void completed(Command c)throws Exception{assertEquals(1,registered(c));var run=fixture.runs().current(c.scope().tenantId(),c.scope().assetId(),c.scope().runId()).orElseThrow();assertEquals(BackupRunState.State.SUCCEEDED,run.state().state());assertEquals(c.expectedRunVersion()+1,run.state().version());assertEquals(c.commandId(),run.commandId());assertEquals(c.scope().versionId(),run.state().attempts().get(c.scope().attemptNumber()-1).storedVersion().versionId());}
 @Test void actualRegistrationCompletesOnlyOriginalRunAndSubsequentInvocationIsReadOnly()throws Exception{
  var c=prepared();var registry=registry();var result=consumer(registry).once(c);assertTrue(result.registrationAndRunCompletionRecorded());assertFalse(result.currentObjectHealthVerified());completed(c);assertEquals(1,objectChecks.get());assertEquals(1,completionChecks.get());int reads=fixture.store.reads.get();assertTrue(consumer(registry).once(c).historicalReceipt());assertEquals(reads,fixture.store.reads.get());assertEquals(1,fixture.store.puts.get());completed(c);
 }
 @Test void committedRegistrationLostReplyRecoversByReadingOriginalWithoutSecondProofOrPut()throws Exception{
  var c=prepared();var registry=registry();var wrapped=new Registry(){public Optional<Receipt> current(Access a,cn.mop.domain.backup.BackupArchivePayload.Scope s){return registry.current(a,s);}public Receipt register(Command original){registry.register(original.access(),original.scope(),original.commandId(),original.expectedRunVersion(),original.expectedArchiveVersion(),original.lease(),original.objectRecheckReference(),original.executionReference(),original.trace());throw new IllegalStateException("synthetic committed reply lost");}};
  var first=new BackupVersionRegistrationDispatch(new Binding(true,"backup-version-dispatch:synthetic-pg"),(b,a,s,step)->authorize(a,s),wrapped).once(c);assertEquals(Status.RECONCILIATION_REQUIRED,first.status());completed(c);int reads=fixture.store.reads.get();var recovered=consumer(registry).once(c);assertTrue(recovered.historicalReceipt());assertFalse(recovered.originalRegistrationMethodInvoked());assertEquals(1,objectChecks.get());assertEquals(reads,fixture.store.reads.get());assertEquals(1,fixture.store.puts.get());completed(c);
 }
 @Test void deniedCollectionProofLeavesRegistrationAndRunUnchangedThenSameOriginalCanResume()throws Exception{
  var c=prepared();var registry=registry();completionAllowed.set(false);assertEquals(Status.RECONCILIATION_REQUIRED,consumer(registry).once(c).status());assertEquals(0,registered(c));assertEquals(BackupRunState.State.RUNNING,fixture.runs().current(c.scope().tenantId(),c.scope().assetId(),c.scope().runId()).orElseThrow().state().state());completionAllowed.set(true);assertTrue(consumer(registry).once(c).registrationAndRunCompletionRecorded());completed(c);assertEquals(2,objectChecks.get());assertEquals(1,fixture.store.puts.get());
 }
 @Test void revocationBeforeReturnSuppressesResultButDoesNotInventRollbackOrRepeatRegistration()throws Exception{
  var c=prepared();var registry=registry();var consumer=new BackupVersionRegistrationDispatch(new Binding(true,"backup-version-dispatch:synthetic-pg"),(b,a,s,step)->{if(step==Step.RETURN_RESULT)fixture.allowed.set(false);authorize(a,s);},registry);
  assertThrows(DomainException.class,()->consumer.once(c));assertThrows(DomainException.class,()->consumer(registry).once(c));fixture.allowed.set(true);completed(c);var recovered=consumer(registry).once(c);assertTrue(recovered.historicalReceipt());assertFalse(recovered.originalRegistrationMethodInvoked());assertEquals(1,objectChecks.get());assertEquals(1,fixture.store.puts.get());
 }
}
