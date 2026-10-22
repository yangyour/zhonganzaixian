package cn.mop.worker.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.PostgresBackupArchiveRepository;
import cn.mop.persistence.PostgresBackupArchiveRepository.*;
import cn.mop.adapters.backup.BackupEnvelopeArchive;
import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import java.util.*;

/** One explicitly supplied durable archive command. No polling, discovery, encryption, device execution,
 * version registration or default authorization. Replayed/uncertain starts never invoke PUT again. */
public final class BackupArchiveDispatch {
 public record Binding(boolean enabled,String reference){
  public Binding{if(enabled&&reference==null||reference!=null&&!reference.matches("backup-archive-dispatch:[A-Za-z0-9._-]{1,128}"))throw invalid();}
 }
 public enum Step{READ,START_STORE,STORE_ORIGINAL,RECONCILE_ORIGINAL,RETURN_RESULT}
 /** Independently enforce current approved binding, account, scope and revocation at each boundary. */
 @FunctionalInterface public interface Authority{void check(Binding binding,Access access,Scope scope,Step step);}
 public record Command(Access access,Scope scope,long expectedArchiveVersion,Dispatch dispatch,UUID startCommand,
                       UUID unconfirmedCommand,UUID verifyCommand,String evidenceReference,String trace){
  public Command{
   if(access==null||scope==null||!access.tenantId().equals(scope.tenantId())||expectedArchiveVersion<0||expectedArchiveVersion>=9007199254740991L||dispatch==null||startCommand==null||unconfirmedCommand==null||verifyCommand==null||new HashSet<>(List.of(startCommand,unconfirmedCommand,verifyCommand)).size()!=3||evidenceReference==null||!evidenceReference.matches("sha256:[0-9a-f]{64}")||trace==null||!trace.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
  }
 }
 public enum Status{DISABLED,ARCHIVE_VERIFICATION_RECORDED,RECONCILIATION_REQUIRED}
 public record Result(Status status,Long archiveEventVersion,boolean storeMethodInvokedThisCall){
  public boolean versionRegistered(){return false;}public boolean backupRunCompleted(){return false;}
  public boolean currentObjectHealthVerified(){return false;}public boolean deviceExecutionAuthorized(){return false;}
  public boolean retentionVerified(){return false;}public boolean recoveryVerified(){return false;}
 }
 // Package-private test seam; production constructor always delegates to independently authorized repositories/adapters.
 interface Journal {
  Optional<Receipt> current(Access a,Scope s);
  Receipt start(Command c,long version);
  Receipt uncertain(Command c,long version);
  Receipt verify(Command c,long version);
 }
 @FunctionalInterface interface OriginalWriter{void store(Access access,BackupArchivePayload original);}
 private final Binding binding;private final Authority authority;private final Journal journal;private final OriginalWriter writer;
 public BackupArchiveDispatch(Binding binding,Authority authority,PostgresBackupArchiveRepository journal,
                              BackupEnvelopeEncryption crypto,BackupEnvelopeArchive archive){
  this(binding,authority,postgres(Objects.requireNonNull(journal)),originalWriter(Objects.requireNonNull(crypto),Objects.requireNonNull(archive)));
 }
 BackupArchiveDispatch(Binding binding,Authority authority,Journal journal,OriginalWriter writer){this.binding=Objects.requireNonNull(binding);this.authority=Objects.requireNonNull(authority);this.journal=Objects.requireNonNull(journal);this.writer=Objects.requireNonNull(writer);}
 public Result once(Command command){
  Objects.requireNonNull(command);
  if(!binding.enabled())return new Result(Status.DISABLED,null,false);
  check(command,Step.READ);Receipt current=journal.current(command.access(),command.scope()).orElseThrow(BackupArchiveDispatch::missing);validate(current,command);if(current.replay())throw unavailable();
  if(current.event().phase()==Phase.VERIFIED)return result(command,Status.ARCHIVE_VERIFICATION_RECORDED,current.event().version(),false);
  if(current.event().phase()!=Phase.STAGED){
   if(!command.dispatch().equals(current.event().dispatch()))throw conflict();
   return reconcile(command,current,false);
  }
  if(current.event().version()!=command.expectedArchiveVersion())throw conflict();
  check(command,Step.START_STORE);
  Receipt started;
  try{started=journal.start(command,current.event().version());}
  catch(RuntimeException uncertain){
   // A start transaction may have committed: no PUT, no guessed rollback, caller must reread the same command.
   return result(command,Status.RECONCILIATION_REQUIRED,null,false);
  }
  validate(started,command);
  if(started.replay()){
   // The historical start receipt is not permission to repeat the original external side effect.
   return result(command,Status.RECONCILIATION_REQUIRED,null,false);
  }
  if(started.event().phase()!=Phase.STORE_STARTED||started.event().action()!=Action.START_STORE||started.event().version()!=current.event().version()+1||!command.startCommand().equals(started.event().commandId())||!command.dispatch().equals(started.event().dispatch())||!sameOriginal(current,started)||started.event().dispatchCount()!=current.event().dispatchCount()+1)throw conflict();
  boolean invoked=false;
  try{
   check(command,Step.STORE_ORIGINAL);invoked=true;writer.store(command.access(),started.source().payload());
  }catch(RuntimeException uncertain){return markUnconfirmed(command,started,invoked);}
  return reconcile(command,started,true);
 }
 private Result reconcile(Command command,Receipt current,boolean invoked){
  try{
   check(command,Step.RECONCILE_ORIGINAL);
   // Repository executes actual original-object proof outside SQL and rechecks authority/CAS before committing.
   Receipt verified=journal.verify(command,current.event().version());validate(verified,command);
   if(verified.event().phase()!=Phase.VERIFIED||verified.event().action()!=Action.VERIFY_OBJECT||!command.verifyCommand().equals(verified.event().commandId())||!command.evidenceReference().equals(verified.event().evidenceReference())||!sameOriginal(current,verified)||verified.event().version()!=current.event().version()+1||verified.event().dispatchCount()!=current.event().dispatchCount()||!Objects.equals(current.event().dispatch(),verified.event().dispatch()))throw conflict();
   return result(command,Status.ARCHIVE_VERIFICATION_RECORDED,verified.event().version(),invoked);
  }catch(RuntimeException uncertain){
   // Failed read/verification never proves that an earlier PUT did not happen. No CONFIRM_NO_DISPATCH here.
   return markUnconfirmed(command,current,invoked);
  }
 }
 private Result markUnconfirmed(Command command,Receipt prior,boolean invoked){
  if(prior.event().phase()==Phase.STORE_STARTED){
   try{check(command,Step.RECONCILE_ORIGINAL);journal.uncertain(command,prior.event().version());}
   catch(RuntimeException ignored){/* May have committed or a competing verifier progressed; never replace original command. */}
  }
  return result(command,Status.RECONCILIATION_REQUIRED,null,invoked);
 }
 private Result result(Command c,Status status,Long version,boolean invoked){check(c,Step.RETURN_RESULT);return new Result(status,version,invoked);}
 private void check(Command c,Step step){authority.check(binding,c.access(),c.scope(),step);}
 private static void validate(Receipt r,Command c){
  if(r==null||r.source()==null||r.source().payload()==null||r.event()==null||!c.scope().equals(r.source().payload().scope())||r.event().version()<0||r.event().version()>9007199254740991L||r.event().phase()==null||r.event().action()==null||r.event().commandId()==null||r.event().actorId()==null||r.event().observedAt()==null||r.source().submissionId()==null||r.source().submissionHash()==null||!r.source().submissionHash().matches("[0-9a-f]{64}")||r.source().createdBy()==null||r.source().recordedAt()==null||r.source().runVersion()<1||r.source().runVersion()>9007199254740991L||r.event().dispatchCount()<0||r.event().dispatchCount()>9007199254740991L)throw unavailable();
  var e=r.event();
  if(e.phase()==Phase.VERIFIED&&(e.action()!=Action.VERIFY_OBJECT||e.evidenceReference()==null||!e.evidenceReference().matches("sha256:[0-9a-f]{64}")))throw unavailable();
  if(e.phase()==Phase.STORE_STARTED&&(e.action()!=Action.START_STORE||e.dispatch()==null||e.dispatchCount()<1))throw unavailable();
  if(e.phase()==Phase.VERIFY_REQUIRED&&(e.action()!=Action.MARK_UNCONFIRMED||e.dispatch()==null||e.dispatchCount()<1))throw unavailable();
  if(e.phase()==Phase.STAGED&&!Set.of(Action.STAGE,Action.CONFIRM_NO_DISPATCH).contains(e.action()))throw unavailable();
 }
 private static boolean sameOriginal(Receipt a,Receipt b){
  var x=a.source();var y=b.source();return x.submissionId().equals(y.submissionId())&&x.submissionHash().equals(y.submissionHash())&&x.runVersion()==y.runVersion()&&x.createdBy().equals(y.createdBy())&&x.recordedAt().equals(y.recordedAt())&&x.payload().scope().equals(y.payload().scope())&&x.payload().size()==y.payload().size()&&x.payload().sha256().equals(y.payload().sha256());
 }
 private static Journal postgres(PostgresBackupArchiveRepository repository){return new Journal(){
  public Optional<Receipt> current(Access a,Scope s){return repository.current(a,s);}
  public Receipt start(Command c,long v){return repository.startStore(c.access(),c.scope(),c.startCommand(),v,c.dispatch(),c.trace());}
  public Receipt uncertain(Command c,long v){return repository.markUnconfirmed(c.access(),c.scope(),c.unconfirmedCommand(),v,c.dispatch(),c.trace());}
  public Receipt verify(Command c,long v){return repository.verifyObject(c.access(),c.scope(),c.verifyCommand(),v,c.evidenceReference(),c.trace());}
 };}
 static OriginalWriter originalWriter(BackupEnvelopeEncryption crypto,BackupEnvelopeArchive archive){return (access,payload)->{
  var s=payload.scope();var scope=new BackupEnvelopeEncryption.Scope(s.tenantId(),s.assetId(),s.runId(),s.attemptNumber(),s.versionId());
  var original=crypto.decode(payload.bytes(),payload.sha256(),scope);
  archive.store(new BackupEnvelopeEncryption.Access(access.tenantId(),access.actorId(),access.permissionVersion()),scope,original);
 };}
 private static DomainException invalid(){return new DomainException(400,"INVALID_BACKUP_ARCHIVE_DISPATCH","归档消费者配置或原命令身份无效");}
 private static DomainException missing(){return new DomainException(404,"BACKUP_ARCHIVE_NOT_FOUND","当前范围无已暂存原信封");}
 private static DomainException conflict(){return new DomainException(409,"BACKUP_ARCHIVE_DISPATCH_CONFLICT","原归档任务、派发身份或版本不匹配；不会重新上传");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_ARCHIVE_DISPATCH_UNAVAILABLE","归档任务回读无效；不能据此重传或确认成功");}
}
