package cn.mop.worker.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.PostgresBackupRunRepository.LeaseClaim;
import cn.mop.persistence.PostgresBackupVersionRepository;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import java.time.Instant;
import java.util.*;

/** One explicitly retained registration command. No polling, new command generation, PUT,
 * device execution, lease release, retention or automatic Spring/runtime wiring. */
public final class BackupVersionRegistrationDispatch {
 private static final long MAX=9007199254740991L;
 public record Binding(boolean enabled,String reference){public Binding{if(enabled&&reference==null||reference!=null&&!reference.matches("backup-version-dispatch:[A-Za-z0-9._-]{1,128}"))throw invalid();}}
 public enum Step{READ_ORIGINAL,REGISTER_ORIGINAL,RETURN_RESULT}
 /** Check the approved binding, current identity/scope and revocation at each boundary. */
 @FunctionalInterface public interface Authority{void check(Binding binding,Access access,Scope scope,Step step);}
 public record Command(Access access,Scope scope,UUID commandId,long expectedRunVersion,long expectedArchiveVersion,
                       LeaseClaim lease,String objectRecheckReference,String executionReference,String trace){
  public Command{if(access==null||scope==null||!access.tenantId().equals(scope.tenantId())||commandId==null||expectedRunVersion<1||expectedRunVersion>=MAX||expectedArchiveVersion<1||expectedArchiveVersion>MAX||lease==null||!reference(objectRecheckReference)||!reference(executionReference)||trace==null||!trace.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();}
 }
 public enum Status{DISABLED,REGISTRATION_AND_RUN_COMPLETION_RECORDED,RECONCILIATION_REQUIRED}
 public record Result(Status status,Long completedRunVersion,boolean originalRegistrationMethodInvoked,boolean historicalReceipt){
  public boolean registrationAndRunCompletionRecorded(){return status==Status.REGISTRATION_AND_RUN_COMPLETION_RECORDED;}
  public boolean currentObjectHealthVerified(){return false;}public boolean deviceExecutionAuthorized(){return false;}
  public boolean retentionVerified(){return false;}public boolean recoveryVerified(){return false;}
 }
 // Package-private seam; public assembly always uses the independently authorized PostgreSQL repository.
 interface Registry{Optional<Receipt> current(Access access,Scope scope);Receipt register(Command command);}
 private final Binding binding;private final Authority authority;private final Registry registry;
 public BackupVersionRegistrationDispatch(Binding binding,Authority authority,PostgresBackupVersionRepository repository){this(binding,authority,postgres(Objects.requireNonNull(repository)));}
 BackupVersionRegistrationDispatch(Binding binding,Authority authority,Registry registry){this.binding=Objects.requireNonNull(binding);this.authority=Objects.requireNonNull(authority);this.registry=Objects.requireNonNull(registry);}
 public Result once(Command command){
  Objects.requireNonNull(command);
  if(!binding.enabled())return new Result(Status.DISABLED,null,false,false);
  check(command,Step.READ_ORIGINAL);
  Optional<Receipt> existing;
  try{existing=registry.current(command.access(),command.scope());if(existing==null)throw unavailable();}
  catch(RuntimeException failure){throw sanitized(failure);}
  if(existing.isPresent()){
   var receipt=existing.get();if(receipt.replay())throw unavailable();validate(receipt,command);
   return result(command,Status.REGISTRATION_AND_RUN_COMPLETION_RECORDED,receipt.registration().completedRunVersion(),false,true);
  }
  check(command,Step.REGISTER_ORIGINAL);
  Receipt accepted;
  try{accepted=registry.register(command);validate(accepted,command);}
  catch(RuntimeException uncertain){
   // SQL may have committed even when its reply was lost or malformed. No new command or inferred rollback.
   return result(command,Status.RECONCILIATION_REQUIRED,null,true,false);
  }
  return result(command,Status.REGISTRATION_AND_RUN_COMPLETION_RECORDED,accepted.registration().completedRunVersion(),true,accepted.replay());
 }
 private Result result(Command command,Status status,Long version,boolean invoked,boolean historical){check(command,Step.RETURN_RESULT);return new Result(status,version,invoked,historical);}
 private void check(Command command,Step step){authority.check(binding,command.access(),command.scope(),step);}
 private static void validate(Receipt receipt,Command command){
  if(receipt==null||receipt.registration()==null||receipt.registration().candidate()==null)throw unavailable();
  var row=receipt.registration();var candidate=row.candidate();
  if(candidate.scope()==null||candidate.lease()==null||candidate.encryptedSha256()==null||!candidate.encryptedSha256().matches("[0-9a-f]{64}")||candidate.encryptedBytes()<247||candidate.encryptedBytes()>BackupArchivePayload.MAX_ENVELOPE_BYTES||candidate.keyReference()==null||!candidate.keyReference().matches("kms-key-ref:[A-Za-z0-9._-]{1,128}")||!reference(candidate.archiveVerificationReference())||!instant(candidate.objectObservedAt())||!instant(row.registeredAt())||row.registeredAt().isBefore(candidate.objectObservedAt())||row.commandHash()==null||!row.commandHash().matches("[0-9a-f]{64}")||row.registeredBy()==null)throw unavailable();
  if(!candidate.scope().equals(command.scope())||candidate.expectedRunVersion()!=command.expectedRunVersion()||candidate.archiveEventVersion()!=command.expectedArchiveVersion()||!candidate.lease().equals(command.lease())||!command.commandId().equals(row.commandId())||!command.objectRecheckReference().equals(row.objectRecheckReference())||!command.executionReference().equals(row.executionReference())||!command.access().actorId().equals(row.registeredBy())||row.completedRunVersion()!=command.expectedRunVersion()+1)throw conflict();
 }
 private static boolean instant(Instant value){return value!=null&&value.getNano()%1_000_000==0&&!value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))&&!value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"));}
 private static boolean reference(String value){return value!=null&&value.matches("sha256:[0-9a-f]{64}");}
 private static Registry postgres(PostgresBackupVersionRepository repository){return new Registry(){
  public Optional<Receipt> current(Access access,Scope scope){return repository.current(access,scope);}
  public Receipt register(Command c){return repository.register(c.access(),c.scope(),c.commandId(),c.expectedRunVersion(),c.expectedArchiveVersion(),c.lease(),c.objectRecheckReference(),c.executionReference(),c.trace());}
 };}
 private static DomainException sanitized(RuntimeException failure){if(failure instanceof DomainException d&&(d.status==403||d.status==404))return new DomainException(d.status,"BACKUP_VERSION_DISPATCH_READ_DENIED","原版本登记未获当前读取授权");return unavailable();}
 private static DomainException invalid(){return new DomainException(400,"INVALID_BACKUP_VERSION_DISPATCH","版本登记消费者绑定或原命令身份无效");}
 private static DomainException conflict(){return new DomainException(409,"BACKUP_VERSION_DISPATCH_CONFLICT","已登记记录与原命令不符；不会重建版本或完成其他运行");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_VERSION_DISPATCH_UNAVAILABLE","原版本登记未能核对；不能据此新建命令或确认完成");}
}
