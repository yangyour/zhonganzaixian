package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupRunState;
import cn.mop.domain.backup.BackupRunState.*;
import javax.sql.DataSource;
import java.io.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Internal journal only. Never dispatches SSH or releases a device lease. No default authorization/evidence adapters. */
public final class PostgresBackupRunRepository {
 /** Check current scope and hold authorization revocation locks until commit. */
 @FunctionalInterface public interface Authorizer {void authorize(Connection c,UUID tenant,UUID asset,UUID run)throws SQLException;}
 /** Verify current approved execution prerequisites from trusted records; hold revocation/policy locks until commit. No external calls. */
 @FunctionalInterface public interface StartVerifier {void verify(Connection c,UUID run,BackupRunState state,LeaseClaim lease)throws SQLException;}
 /** Verify already recorded result/object evidence bound to this attempt, never accept a checksum alone as proof. */
 @FunctionalInterface public interface CompletionVerifier {void verify(Connection c,UUID run,BackupRunState prior,Outcome outcome,Failure failure,StoredVersion stored,String reference)throws SQLException;}
 public record LeaseClaim(UUID requestId,String owner,long fencingToken){public LeaseClaim{if(requestId==null||owner==null||!owner.matches("[A-Za-z0-9._:-]{1,128}")||fencingToken<1||fencingToken>9007199254740991L)throw invalid();}}
 public record LeaseBinding(LeaseClaim claim,long eventVersion){public LeaseBinding{if(claim==null||eventVersion<1||eventVersion>9007199254740991L)throw invalid();}}
 public record Receipt(UUID runId,UUID commandId,String action,BackupRunState state,LeaseBinding lease,Instant recordedAt,boolean replay){public boolean deviceExecutionAuthorized(){return false;}public boolean objectVerifiedByJournal(){return false;}}
 private enum Action{QUEUE,START,MARK_UNKNOWN,SUCCEED,FAIL}
 private record Command(UUID tenant,UUID asset,UUID run,UUID id,Action action,long expectedVersion,LeaseClaim lease,Failure failure,StoredVersion stored,String reference,String trace){}
 private record Event(Receipt receipt,String commandHash){}
 private final DataSource source;private final Authorizer authorizer;private final StartVerifier startVerifier;private final CompletionVerifier completionVerifier;private final BackupRunSnapshotCodec codec=new BackupRunSnapshotCodec();
 public PostgresBackupRunRepository(DataSource source,Authorizer authorizer,StartVerifier startVerifier,CompletionVerifier completionVerifier){this.source=Objects.requireNonNull(source);this.authorizer=Objects.requireNonNull(authorizer);this.startVerifier=Objects.requireNonNull(startVerifier);this.completionVerifier=Objects.requireNonNull(completionVerifier);}
 public Receipt queue(UUID tenant,UUID asset,UUID run,UUID command,String trace){return apply(new Command(tenant,asset,run,command,Action.QUEUE,-1,null,null,null,null,trace));}
 public Receipt start(UUID tenant,UUID asset,UUID run,UUID command,long version,LeaseClaim lease,String trace){return apply(new Command(tenant,asset,run,command,Action.START,version,lease,null,null,null,trace));}
 public Receipt markUnknown(UUID tenant,UUID asset,UUID run,UUID command,long version,LeaseClaim lease,String trace){return apply(new Command(tenant,asset,run,command,Action.MARK_UNKNOWN,version,lease,null,null,null,trace));}
 public Receipt fail(UUID tenant,UUID asset,UUID run,UUID command,long version,LeaseClaim lease,Failure failure,String evidence,String trace){return apply(new Command(tenant,asset,run,command,Action.FAIL,version,lease,failure,null,evidence,trace));}
 public Receipt succeed(UUID tenant,UUID asset,UUID run,UUID command,long version,LeaseClaim lease,StoredVersion stored,String evidence,String trace){return apply(new Command(tenant,asset,run,command,Action.SUCCEED,version,lease,null,stored,evidence,trace));}
 public Optional<Receipt> current(UUID tenant,UUID asset,UUID run){identity(tenant,asset,run);return transaction(tenant,asset,run,c->{Event e=find(c,tenant,asset,run,null);return e==null?Optional.empty():Optional.of(e.receipt());});}
 private Receipt apply(Command input){
  validate(input);String fingerprint=fingerprint(input);
  return transaction(input.tenant(),input.asset(),input.run(),c->{
   Event replay=find(c,input.tenant(),input.asset(),input.run(),input.id());
   if(replay!=null){if(!fingerprint.equals(replay.commandHash()))throw conflict();var r=replay.receipt();return new Receipt(r.runId(),r.commandId(),r.action(),r.state(),r.lease(),r.recordedAt(),true);}
   Event latest=find(c,input.tenant(),input.asset(),input.run(),null);Instant at=transactionTime(c);BackupRunState next;LeaseBinding binding=null;
   if(input.action()==Action.QUEUE){if(latest!=null)throw conflict();next=BackupRunState.queued(input.tenant(),input.asset(),at);}
   else{
    if(latest==null)throw new DomainException(404,"BACKUP_RUN_NOT_FOUND","当前范围内未找到运行记录");var previous=latest.receipt().state();
    if(input.expectedVersion()!=previous.version())throw new DomainException(409,"VERSION_CONFLICT","备份运行记录版本已变化");
    if(input.action()==Action.START){
     next=previous.start(input.expectedVersion(),at);startVerifier.verify(c,input.run(),previous,input.lease());binding=currentLease(c,input,at);
    }else{
     binding=latest.receipt().lease();if(binding==null||!binding.claim().equals(input.lease()))throw conflict();
     next=switch(input.action()){
      case MARK_UNKNOWN->previous.markUnknown(input.expectedVersion(),at);
      case FAIL->previous.fail(input.expectedVersion(),at,input.failure());
      case SUCCEED->previous.succeed(input.expectedVersion(),at,input.stored());
      default->throw conflict();
     };
     if(input.action()==Action.FAIL||input.action()==Action.SUCCEED)completionVerifier.verify(c,input.run(),previous,input.action()==Action.FAIL?Outcome.FAILED:Outcome.SUCCEEDED,input.failure(),input.stored(),input.reference());
    }
   }
   return insert(c,input,fingerprint,next,binding,at);
  });
 }
 private LeaseBinding currentLease(Connection c,Command command,Instant at)throws SQLException{
  try(var q=statement(c,"SELECT *,clock_timestamp()<lease_until AS still_valid FROM public.mop_backup_device_lease_event WHERE tenant_id=? AND asset_id=? ORDER BY version DESC LIMIT 1")){
   q.setObject(1,command.tenant());q.setObject(2,command.asset());try(var r=q.executeQuery()){
    if(!r.next()||r.getObject("released_at")!=null||!r.getBoolean("still_valid")||at.isBefore(instant(r,"observed_at"))||!command.lease().requestId().equals(r.getObject("request_id",UUID.class))||!command.lease().owner().equals(r.getString("worker_owner"))||command.lease().fencingToken()!=r.getLong("fencing_token"))throw conflict();
    return new LeaseBinding(command.lease(),r.getLong("version"));
   }
  }
 }
 private Receipt insert(Connection c,Command command,String hash,BackupRunState state,LeaseBinding binding,Instant at)throws SQLException{
  var document=codec.encode(command.run(),state);
  try(var q=statement(c,"INSERT INTO public.mop_backup_run_event(tenant_id,run_id,asset_id,version,command_id,command_sha256,action,state,queued_at,observed_at,attempt_number,lease_request_id,lease_event_version,lease_owner,fencing_token,verification_reference,snapshot,snapshot_sha256,trace_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *")){
   q.setObject(1,command.tenant());q.setObject(2,command.run());q.setObject(3,command.asset());q.setLong(4,state.version());q.setObject(5,command.id());q.setString(6,hash);q.setString(7,command.action().name());q.setString(8,state.state().name());q.setObject(9,state.queuedAt().atOffset(ZoneOffset.UTC));q.setObject(10,at.atOffset(ZoneOffset.UTC));q.setInt(11,state.attempts().size());
   if(binding==null){q.setNull(12,Types.OTHER);q.setNull(13,Types.BIGINT);q.setNull(14,Types.VARCHAR);q.setNull(15,Types.BIGINT);}else{q.setObject(12,binding.claim().requestId());q.setLong(13,binding.eventVersion());q.setString(14,binding.claim().owner());q.setLong(15,binding.claim().fencingToken());}
   q.setString(16,command.reference());q.setBytes(17,document.bytes());q.setString(18,document.sha256());q.setString(19,command.trace());try(var r=q.executeQuery()){if(!r.next())throw unavailable();return event(r).receipt();}
  }
 }
 private Event find(Connection c,UUID tenant,UUID asset,UUID run,UUID command)throws SQLException{
  try(var q=statement(c,"SELECT * FROM public.mop_backup_run_event WHERE tenant_id=? AND asset_id=? AND run_id=?"+(command==null?"":" AND command_id=?")+" ORDER BY version DESC LIMIT 1")){
   q.setObject(1,tenant);q.setObject(2,asset);q.setObject(3,run);if(command!=null)q.setObject(4,command);try(var r=q.executeQuery()){return r.next()?event(r):null;}
  }
 }
 private Event event(ResultSet r)throws SQLException{
  if(!"INTERNAL_BACKUP_RUN_JOURNAL".equals(r.getString("record_source"))||r.getBoolean("device_execution_authorized")||!BackupRunSnapshotCodec.FORMAT.equals(r.getString("snapshot_format")))throw unavailable();
  UUID run=r.getObject("run_id",UUID.class),tenant=r.getObject("tenant_id",UUID.class),asset=r.getObject("asset_id",UUID.class);var state=codec.decode(r.getBytes("snapshot"),r.getString("snapshot_sha256"),run,tenant,asset);Instant observed=instant(r,"observed_at");
  if(state.version()!=r.getLong("version")||!state.queuedAt().equals(instant(r,"queued_at"))||state.attempts().size()!=r.getInt("attempt_number")||!state.state().name().equals(r.getString("state"))||!observed.equals(state.attempts().isEmpty()?state.queuedAt():state.attempts().get(state.attempts().size()-1).observedAt()))throw unavailable();
  try {
   Action action=Action.valueOf(r.getString("action"));
   String hash=r.getString("command_sha256"),reference=r.getString("verification_reference");
   UUID command=r.getObject("command_id",UUID.class),lease=r.getObject("lease_request_id",UUID.class);
   if(command==null||hash==null||!hash.matches("[0-9a-f]{64}"))throw unavailable();
   boolean terminal=action==Action.FAIL||action==Action.SUCCEED;
   if(terminal?(reference==null||!reference.matches("sha256:[0-9a-f]{64}")):reference!=null)throw unavailable();
   LeaseBinding binding;
   if(action==Action.QUEUE){
    if(!state.attempts().isEmpty()||lease!=null||r.getObject("lease_event_version")!=null||r.getObject("lease_owner")!=null||r.getObject("fencing_token")!=null)throw unavailable();
    binding=null;
   }else{
    if(state.attempts().isEmpty()||lease==null||r.getObject("lease_event_version")==null||r.getObject("fencing_token")==null)throw unavailable();
    Outcome expected=switch(action){case START->Outcome.RUNNING;case MARK_UNKNOWN->Outcome.RESULT_UNKNOWN;case FAIL->Outcome.FAILED;case SUCCEED->Outcome.SUCCEEDED;default->throw unavailable();};
    if(state.attempts().get(state.attempts().size()-1).outcome()!=expected)throw unavailable();
    binding=new LeaseBinding(new LeaseClaim(lease,r.getString("lease_owner"),r.getLong("fencing_token")),r.getLong("lease_event_version"));
   }
   return new Event(new Receipt(run,command,action.name(),state,binding,observed,false),hash);
  }catch(IllegalArgumentException|NullPointerException|DomainException rejected){throw unavailable();}
 }
 private interface Work<T>{T run(Connection c)throws SQLException;}
 private <T>T transaction(UUID tenant,UUID asset,UUID run,Work<T> work){
  try(Connection c=source.getConnection()){
   c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
   try{
    try(var q=c.createStatement()){q.execute("SET LOCAL lock_timeout='5s'");q.execute("SET LOCAL statement_timeout='10s'");}
    authorizer.authorize(c,tenant,asset,run);
    try(var q=statement(c,"SELECT id FROM public.mop_asset WHERE tenant_id=? AND id=? FOR UPDATE")){q.setObject(1,tenant);q.setObject(2,asset);try(var r=q.executeQuery()){if(!r.next())throw new DomainException(404,"ASSET_NOT_FOUND","当前范围内未找到设备");}}
    T result=work.run(c);c.commit();return result;
   }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
  }catch(SQLException e){if("23505".equals(e.getSQLState())||"23514".equals(e.getSQLState()))throw conflict();if("23503".equals(e.getSQLState()))throw new DomainException(404,"BACKUP_RUN_REFERENCE_NOT_FOUND","运行或租约引用不可见");throw unavailable();}
 }
 private static Instant transactionTime(Connection c)throws SQLException{try(var q=statement(c,"SELECT date_trunc('milliseconds',transaction_timestamp())");var r=q.executeQuery()){if(!r.next())throw unavailable();return r.getObject(1,OffsetDateTime.class).toInstant();}}
 private static Instant instant(ResultSet r,String key)throws SQLException{return r.getObject(key,OffsetDateTime.class).toInstant();}
 private static PreparedStatement statement(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(12);return q;}
 private static void validate(Command c){identity(c.tenant(),c.asset(),c.run());if(c.id()==null||c.trace()==null||!c.trace().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();if(c.action()!=Action.QUEUE&&(c.expectedVersion()<0||c.expectedVersion()>9007199254740991L||c.lease()==null))throw invalid();if(c.action()==Action.FAIL&&c.failure()==null||c.action()==Action.SUCCEED&&c.stored()==null)throw invalid();if((c.action()==Action.FAIL||c.action()==Action.SUCCEED)&&(c.reference()==null||!c.reference().matches("sha256:[0-9a-f]{64}")))throw invalid();}
 private static void identity(UUID tenant,UUID asset,UUID run){if(tenant==null||asset==null||run==null)throw invalid();}
 private static String fingerprint(Command c){try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
  out.writeUTF("MOP_BACKUP_RUN_COMMAND_V1");out.writeUTF(c.tenant().toString());out.writeUTF(c.asset().toString());out.writeUTF(c.run().toString());out.writeUTF(c.id().toString());out.writeUTF(c.action().name());out.writeLong(c.expectedVersion());out.writeBoolean(c.lease()!=null);if(c.lease()!=null){out.writeUTF(c.lease().requestId().toString());out.writeUTF(c.lease().owner());out.writeLong(c.lease().fencingToken());}out.writeUTF(c.failure()==null?"":c.failure().name());out.writeUTF(c.reference()==null?"":c.reference());out.writeBoolean(c.stored()!=null);if(c.stored()!=null){var v=c.stored();out.writeUTF(v.tenantId().toString());out.writeUTF(v.assetId().toString());out.writeUTF(v.versionId().toString());out.writeUTF(v.encryptedObjectSha256());out.writeLong(v.encryptedBytes());out.writeLong(v.persistedAt().toEpochMilli());}out.flush();return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
 }catch(IOException|NoSuchAlgorithmException impossible){throw unavailable();}}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","备份运行命令、版本、租约或核查引用无效");}
 private static DomainException conflict(){return new DomainException(409,"BACKUP_RUN_CONFLICT","备份运行、命令或当前尝试租约冲突；未知结果不允许盲目重试");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_RUN_STORE_UNAVAILABLE","备份运行存储不可用；请按原请求核查，不要假定写入失败");}
}
