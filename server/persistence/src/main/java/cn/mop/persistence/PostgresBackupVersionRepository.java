package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.domain.backup.BackupRunState;
import cn.mop.domain.backup.BackupRunState.*;
import cn.mop.persistence.PostgresBackupRunRepository.LeaseClaim;
import javax.sql.DataSource;
import java.io.*;import java.security.*;import java.sql.*;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicReference;

/** Registration workflow only: original-object recheck outside SQL, registration and run success in one transaction.
 * No PUT, device command, lease release, retention policy, default authorization or auto-wiring. */
public final class PostgresBackupVersionRepository {
 private static final long MAX=9007199254740991L;
 public record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{if(tenantId==null||actorId==null||permissionVersion<1||permissionVersion>MAX)throw invalid();}}
 public enum Operation{READ,REGISTER}
 @FunctionalInterface public interface Authorizer{void authorize(Connection c,Access access,Scope scope,Operation operation)throws SQLException;}
 /** Actually read/authenticate the original scoped object; reference text alone is not proof. No SQL transaction is held. */
 @FunctionalInterface public interface ObjectVerifier{void verify(Access access,Candidate original,String recheckReference)throws Exception;}
 /** Verify trusted completed collection/prerequisite records and hold relevant revocation locks. No external IO or device action. */
 @FunctionalInterface public interface CompletionPrerequisiteVerifier{void verify(Connection c,Access access,Scope scope,BackupRunState prior,LeaseClaim lease,String executionReference)throws SQLException;}
 public record Candidate(Scope scope,long expectedRunVersion,long archiveEventVersion,String encryptedSha256,int encryptedBytes,String keyReference,String archiveVerificationReference,Instant objectObservedAt,LeaseClaim lease){}
 public record Registration(Candidate candidate,UUID commandId,String commandHash,String objectRecheckReference,String executionReference,UUID registeredBy,Instant registeredAt,long completedRunVersion){
  public boolean registrationAndRunCompletionRecorded(){return true;}
  public boolean currentObjectHealthVerified(){return false;}public boolean deviceExecutionAuthorized(){return false;}
  public boolean retentionVerified(){return false;}public boolean recoveryVerified(){return false;}
 }
 public record Receipt(Registration registration,boolean replay){}
 public static final int MAX_INVENTORY_PAGE_SIZE=50;
 public record InventoryScope(UUID tenantId,UUID assetId){public InventoryScope{if(tenantId==null||assetId==null)throw invalid();}}
 /** Separate asset-wide enumeration authority, not draft editing or permission to download. Hold revocation locks; no external IO. */
 @FunctionalInterface public interface InventoryAuthorizer{void authorize(Connection connection,Access access,InventoryScope scope)throws SQLException;}
 public record InventoryCursor(InventoryScope scope,Instant registeredAt,UUID versionId){public InventoryCursor{if(scope==null||versionId==null||registeredAt==null||registeredAt.getNano()%1_000_000!=0||registeredAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||registeredAt.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}}
 /** Historical database registration only. Deliberately excludes KMS, storage, lease, proof and trace references. */
 public record InventoryItem(UUID versionId,UUID assetId,UUID runId,int attemptNumber,long completedRunVersion,Instant objectObservedAt,Instant registeredAt,String encryptedSha256,int encryptedBytes){
  public boolean registrationAndRunCompletionRecorded(){return true;}public boolean currentObjectHealthVerified(){return false;}
  public boolean downloadAuthorized(){return false;}public boolean deviceExecutionAuthorized(){return false;}
  public boolean retentionVerified(){return false;}public boolean recoveryVerified(){return false;}
 }
 public record InventoryPage(List<InventoryItem> items,boolean hasMore,InventoryCursor nextBefore){public InventoryPage{items=List.copyOf(items);}}

 private record Command(Access access,Scope scope,UUID id,long runVersion,long archiveVersion,LeaseClaim lease,String objectReference,String executionReference,String trace){}
 private record Prepared(Candidate candidate,Registration existing){}
 private record RunView(BackupRunState state,UUID command,String action,String reference,LeaseClaim lease,long leaseEventVersion,Instant observedAt){}
 private final DataSource source;private final Authorizer authorizer;private final ObjectVerifier objectVerifier;private final CompletionPrerequisiteVerifier completionVerifier;
 private final BackupRunSnapshotCodec codec=new BackupRunSnapshotCodec();
 public PostgresBackupVersionRepository(DataSource source,Authorizer authorizer,ObjectVerifier objectVerifier,CompletionPrerequisiteVerifier completionVerifier){this.source=Objects.requireNonNull(source);this.authorizer=Objects.requireNonNull(authorizer);this.objectVerifier=Objects.requireNonNull(objectVerifier);this.completionVerifier=Objects.requireNonNull(completionVerifier);}
 public Receipt register(Access access,Scope scope,UUID command,long expectedRunVersion,long expectedArchiveVersion,LeaseClaim lease,String objectRecheckReference,String executionReference,String trace){
  var input=new Command(access,scope,command,expectedRunVersion,expectedArchiveVersion,lease,objectRecheckReference,executionReference,trace);validate(input);
  Prepared prepared=transaction(access,scope,Operation.REGISTER,c->prepare(c,input));if(prepared.existing()!=null)return new Receipt(prepared.existing(),true);
  Candidate original=prepared.candidate();
  try{objectVerifier.verify(access,original,objectRecheckReference);}catch(Exception rejected){throw proofFailure(rejected);}
  var registered=new AtomicReference<Registration>();
  // This instance is deliberately local to one registration command. No thread-local/global evidence cache.
  var runs=new PostgresBackupRunRepository(source,(c,t,a,r)->{
   if(!scope.tenantId().equals(t)||!scope.assetId().equals(a)||!scope.runId().equals(r))throw conflict();
   authorize(c,access,scope,Operation.REGISTER);lockAsset(c,scope);Prepared latest=prepare(c,input);
   if(!original.equals(latest.candidate()))throw conflict();if(latest.existing()!=null)registered.set(latest.existing());
  },(c,r,state,claim)->{throw conflict();},(c,r,prior,outcome,failure,stored,reference)->{
   if(!r.equals(scope.runId())||outcome!=Outcome.SUCCEEDED||failure!=null||prior.version()!=expectedRunVersion||prior.attempts().size()!=scope.attemptNumber()||!reference.equals(executionReference)||!stored.equals(stored(original)))throw conflict();
   try{completionVerifier.verify(c,access,scope,prior,lease,executionReference);}catch(DomainException rejected){throw proofFailure(rejected);}catch(RuntimeException rejected){throw proofFailure(rejected);}
   Registration row=insert(c,input,original);registered.set(row);
  });
  var completed=runs.succeed(scope.tenantId(),scope.assetId(),scope.runId(),command,expectedRunVersion,lease,stored(original),executionReference,trace);
  Registration row=registered.get();if(row==null||completed.state().state()!=State.SUCCEEDED||completed.state().version()!=row.completedRunVersion()||!completed.commandId().equals(command)||!completed.recordedAt().equals(row.registeredAt())||!completed.state().attempts().get(scope.attemptNumber()-1).storedVersion().equals(stored(original)))throw unavailable();
  return new Receipt(row,completed.replay());
 }
 public Optional<Receipt> current(Access access,Scope scope){matching(access,scope);return transaction(access,scope,Operation.READ,c->{Registration row=find(c,scope);if(row==null)return Optional.empty();verifyRegistration(c,row);return Optional.of(new Receipt(row,false));});}
 private Prepared prepare(Connection c,Command command)throws SQLException{
  Candidate candidate=candidate(c,command.scope(),command.runVersion(),command.archiveVersion(),command.lease());Registration existing=find(c,command.scope());
  if(existing!=null){if(!existing.commandId().equals(command.id())||!existing.commandHash().equals(fingerprint(command,candidate))||!existing.candidate().equals(candidate))throw conflict();verifyRegistration(c,existing);return new Prepared(candidate,existing);}
  try(var q=query(c,"SELECT 1 FROM public.mop_backup_version_registration WHERE tenant_id=? AND command_id=?")){q.setObject(1,command.scope().tenantId());q.setObject(2,command.id());try(var r=q.executeQuery()){if(r.next())throw conflict();}}
  RunView prior=run(c,command.scope(),null);requireRunning(prior,candidate);return new Prepared(candidate,null);
 }
 private Candidate candidate(Connection c,Scope scope,long runVersion,long archiveVersion,LeaseClaim lease)throws SQLException{
  BackupArchivePayload payload;Instant stagedAt;
  try(var q=query(c,"SELECT * FROM public.mop_backup_archive_submission WHERE tenant_id=? AND version_id=?")){q.setObject(1,scope.tenantId());q.setObject(2,scope.versionId());try(var r=q.executeQuery()){
   if(!r.next())throw new DomainException(404,"BACKUP_ARCHIVE_NOT_FOUND","当前范围未找到原信封");
   try{payload=BackupArchivePayload.parse(r.getBytes("encoded_envelope"),r.getString("envelope_sha256"));}catch(DomainException rejected){throw unavailable();}
   if(!payload.scope().equals(scope)||!scope.assetId().equals(r.getObject("asset_id",UUID.class))||!scope.runId().equals(r.getObject("run_id",UUID.class))||scope.attemptNumber()!=r.getInt("attempt_number"))throw new DomainException(404,"BACKUP_ARCHIVE_NOT_FOUND","当前范围未找到原信封");
   if(payload.size()!=r.getInt("envelope_bytes")||!payload.keyReference().equals(r.getString("key_reference"))||!BackupArchivePayload.FORMAT.equals(r.getString("envelope_format"))||!BackupArchivePayload.ALGORITHM.equals(r.getString("encryption_algorithm"))||!"INTERNAL_BACKUP_ARCHIVE_STAGING".equals(r.getString("record_source"))||r.getBoolean("storage_verified")||r.getBoolean("version_registered")||r.getBoolean("device_execution_authorized"))throw unavailable();stagedAt=instant(r,"recorded_at");
  }}
  try(var q=query(c,"SELECT * FROM public.mop_backup_archive_event WHERE tenant_id=? AND version_id=? ORDER BY event_version DESC LIMIT 1")){q.setObject(1,scope.tenantId());q.setObject(2,scope.versionId());try(var r=q.executeQuery()){
   if(!r.next()||r.getLong("event_version")!=archiveVersion||!"VERIFIED".equals(r.getString("phase"))||!"VERIFY_OBJECT".equals(r.getString("action")))throw conflict();
   String ref=r.getString("evidence_reference");Instant at=instant(r,"observed_at");if(!reference(ref)||at.isBefore(stagedAt)||!"INTERNAL_BACKUP_ARCHIVE_JOURNAL".equals(r.getString("record_source"))||r.getBoolean("version_registered")||r.getBoolean("device_execution_authorized"))throw unavailable();
   return new Candidate(scope,runVersion,archiveVersion,payload.sha256(),payload.size(),payload.keyReference(),ref,at,lease);
  }}
 }
 private RunView run(Connection c,Scope scope,Long version)throws SQLException{
  try(var q=query(c,"SELECT * FROM public.mop_backup_run_event WHERE tenant_id=? AND asset_id=? AND run_id=?"+(version==null?"":" AND version=?")+" ORDER BY version DESC LIMIT 1")){q.setObject(1,scope.tenantId());q.setObject(2,scope.assetId());q.setObject(3,scope.runId());if(version!=null)q.setLong(4,version);try(var r=q.executeQuery()){
   if(!r.next())throw conflict();
   try{
    BackupRunState state=codec.decode(r.getBytes("snapshot"),r.getString("snapshot_sha256"),scope.runId(),scope.tenantId(),scope.assetId());Instant at=instant(r,"observed_at");
    if(!"INTERNAL_BACKUP_RUN_JOURNAL".equals(r.getString("record_source"))||r.getBoolean("device_execution_authorized")||!BackupRunSnapshotCodec.FORMAT.equals(r.getString("snapshot_format"))||state.version()!=r.getLong("version")||!state.queuedAt().equals(instant(r,"queued_at"))||state.attempts().size()!=r.getInt("attempt_number")||!state.state().name().equals(r.getString("state"))||!at.equals(state.attempts().isEmpty()?state.queuedAt():state.attempts().get(state.attempts().size()-1).observedAt()))throw unavailable();
    UUID command=r.getObject("command_id",UUID.class);String action=r.getString("action"),ref=r.getString("verification_reference"),hash=r.getString("command_sha256");if(command==null||hash==null||!hash.matches("[0-9a-f]{64}"))throw unavailable();
    if(state.attempts().isEmpty()){if(!"QUEUE".equals(action)||ref!=null||r.getObject("lease_request_id")!=null||r.getObject("lease_event_version")!=null||r.getObject("lease_owner")!=null||r.getObject("fencing_token")!=null)throw unavailable();return new RunView(state,command,action,null,null,0,at);}
    Outcome expected=switch(action){case "START"->Outcome.RUNNING;case "MARK_UNKNOWN"->Outcome.RESULT_UNKNOWN;case "FAIL"->Outcome.FAILED;case "SUCCEED"->Outcome.SUCCEEDED;default->throw unavailable();};if(state.attempts().get(state.attempts().size()-1).outcome()!=expected||(expected==Outcome.FAILED||expected==Outcome.SUCCEEDED?!reference(ref):ref!=null))throw unavailable();
    var claim=new LeaseClaim(r.getObject("lease_request_id",UUID.class),r.getString("lease_owner"),r.getLong("fencing_token"));long eventVersion=r.getLong("lease_event_version");if(eventVersion<1||eventVersion>MAX)throw unavailable();return new RunView(state,command,action,ref,claim,eventVersion,at);
   }catch(IllegalArgumentException|NullPointerException|DomainException rejected){throw unavailable();}
  }}
 }
 private static void requireRunning(RunView prior,Candidate c){if(prior.state().state()!=State.RUNNING||prior.state().version()!=c.expectedRunVersion()||prior.state().attempts().size()!=c.scope().attemptNumber()||!c.lease().equals(prior.lease())||c.objectObservedAt().isBefore(prior.state().attempts().get(c.scope().attemptNumber()-1).startedAt()))throw conflict();}
 private Registration insert(Connection c,Command input,Candidate original)throws SQLException{
  Scope s=input.scope();String hash=fingerprint(input,original);
  try(var q=query(c,"INSERT INTO public.mop_backup_version_registration(tenant_id,version_id,asset_id,run_id,attempt_number,command_id,command_sha256,expected_run_version,completed_run_version,archive_event_version,envelope_sha256,envelope_bytes,key_reference,archive_verification_reference,object_recheck_reference,execution_reference,lease_request_id,lease_owner,fencing_token,object_observed_at,registered_by,trace_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *")){
   q.setObject(1,s.tenantId());q.setObject(2,s.versionId());q.setObject(3,s.assetId());q.setObject(4,s.runId());q.setInt(5,s.attemptNumber());q.setObject(6,input.id());q.setString(7,hash);q.setLong(8,input.runVersion());q.setLong(9,input.runVersion()+1);q.setLong(10,input.archiveVersion());q.setString(11,original.encryptedSha256());q.setInt(12,original.encryptedBytes());q.setString(13,original.keyReference());q.setString(14,original.archiveVerificationReference());q.setString(15,input.objectReference());q.setString(16,input.executionReference());q.setObject(17,input.lease().requestId());q.setString(18,input.lease().owner());q.setLong(19,input.lease().fencingToken());q.setObject(20,original.objectObservedAt().atOffset(ZoneOffset.UTC));q.setObject(21,input.access().actorId());q.setString(22,input.trace());try(var r=q.executeQuery()){if(!r.next())throw unavailable();Registration row=row(r);if(!row.candidate().equals(original)||!row.commandHash().equals(hash))throw unavailable();return row;}
  }
 }
 /** Bounded keyset inventory under explicit asset-wide plus per-version authorization, in one owned transaction.
  * Asset lock serializes registration for this page only. Pages are not a frozen inventory or a storage health check. */
 public InventoryPage inventory(Access access,InventoryScope inventoryScope,InventoryCursor before,int limit,InventoryAuthorizer inventoryAuthorizer){
  if(access==null||inventoryScope==null||inventoryAuthorizer==null||limit<1||limit>MAX_INVENTORY_PAGE_SIZE)throw invalid();
  if(!access.tenantId().equals(inventoryScope.tenantId())||before!=null&&!before.scope().equals(inventoryScope))throw new DomainException(404,"BACKUP_VERSION_NOT_FOUND","当前范围未找到版本登记");
  try(Connection c=source.getConnection()){
   c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
   try{
    try(var statement=c.createStatement()){statement.execute("SET LOCAL lock_timeout='5s'");statement.execute("SET LOCAL statement_timeout='10s'");}
    authorizeInventory(c,access,inventoryScope,inventoryAuthorizer);lockAsset(c,inventoryScope.tenantId(),inventoryScope.assetId());
    String sql="SELECT * FROM public.mop_backup_version_registration WHERE tenant_id=? AND asset_id=?"+(before==null?"":" AND (registered_at,version_id)<(?,?)")+" ORDER BY registered_at DESC,version_id DESC LIMIT ?";
    var rows=new ArrayList<Registration>();
    try(var q=query(c,sql)){q.setObject(1,inventoryScope.tenantId());q.setObject(2,inventoryScope.assetId());int index=3;if(before!=null){q.setObject(index++,before.registeredAt().atOffset(ZoneOffset.UTC));q.setObject(index++,before.versionId());}q.setInt(index,limit+1);try(var r=q.executeQuery()){while(r.next())rows.add(row(r));}}
    var items=new ArrayList<InventoryItem>();
    for(Registration registration:rows){Candidate original=registration.candidate();Scope scope=original.scope();if(!scope.tenantId().equals(inventoryScope.tenantId())||!scope.assetId().equals(inventoryScope.assetId()))throw inventoryUnavailable();authorize(c,access,scope,Operation.READ);verifyRegistration(c,registration);
     if(items.size()<limit)items.add(new InventoryItem(scope.versionId(),scope.assetId(),scope.runId(),scope.attemptNumber(),registration.completedRunVersion(),original.objectObservedAt(),registration.registeredAt(),original.encryptedSha256(),original.encryptedBytes()));
    }
    // Even a zero-row result and the look-ahead row require enumeration authority; no partial result on a veto.
    authorizeInventory(c,access,inventoryScope,inventoryAuthorizer);boolean more=rows.size()>limit;InventoryItem last=items.isEmpty()?null:items.get(items.size()-1);InventoryCursor next=more?new InventoryCursor(inventoryScope,last.registeredAt(),last.versionId()):null;
    c.commit();return new InventoryPage(items,more,next);
   }catch(SQLException|RuntimeException rejected){try{c.rollback();}catch(SQLException ignored){}throw rejected;}
  }catch(SQLException rejected){throw inventoryUnavailable();}catch(DomainException rejected){if(rejected.status==403||rejected.status==404)throw rejected;throw inventoryUnavailable();}catch(RuntimeException rejected){throw inventoryUnavailable();}
 }
 private static void authorizeInventory(Connection c,Access access,InventoryScope scope,InventoryAuthorizer authorizer)throws SQLException{
  try{authorizer.authorize(c,access,scope);}catch(DomainException rejected){if(rejected.status==403||rejected.status==404)throw new DomainException(rejected.status,"BACKUP_VERSION_INVENTORY_ACCESS_DENIED","当前设备的版本列表未获独立授权");throw inventoryUnavailable();}catch(RuntimeException rejected){throw inventoryUnavailable();}
 }
 private static DomainException inventoryUnavailable(){return new DomainException(503,"BACKUP_VERSION_INVENTORY_UNAVAILABLE","版本登记列表或完成关联未能核对；没有授予下载或执行权限");}
 private Registration find(Connection c,Scope s)throws SQLException{try(var q=query(c,"SELECT * FROM public.mop_backup_version_registration WHERE tenant_id=? AND version_id=? AND asset_id=? AND run_id=? AND attempt_number=?")){q.setObject(1,s.tenantId());q.setObject(2,s.versionId());q.setObject(3,s.assetId());q.setObject(4,s.runId());q.setInt(5,s.attemptNumber());try(var r=q.executeQuery()){return r.next()?row(r):null;}}}
 private Registration row(ResultSet r)throws SQLException{
  try{
   var scope=new Scope(r.getObject("tenant_id",UUID.class),r.getObject("asset_id",UUID.class),r.getObject("run_id",UUID.class),r.getInt("attempt_number"),r.getObject("version_id",UUID.class));var lease=new LeaseClaim(r.getObject("lease_request_id",UUID.class),r.getString("lease_owner"),r.getLong("fencing_token"));long runVersion=r.getLong("expected_run_version"),archiveVersion=r.getLong("archive_event_version");String sha=r.getString("envelope_sha256"),key=r.getString("key_reference"),archiveRef=r.getString("archive_verification_reference"),objectRef=r.getString("object_recheck_reference"),executionRef=r.getString("execution_reference"),hash=r.getString("command_sha256");int bytes=r.getInt("envelope_bytes");UUID command=r.getObject("command_id",UUID.class),actor=r.getObject("registered_by",UUID.class);Instant observed=instant(r,"object_observed_at"),registeredAt=instant(r,"registered_at");
   if(runVersion<1||runVersion>=MAX||archiveVersion<1||archiveVersion>MAX||sha==null||!sha.matches("[0-9a-f]{64}")||bytes<247||bytes>BackupArchivePayload.MAX_ENVELOPE_BYTES||key==null||!key.matches("kms-key-ref:[A-Za-z0-9._-]{1,128}")||!reference(archiveRef)||!reference(objectRef)||!reference(executionRef)||command==null||actor==null||hash==null||!hash.matches("[0-9a-f]{64}")||registeredAt.isBefore(observed)||r.getLong("completed_run_version")!=runVersion+1||!"INTERNAL_BACKUP_VERSION_REGISTRATION".equals(r.getString("record_source"))||r.getBoolean("device_execution_authorized")||r.getBoolean("retention_verified")||r.getBoolean("recovery_verified")||r.getString("trace_id")==null||!r.getString("trace_id").matches("[A-Za-z0-9._:-]{1,128}"))throw unavailable();
   Candidate candidate=new Candidate(scope,runVersion,archiveVersion,sha,bytes,key,archiveRef,observed,lease);Command input=new Command(new Access(scope.tenantId(),actor,1),scope,command,runVersion,archiveVersion,lease,objectRef,executionRef,r.getString("trace_id"));if(!hash.equals(fingerprint(input,candidate)))throw unavailable();return new Registration(candidate,command,hash,objectRef,executionRef,actor,registeredAt,runVersion+1);
  }catch(IllegalArgumentException|NullPointerException|DomainException rejected){throw unavailable();}
 }
 private void verifyRegistration(Connection c,Registration row)throws SQLException{
  Candidate original=row.candidate();Candidate actual=candidate(c,original.scope(),original.expectedRunVersion(),original.archiveEventVersion(),original.lease());if(!original.equals(actual))throw unavailable();
  RunView previous=run(c,original.scope(),original.expectedRunVersion()),completed=run(c,original.scope(),null);
  try{requireRunning(previous,original);}catch(DomainException rejected){throw unavailable();}
  if(completed.state().state()!=State.SUCCEEDED||completed.state().version()!=row.completedRunVersion()||completed.state().attempts().size()!=original.scope().attemptNumber()||!"SUCCEED".equals(completed.action())||!row.commandId().equals(completed.command())||!row.executionReference().equals(completed.reference())||!row.registeredAt().equals(completed.observedAt())||!original.lease().equals(completed.lease())||previous.leaseEventVersion()!=completed.leaseEventVersion()||!stored(original).equals(completed.state().attempts().get(original.scope().attemptNumber()-1).storedVersion()))throw unavailable();
 }
 private static StoredVersion stored(Candidate c){return new StoredVersion(c.scope().tenantId(),c.scope().assetId(),c.scope().versionId(),c.encryptedSha256(),c.encryptedBytes(),c.objectObservedAt());}
 private interface Work<T>{T run(Connection c)throws SQLException;}
 private <T>T transaction(Access a,Scope s,Operation operation,Work<T> work){try(Connection c=source.getConnection()){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);try{try(var q=c.createStatement()){q.execute("SET LOCAL lock_timeout='5s'");q.execute("SET LOCAL statement_timeout='10s'");}authorize(c,a,s,operation);lockAsset(c,s);T result=work.run(c);c.commit();return result;}catch(SQLException|RuntimeException rejected){try{c.rollback();}catch(SQLException ignored){}throw rejected;}}catch(SQLException rejected){if("23505".equals(rejected.getSQLState())||"23514".equals(rejected.getSQLState()))throw conflict();if("23503".equals(rejected.getSQLState()))throw new DomainException(404,"BACKUP_VERSION_REFERENCE_NOT_FOUND","当前登记引用不可见");throw unavailable();}}
 private void authorize(Connection c,Access a,Scope s,Operation operation)throws SQLException{try{authorizer.authorize(c,a,s,operation);}catch(DomainException rejected){if(rejected.status==403||rejected.status==404)throw new DomainException(rejected.status,"BACKUP_VERSION_ACCESS_DENIED","当前范围的版本登记或读取未获授权");throw unavailable();}catch(RuntimeException rejected){throw unavailable();}}
 private static void lockAsset(Connection c,Scope s)throws SQLException{lockAsset(c,s.tenantId(),s.assetId());}
 private static void lockAsset(Connection c,UUID tenant,UUID asset)throws SQLException{try(var q=query(c,"SELECT id FROM public.mop_asset WHERE tenant_id=? AND id=? FOR UPDATE")){q.setObject(1,tenant);q.setObject(2,asset);try(var r=q.executeQuery()){if(!r.next())throw new DomainException(404,"ASSET_NOT_FOUND","当前范围未找到设备");}}}
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(12);return q;}
 private static Instant instant(ResultSet r,String key)throws SQLException{var value=r.getObject(key,OffsetDateTime.class);if(value==null)throw unavailable();Instant at=value.toInstant();if(at.getNano()%1000000!=0||at.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||at.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw unavailable();return at;}
 private static boolean reference(String value){return value!=null&&value.matches("sha256:[0-9a-f]{64}");}
 private static void matching(Access a,Scope s){if(a==null||s==null)throw invalid();if(!a.tenantId().equals(s.tenantId()))throw new DomainException(404,"BACKUP_VERSION_NOT_FOUND","当前范围未找到版本登记");}
 private static void validate(Command c){matching(c.access(),c.scope());if(c.id()==null||c.runVersion()<1||c.runVersion()>=MAX||c.archiveVersion()<1||c.archiveVersion()>MAX||c.lease()==null||!reference(c.objectReference())||!reference(c.executionReference())||c.trace()==null||!c.trace().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();}
 private static String fingerprint(Command c,Candidate p){try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeUTF("MOP_BACKUP_VERSION_REGISTRATION_V1");Scope s=c.scope();for(UUID id:List.of(s.tenantId(),s.assetId(),s.runId(),s.versionId(),c.id()))out.writeUTF(id.toString());out.writeInt(s.attemptNumber());out.writeLong(c.runVersion());out.writeLong(c.archiveVersion());out.writeUTF(c.lease().requestId().toString());out.writeUTF(c.lease().owner());out.writeLong(c.lease().fencingToken());out.writeUTF(c.objectReference());out.writeUTF(c.executionReference());out.writeUTF(p.encryptedSha256());out.writeInt(p.encryptedBytes());out.writeUTF(p.keyReference());out.writeUTF(p.archiveVerificationReference());out.writeLong(p.objectObservedAt().getEpochSecond());out.writeInt(p.objectObservedAt().getNano());out.flush();return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}catch(IOException|NoSuchAlgorithmException impossible){throw unavailable();}}
 private static DomainException proofFailure(Exception e){if(e instanceof DomainException d&&d.status==403)return new DomainException(403,"BACKUP_VERSION_PROOF_ACCESS_DENIED","原对象或完成证明当前未获核验授权");return new DomainException(503,"BACKUP_VERSION_PROOF_UNVERIFIED","原对象或采集完成证明尚未通过核查；请查询原登记命令，不授权新执行");}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","版本登记范围、版本或原命令引用无效");}
 private static DomainException conflict(){return new DomainException(409,"BACKUP_VERSION_REGISTRATION_CONFLICT","原信封、运行尝试、租约或登记命令冲突；请核查原请求");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_VERSION_REGISTRATION_UNCONFIRMED","版本登记存储或完成关联未能核对；请查询原登记命令，不假定事务未提交");}
}
