package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupDeviceLeaseState;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Internal coordination only. Not wired into HTTP, scheduling or SSH execution.
 * Required authorization and stop verification adapters have no default implementation. */
public final class PostgresBackupDeviceLeaseRepository {
 /** Must verify current authority and lock its revocation/version records until transaction commit. */
 @FunctionalInterface public interface Authorizer { void authorize(Connection connection,UUID tenant,UUID asset,UUID request)throws SQLException; }
 /** Must independently verify stopped/not-started evidence; a caller-supplied checksum alone is insufficient. */
 @FunctionalInterface public interface StopVerifier { void verifyStopped(Connection connection,BackupDeviceLeaseState state,String confirmationReference)throws SQLException; }
 public record Receipt(BackupDeviceLeaseState state,Instant observedAt,boolean replay){
  public boolean deviceExecutionAuthorized(){return false;}
  public BackupDeviceLeaseState.Status status(){return state.statusAt(observedAt);}
 }
 private final DataSource source;private final Authorizer authorizer;private final StopVerifier stopVerifier;
 public PostgresBackupDeviceLeaseRepository(DataSource source,Authorizer authorizer,StopVerifier stopVerifier){this.source=Objects.requireNonNull(source);this.authorizer=Objects.requireNonNull(authorizer);this.stopVerifier=Objects.requireNonNull(stopVerifier);}
 public Receipt acquire(UUID tenant,UUID asset,UUID request,String owner,Duration duration,String trace){
  // Validate before acquiring a connection. The fixed instant is validation only, never an observed lease time.
  BackupDeviceLeaseState.initial(tenant,asset,request,owner,Instant.EPOCH,duration);token(trace);
  return transaction(tenant,asset,request,c->{
   Event original=latest(c,tenant,asset,request);Instant at=now(c);
   if(original!=null){var s=original.state();if(!s.owner().equals(owner)||!s.duration().equals(duration))throw conflict();return new Receipt(s,at,true);}
   Event current=latest(c,tenant,asset,null);BackupDeviceLeaseState planned=current==null?BackupDeviceLeaseState.initial(tenant,asset,request,owner,at,duration):current.state().next(current.state().version(),request,owner,at,duration);
   return insert(c,planned,"ACQUIRE",null,trace);
  });
 }
 public Receipt renew(UUID tenant,UUID asset,UUID request,String owner,long fence,long expectedVersion,String trace){
  identity(tenant,asset,request);token(owner);token(trace);
  return transaction(tenant,asset,request,c->{var s=required(latest(c,tenant,asset,null),request);Instant at=now(c);var next=s.renew(expectedVersion,owner,fence,at);return next==s?new Receipt(s,at,false):insert(c,next,"RENEW",null,trace);});
 }
 public Receipt release(UUID tenant,UUID asset,UUID request,String owner,long fence,long expectedVersion,String confirmationReference,String trace){
  identity(tenant,asset,request);token(owner);token(trace);if(confirmationReference==null||!confirmationReference.matches("sha256:[0-9a-f]{64}"))throw invalid();
  return transaction(tenant,asset,request,c->{
   Event prior=latest(c,tenant,asset,request);Instant at=now(c);
   if(prior!=null&&prior.state().releasedAt()!=null){var s=prior.state();if(!s.owner().equals(owner)||s.fencingToken()!=fence||s.version()!=expectedVersion+1||!confirmationReference.equals(prior.confirmation()))throw conflict();return new Receipt(s,at,true);}
   var s=required(latest(c,tenant,asset,null),request);var next=s.release(expectedVersion,owner,fence,at);
   // A checksum is NOT proof of stop. The mandatory adapter must verify independently in this transaction.
   stopVerifier.verifyStopped(c,s,confirmationReference);
   return insert(c,next,"RELEASE",confirmationReference,trace);
  });
 }
 /** A scoped observation, not permission to execute. No journal row is created. */
 public Optional<Receipt> current(UUID tenant,UUID asset,UUID request){
  identity(tenant,asset,request);return transaction(tenant,asset,request,c->{Event event=latest(c,tenant,asset,null);if(event==null||!event.state().requestId().equals(request))return Optional.empty();return Optional.of(new Receipt(event.state(),now(c),false));});
 }
 private record Event(BackupDeviceLeaseState state,String confirmation){}
 private interface Work<T>{T run(Connection c)throws SQLException;}
 private <T>T transaction(UUID tenant,UUID asset,UUID request,Work<T> work){
  try(Connection c=source.getConnection()){
   c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
   try{
    try(var s=c.createStatement()){s.execute("SET LOCAL lock_timeout='5s'");s.execute("SET LOCAL statement_timeout='10s'");}
    authorizer.authorize(c,tenant,asset,request); // Also enforced for reads and original-response replay.
    try(var s=statement(c,"SELECT id FROM public.mop_asset WHERE tenant_id=? AND id=? FOR UPDATE")){s.setObject(1,tenant);s.setObject(2,asset);try(var r=s.executeQuery()){if(!r.next())throw new DomainException(404,"ASSET_NOT_FOUND","未找到当前范围内的设备");}}
    T result=work.run(c);c.commit();return result;
   }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
  }catch(SQLException e){if("23503".equals(e.getSQLState()))throw new DomainException(404,"ASSET_NOT_FOUND","未找到当前范围内的设备");if("23505".equals(e.getSQLState())||"23514".equals(e.getSQLState()))throw conflict();throw new DomainException(503,"BACKUP_DEVICE_COORDINATION_UNAVAILABLE","设备协调存储暂不可用；请核对原请求状态，不要假定写入失败");}
 }
 private static Event latest(Connection c,UUID tenant,UUID asset,UUID request)throws SQLException{
  try(var s=statement(c,"SELECT * FROM public.mop_backup_device_lease_event WHERE tenant_id=? AND asset_id=?"+(request==null?"":" AND request_id=?")+" ORDER BY version DESC LIMIT 1")){s.setObject(1,tenant);s.setObject(2,asset);if(request!=null)s.setObject(3,request);try(var r=s.executeQuery()){return r.next()?event(r):null;}}
 }
 private static Receipt insert(Connection c,BackupDeviceLeaseState s,String action,String confirmation,String trace)throws SQLException{
  try(var q=statement(c,"INSERT INTO public.mop_backup_device_lease_event(tenant_id,asset_id,version,fencing_token,request_id,worker_owner,lease_millis,action,release_confirmation_reference,trace_id) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING *")){
   q.setObject(1,s.tenantId());q.setObject(2,s.assetId());q.setLong(3,s.version());q.setLong(4,s.fencingToken());q.setObject(5,s.requestId());q.setString(6,s.owner());q.setLong(7,s.duration().toMillis());q.setString(8,action);q.setString(9,confirmation);q.setString(10,trace);
   try(var r=q.executeQuery()){if(!r.next())throw unavailable();var saved=event(r).state();return new Receipt(saved,saved.observedAt(),false);}
  }
 }
 private static Event event(ResultSet r)throws SQLException{
  if(!"INTERNAL_COORDINATION_JOURNAL".equals(r.getString("record_source"))||r.getBoolean("device_execution_authorized"))throw unavailable();
  var state=BackupDeviceLeaseState.rehydrate(new BackupDeviceLeaseState.Snapshot(r.getObject("tenant_id",UUID.class),r.getObject("asset_id",UUID.class),r.getObject("request_id",UUID.class),r.getString("worker_owner"),r.getLong("fencing_token"),r.getLong("version"),Duration.ofMillis(r.getLong("lease_millis")),instant(r,"acquired_at"),instant(r,"observed_at"),instant(r,"lease_until"),instant(r,"released_at")));
  return new Event(state,r.getString("release_confirmation_reference"));
 }
 private static BackupDeviceLeaseState required(Event e,UUID request){if(e==null||!e.state().requestId().equals(request))throw conflict();return e.state();}
 private static Instant instant(ResultSet r,String name)throws SQLException{OffsetDateTime value=r.getObject(name,OffsetDateTime.class);return value==null?null:value.toInstant();}
 private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){if(!r.next())throw unavailable();return r.getObject(1,OffsetDateTime.class).toInstant();}}
 private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(12);return s;}
 private static void identity(UUID tenant,UUID asset,UUID request){if(tenant==null||asset==null||request==null)throw invalid();}
 private static void token(String value){if(value==null||!value.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","设备协调请求格式无效");}
 private static DomainException conflict(){return new DomainException(409,"BACKUP_DEVICE_LEASE_CONFLICT","设备协调状态冲突；过期不证明执行已停止，不能自动接管");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_DEVICE_COORDINATION_UNAVAILABLE","设备协调存储状态无效");}
}
