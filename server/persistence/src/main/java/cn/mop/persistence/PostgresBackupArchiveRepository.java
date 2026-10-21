package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import javax.sql.DataSource;
import java.io.*;import java.security.*;import java.sql.*;import java.time.*;import java.util.*;

/** Durable original encrypted source and append-only facts. Never calls PUT, schedules a worker or grants execution. */
public final class PostgresBackupArchiveRepository {
 private static final long MAX=9007199254740991L;
 public record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{if(tenantId==null||actorId==null||permissionVersion<1||permissionVersion>MAX)throw invalid();}}
 public record Dispatch(UUID id,String owner){public Dispatch{if(id==null||owner==null||!owner.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();}}
 public enum Action{READ,STAGE,START_STORE,MARK_UNCONFIRMED,CONFIRM_NO_DISPATCH,VERIFY_OBJECT}
 public enum Phase{STAGED,STORE_STARTED,VERIFY_REQUIRED,VERIFIED}
 /** Validate current scope/action and hold revocation locks through this transaction. No external calls. */
 @FunctionalInterface public interface Authorizer{void authorize(Connection c,Access access,Scope scope,Action action)throws SQLException;}
 /** Must authenticate original ciphertext and approved binding. Called outside SQL transactions, between two authorization/CAS checks. */
 @FunctionalInterface public interface EnvelopeVerifier{void verify(Access access,BackupArchivePayload payload)throws Exception;}
 /** VERIFY_OBJECT: read original object and authenticate it. CONFIRM_NO_DISPATCH: prove no PUT in flight or committed for this dispatch. A hash alone is not proof. */
 @FunctionalInterface public interface EvidenceVerifier{void verify(Access access,Source source,Event prior,Action action,String evidenceReference)throws Exception;}
 public record Source(BackupArchivePayload payload,UUID submissionId,String submissionHash,long runVersion,UUID createdBy,Instant recordedAt){}
 public record Event(long version,UUID commandId,String commandHash,Action action,Phase phase,Dispatch dispatch,long dispatchCount,String evidenceReference,UUID actorId,Instant observedAt){
  public boolean requiresReadOnlyReconciliation(){return phase==Phase.STORE_STARTED||phase==Phase.VERIFY_REQUIRED;}
  public boolean storeDispatchAuthorized(){return false;}public boolean versionRegistered(){return false;}public boolean backupRunCompleted(){return false;}
 }
 public record Receipt(Source source,Event event,boolean replay){}
 private record Command(Access access,Scope scope,UUID id,long expectedVersion,Action action,Dispatch dispatch,String evidence,String trace){}
 private record Prepared(Source source,Event prior,Receipt replay){}
 private final DataSource source;private final Authorizer authorizer;private final EnvelopeVerifier envelopeVerifier;private final EvidenceVerifier evidenceVerifier;
 public PostgresBackupArchiveRepository(DataSource source,Authorizer authorizer,EnvelopeVerifier envelopeVerifier,EvidenceVerifier evidenceVerifier){this.source=Objects.requireNonNull(source);this.authorizer=Objects.requireNonNull(authorizer);this.envelopeVerifier=Objects.requireNonNull(envelopeVerifier);this.evidenceVerifier=Objects.requireNonNull(evidenceVerifier);}
 public Receipt stage(Access access,UUID submissionId,long runVersion,BackupArchivePayload payload,String trace){
  if(payload==null)throw invalid();Scope scope=payload.scope();matching(access,scope);identity(submissionId,trace);if(runVersion<1||runVersion>MAX)throw invalid();String hash=stageHash(submissionId,runVersion,payload);
  Receipt existing=transaction(access,scope,Action.STAGE,c->stagePreflight(c,scope,submissionId,runVersion,payload,hash));if(existing!=null)return existing;
  try{envelopeVerifier.verify(access,payload);}catch(Exception rejected){throw proofFailure(rejected);}
  return transaction(access,scope,Action.STAGE,c->{Receipt replay=stagePreflight(c,scope,submissionId,runVersion,payload,hash);if(replay!=null)return replay;
   Source staged=insertSource(c,access,submissionId,runVersion,payload,hash);Event event=insertEvent(c,access,scope,0,submissionId,hash,Action.STAGE,Phase.STAGED,null,0,null,trace);return new Receipt(staged,event,false);
  });
 }
 public Optional<Receipt> current(Access access,Scope scope){matching(access,scope);return transaction(access,scope,Action.READ,c->{Source s=findSource(c,scope);if(s==null)return Optional.empty();return Optional.of(new Receipt(s,requiredLatest(c,s),false));});}
 public Receipt startStore(Access a,Scope s,UUID command,long version,Dispatch dispatch,String trace){return apply(new Command(a,s,command,version,Action.START_STORE,dispatch,null,trace));}
 public Receipt markUnconfirmed(Access a,Scope s,UUID command,long version,Dispatch dispatch,String trace){return apply(new Command(a,s,command,version,Action.MARK_UNCONFIRMED,dispatch,null,trace));}
 public Receipt confirmNoDispatch(Access a,Scope s,UUID command,long version,Dispatch dispatch,String evidence,String trace){return apply(new Command(a,s,command,version,Action.CONFIRM_NO_DISPATCH,dispatch,evidence,trace));}
 public Receipt verifyObject(Access a,Scope s,UUID command,long version,String evidence,String trace){return apply(new Command(a,s,command,version,Action.VERIFY_OBJECT,null,evidence,trace));}
 private Receipt apply(Command command){
  validate(command);String hash=commandHash(command);boolean proof=command.action()==Action.CONFIRM_NO_DISPATCH||command.action()==Action.VERIFY_OBJECT;
  if(proof){Prepared p=transaction(command.access(),command.scope(),command.action(),c->prepare(c,command,hash));if(p.replay()!=null)return p.replay();
   try{evidenceVerifier.verify(command.access(),p.source(),p.prior(),command.action(),command.evidence());}catch(Exception rejected){throw proofFailure(rejected);}
  }
  return transaction(command.access(),command.scope(),command.action(),c->{Prepared p=prepare(c,command,hash);if(p.replay()!=null)return p.replay();Event prior=p.prior();Dispatch dispatch=command.action()==Action.START_STORE?command.dispatch():prior.dispatch();long count=prior.dispatchCount()+(command.action()==Action.START_STORE?1:0);
   Phase phase=switch(command.action()){case START_STORE->Phase.STORE_STARTED;case MARK_UNCONFIRMED->Phase.VERIFY_REQUIRED;case CONFIRM_NO_DISPATCH->Phase.STAGED;case VERIFY_OBJECT->Phase.VERIFIED;default->throw invalid();};
   return new Receipt(p.source(),insertEvent(c,command.access(),command.scope(),prior.version()+1,command.id(),hash,command.action(),phase,dispatch,count,command.evidence(),command.trace()),false);
  });
 }
 private Receipt stagePreflight(Connection c,Scope scope,UUID id,long runVersion,BackupArchivePayload payload,String hash)throws SQLException{
  Source existing=findSource(c,scope);
  if(existing!=null){if(!existing.submissionId().equals(id)||!existing.submissionHash().equals(hash)||existing.runVersion()!=runVersion||!existing.payload().sha256().equals(payload.sha256()))throw conflict();Event original=findEvent(c,existing,id);if(original==null||original.version()!=0||original.action()!=Action.STAGE)throw unavailable();return new Receipt(existing,original,true);}
  // Fail before expensive key verification when another version already owns this run/attempt.
  try(var q=query(c,"SELECT 1 FROM public.mop_backup_archive_submission WHERE tenant_id=? AND run_id=? AND attempt_number=?")){q.setObject(1,scope.tenantId());q.setObject(2,scope.runId());q.setInt(3,scope.attemptNumber());try(var r=q.executeQuery()){if(r.next())throw conflict();}}
  running(c,scope,runVersion);return null;
 }
 private Prepared prepare(Connection c,Command command,String hash)throws SQLException{
  Source staged=findSource(c,command.scope());if(staged==null)throw new DomainException(404,"BACKUP_ARCHIVE_NOT_FOUND","当前范围内没有原信封任务");Event replay=findEvent(c,staged,command.id());
  if(replay!=null){if(!replay.commandHash().equals(hash))throw conflict();return new Prepared(staged,null,new Receipt(staged,replay,true));}
  Event prior=requiredLatest(c,staged);if(prior.version()!=command.expectedVersion())throw new DomainException(409,"VERSION_CONFLICT","归档任务状态版本已变化");if(prior.version()>=MAX||prior.phase()==Phase.VERIFIED)throw conflict();
  switch(command.action()){
   case START_STORE->{if(prior.phase()!=Phase.STAGED||prior.dispatchCount()>=MAX||prior.dispatch()!=null&&prior.dispatch().id().equals(command.dispatch().id()))throw conflict();running(c,command.scope(),null);}
   case MARK_UNCONFIRMED,CONFIRM_NO_DISPATCH->{if(!Set.of(Phase.STORE_STARTED,Phase.VERIFY_REQUIRED).contains(prior.phase())||!Objects.equals(prior.dispatch(),command.dispatch()))throw conflict();}
   case VERIFY_OBJECT->{if(!Set.of(Phase.STAGED,Phase.STORE_STARTED,Phase.VERIFY_REQUIRED).contains(prior.phase()))throw conflict();}
   default->throw invalid();
  }
  return new Prepared(staged,prior,null);
 }
 private void running(Connection c,Scope scope,Long exactVersion)throws SQLException{
  try(var q=query(c,"SELECT asset_id,version,attempt_number,state FROM public.mop_backup_run_event WHERE tenant_id=? AND run_id=? ORDER BY version DESC LIMIT 1")){q.setObject(1,scope.tenantId());q.setObject(2,scope.runId());try(var r=q.executeQuery()){if(!r.next()||!scope.assetId().equals(r.getObject("asset_id",UUID.class))||scope.attemptNumber()!=r.getInt("attempt_number")||!"RUNNING".equals(r.getString("state"))||exactVersion!=null&&r.getLong("version")!=exactVersion)throw conflict();}}
 }
 private Source insertSource(Connection c,Access a,UUID id,long runVersion,BackupArchivePayload p,String hash)throws SQLException{
  Scope s=p.scope();try(var q=query(c,"INSERT INTO public.mop_backup_archive_submission(tenant_id,version_id,asset_id,run_id,attempt_number,run_version,submission_id,submission_sha256,encoded_envelope,envelope_sha256,envelope_bytes,key_reference,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *")){
   q.setObject(1,s.tenantId());q.setObject(2,s.versionId());q.setObject(3,s.assetId());q.setObject(4,s.runId());q.setInt(5,s.attemptNumber());q.setLong(6,runVersion);q.setObject(7,id);q.setString(8,hash);q.setBytes(9,p.bytes());q.setString(10,p.sha256());q.setInt(11,p.size());q.setString(12,p.keyReference());q.setObject(13,a.actorId());try(var r=q.executeQuery()){if(!r.next())throw unavailable();return sourceRow(r,s);}
  }
 }
 private Source findSource(Connection c,Scope s)throws SQLException{
  try(var q=query(c,"SELECT * FROM public.mop_backup_archive_submission WHERE tenant_id=? AND version_id=? AND asset_id=? AND run_id=? AND attempt_number=?")){q.setObject(1,s.tenantId());q.setObject(2,s.versionId());q.setObject(3,s.assetId());q.setObject(4,s.runId());q.setInt(5,s.attemptNumber());try(var r=q.executeQuery()){return r.next()?sourceRow(r,s):null;}}
 }
 private Source sourceRow(ResultSet r,Scope expected)throws SQLException{
  try{var p=BackupArchivePayload.parse(r.getBytes("encoded_envelope"),r.getString("envelope_sha256"));if(!expected.equals(p.scope())||p.size()!=r.getInt("envelope_bytes")||!p.keyReference().equals(r.getString("key_reference"))||!BackupArchivePayload.FORMAT.equals(r.getString("envelope_format"))||!BackupArchivePayload.ALGORITHM.equals(r.getString("encryption_algorithm"))||!"INTERNAL_BACKUP_ARCHIVE_STAGING".equals(r.getString("record_source"))||r.getBoolean("storage_verified")||r.getBoolean("version_registered")||r.getBoolean("device_execution_authorized"))throw unavailable();
   long version=r.getLong("run_version");String hash=r.getString("submission_sha256");UUID id=r.getObject("submission_id",UUID.class),actor=r.getObject("created_by",UUID.class);Instant at=instant(r,"recorded_at");if(version<1||version>MAX||id==null||actor==null||!hash.matches("[0-9a-f]{64}")||!hash.equals(stageHash(id,version,p)))throw unavailable();return new Source(p,id,hash,version,actor,at);
  }catch(IllegalArgumentException|NullPointerException|DomainException rejected){throw unavailable();}
 }
 private Event requiredLatest(Connection c,Source source)throws SQLException{Event e=findEvent(c,source,null);if(e==null)throw unavailable();return e;}
 private Event findEvent(Connection c,Source source,UUID command)throws SQLException{
  Scope s=source.payload().scope();try(var q=query(c,"SELECT * FROM public.mop_backup_archive_event WHERE tenant_id=? AND version_id=?"+(command==null?"":" AND command_id=?")+" ORDER BY event_version DESC LIMIT 1")){q.setObject(1,s.tenantId());q.setObject(2,s.versionId());if(command!=null)q.setObject(3,command);try(var r=q.executeQuery()){return r.next()?eventRow(r,source.recordedAt()):null;}}
 }
 private Event insertEvent(Connection c,Access a,Scope s,long version,UUID command,String hash,Action action,Phase phase,Dispatch dispatch,long count,String evidence,String trace)throws SQLException{
  try(var q=query(c,"INSERT INTO public.mop_backup_archive_event(tenant_id,version_id,event_version,command_id,command_sha256,action,phase,dispatch_id,dispatch_owner,dispatch_count,evidence_reference,actor_id,trace_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *")){
   q.setObject(1,s.tenantId());q.setObject(2,s.versionId());q.setLong(3,version);q.setObject(4,command);q.setString(5,hash);q.setString(6,action.name());q.setString(7,phase.name());q.setObject(8,dispatch==null?null:dispatch.id());q.setString(9,dispatch==null?null:dispatch.owner());q.setLong(10,count);q.setString(11,evidence);q.setObject(12,a.actorId());q.setString(13,trace);try(var r=q.executeQuery()){if(!r.next())throw unavailable();return eventRow(r,Instant.parse("0001-01-01T00:00:00Z"));}
  }
 }
 private Event eventRow(ResultSet r,Instant stagedAt)throws SQLException{
  try{long v=r.getLong("event_version"),count=r.getLong("dispatch_count");Action action=Action.valueOf(r.getString("action"));Phase phase=Phase.valueOf(r.getString("phase"));UUID command=r.getObject("command_id",UUID.class),actor=r.getObject("actor_id",UUID.class),id=r.getObject("dispatch_id",UUID.class);String owner=r.getString("dispatch_owner"),hash=r.getString("command_sha256"),ref=r.getString("evidence_reference");Instant at=instant(r,"observed_at");
   if(v<0||v>MAX||count<0||count>MAX||command==null||actor==null||!hash.matches("[0-9a-f]{64}")||at.isBefore(stagedAt)||!"INTERNAL_BACKUP_ARCHIVE_JOURNAL".equals(r.getString("record_source"))||r.getBoolean("version_registered")||r.getBoolean("device_execution_authorized"))throw unavailable();Dispatch d=id==null?null:new Dispatch(id,owner);if(count==0?(d!=null||owner!=null):d==null)throw unavailable();boolean proof=action==Action.CONFIRM_NO_DISPATCH||action==Action.VERIFY_OBJECT;if(proof?(ref==null||!ref.matches("sha256:[0-9a-f]{64}")):ref!=null)throw unavailable();
   boolean coherent=switch(action){case STAGE->v==0&&count==0&&phase==Phase.STAGED;case START_STORE->v>0&&count>0&&phase==Phase.STORE_STARTED;case MARK_UNCONFIRMED->v>0&&count>0&&phase==Phase.VERIFY_REQUIRED;case CONFIRM_NO_DISPATCH->v>0&&count>0&&phase==Phase.STAGED;case VERIFY_OBJECT->v>0&&phase==Phase.VERIFIED;default->false;};if(!coherent)throw unavailable();return new Event(v,command,hash,action,phase,d,count,ref,actor,at);
  }catch(IllegalArgumentException|NullPointerException|DomainException rejected){throw unavailable();}
 }
 private interface Work<T>{T run(Connection c)throws SQLException;}
 private <T>T transaction(Access a,Scope s,Action action,Work<T> work){
  try(Connection c=source.getConnection()){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);try{
   try(var q=c.createStatement()){q.execute("SET LOCAL lock_timeout='5s'");q.execute("SET LOCAL statement_timeout='10s'");}authorizer.authorize(c,a,s,action);
   try(var q=query(c,"SELECT id FROM public.mop_asset WHERE tenant_id=? AND id=? FOR UPDATE")){q.setObject(1,s.tenantId());q.setObject(2,s.assetId());try(var r=q.executeQuery()){if(!r.next())throw new DomainException(404,"ASSET_NOT_FOUND","当前范围内没有设备");}}
   T result=work.run(c);c.commit();return result;
  }catch(SQLException|RuntimeException rejected){try{c.rollback();}catch(SQLException ignored){}throw rejected;}}
  catch(SQLException rejected){if("23505".equals(rejected.getSQLState())||"23514".equals(rejected.getSQLState()))throw conflict();if("23503".equals(rejected.getSQLState()))throw new DomainException(404,"BACKUP_ARCHIVE_REFERENCE_NOT_FOUND","归档范围引用不存在");throw unavailable();}
 }
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(12);return q;}
 private static Instant instant(ResultSet r,String key)throws SQLException{return r.getObject(key,OffsetDateTime.class).toInstant();}
 private static void matching(Access a,Scope s){if(a==null||s==null)throw invalid();if(!a.tenantId().equals(s.tenantId()))throw new DomainException(404,"BACKUP_ARCHIVE_NOT_FOUND","当前范围内没有原信封任务");}
 private static void identity(UUID id,String trace){if(id==null||trace==null||!trace.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();}
 private static void validate(Command c){matching(c.access(),c.scope());identity(c.id(),c.trace());if(c.expectedVersion()<0||c.expectedVersion()>MAX)throw invalid();if(c.action()!=Action.VERIFY_OBJECT&&c.dispatch()==null)throw invalid();if((c.action()==Action.VERIFY_OBJECT||c.action()==Action.CONFIRM_NO_DISPATCH)&&(c.evidence()==null||!c.evidence().matches("sha256:[0-9a-f]{64}")))throw invalid();}
 private interface Encode{void write(DataOutputStream out)throws IOException;}
 private static String fingerprint(Encode e){try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){e.write(out);out.flush();return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}catch(IOException|NoSuchAlgorithmException impossible){throw unavailable();}}
 private static void scope(DataOutputStream o,Scope s)throws IOException{o.writeUTF(s.tenantId().toString());o.writeUTF(s.assetId().toString());o.writeUTF(s.runId().toString());o.writeInt(s.attemptNumber());o.writeUTF(s.versionId().toString());}
 private static String stageHash(UUID id,long version,BackupArchivePayload p){return fingerprint(o->{o.writeUTF("MOP_BACKUP_ARCHIVE_STAGE_V1");scope(o,p.scope());o.writeUTF(id.toString());o.writeLong(version);o.writeUTF(p.sha256());o.writeInt(p.size());o.writeUTF(p.keyReference());});}
 private static String commandHash(Command c){return fingerprint(o->{o.writeUTF("MOP_BACKUP_ARCHIVE_COMMAND_V1");scope(o,c.scope());o.writeUTF(c.id().toString());o.writeUTF(c.action().name());o.writeLong(c.expectedVersion());o.writeBoolean(c.dispatch()!=null);if(c.dispatch()!=null){o.writeUTF(c.dispatch().id().toString());o.writeUTF(c.dispatch().owner());}o.writeUTF(c.evidence()==null?"":c.evidence());});}
 private static DomainException proofFailure(Exception e){if(e instanceof DomainException d&&d.status==403)return new DomainException(403,"BACKUP_ARCHIVE_ACCESS_DENIED","原信封或核查证据当前未获授权");return new DomainException(503,"BACKUP_ARCHIVE_PROOF_UNAVAILABLE","原信封认证或派发/对象证据尚未通过核查，未改变任务状态");}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","归档原信封任务参数、版本或核查引用无效");}
 private static DomainException conflict(){return new DomainException(409,"BACKUP_ARCHIVE_CONFLICT","原信封、运行尝试或派发状态冲突；未知写入不可盲目重派");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_ARCHIVE_STORE_UNAVAILABLE","原信封任务存储不可用；请核查原请求，不假定提交失败");}
}
