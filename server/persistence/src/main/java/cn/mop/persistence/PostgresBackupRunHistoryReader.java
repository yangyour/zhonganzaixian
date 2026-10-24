package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupRunHistoryReader;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
/** SELECT-only footprint, with authorization share locks; no device/object/KMS access or state writes. */
public final class PostgresBackupRunHistoryReader implements BackupRunHistoryReader {
 private final DataSource source;private final PostgresBackupTaskAuthorization authorization;
 public PostgresBackupRunHistoryReader(DataSource source,PostgresBackupTaskAuthorization authorization){this.source=Objects.requireNonNull(source);this.authorization=Objects.requireNonNull(authorization);}
 public Page read(Access access,UUID asset,Cursor before,int size){if(access==null||asset==null||size<1||size>50)throw new DomainException(400,"VALIDATION_ERROR","备份运行历史范围无效");
  try(var c=source.getConnection()){c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{
   try(var q=query(c,"SELECT id FROM public.mop_asset WHERE tenant_id=? AND id=? FOR SHARE")){q.setObject(1,access.tenantId());q.setObject(2,asset);try(var r=q.executeQuery()){if(!r.next())throw missing();}}
   authorize(c,access,asset);
   Instant observed;try(var q=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=q.executeQuery()){if(!r.next())throw unavailable();observed=r.getObject(1,OffsetDateTime.class).toInstant();}
   long total;try(var q=query(c,"SELECT count(DISTINCT run_id) FROM public.mop_backup_run_event WHERE tenant_id=? AND asset_id=?")){q.setObject(1,access.tenantId());q.setObject(2,asset);try(var r=q.executeQuery()){if(!r.next())throw unavailable();total=r.getLong(1);}}
   var rows=new ArrayList<Entry>();String sql="SELECT * FROM (SELECT DISTINCT ON (run_id) run_id,version,state,queued_at,observed_at,attempt_number,snapshot,snapshot_sha256,snapshot_format FROM public.mop_backup_run_event WHERE tenant_id=? AND asset_id=? ORDER BY run_id,version DESC) latest"+(before==null?"":" WHERE (queued_at,run_id)<(?,?::uuid)")+" ORDER BY queued_at DESC,run_id DESC LIMIT ?";
   try(var q=query(c,sql)){q.setObject(1,access.tenantId());q.setObject(2,asset);int p=3;if(before!=null){q.setObject(p++,OffsetDateTime.ofInstant(before.queuedAt(),ZoneOffset.UTC));q.setString(p++,before.runId().toString());}q.setInt(p,size+1);try(var r=q.executeQuery()){while(r.next()){
    var id=r.getObject("run_id",UUID.class);String hash=r.getString("snapshot_sha256");var state=new BackupRunSnapshotCodec().decode(r.getBytes("snapshot"),hash,id,access.tenantId(),asset);
    if(!BackupRunSnapshotCodec.FORMAT.equals(r.getString("snapshot_format"))||state.version()!=r.getLong("version")||!state.state().name().equals(r.getString("state"))||state.attempts().size()!=r.getInt("attempt_number")||!state.queuedAt().equals(r.getObject("queued_at",OffsetDateTime.class).toInstant())||!(state.attempts().isEmpty()?state.queuedAt():state.attempts().get(state.attempts().size()-1).observedAt()).equals(r.getObject("observed_at",OffsetDateTime.class).toInstant()))throw unavailable();
    if(before!=null&&(state.queuedAt().isAfter(before.queuedAt())||state.queuedAt().equals(before.queuedAt())&&id.toString().compareTo(before.runId().toString())>=0))throw unavailable();rows.add(new Entry(id,state,hash));
   }}}
   if(before==null&&rows.size()!=Math.min(total,size+1L))throw unavailable();
   Cursor next=null;if(rows.size()>size){rows.remove(size);var last=rows.get(size-1);next=new Cursor(last.state().queuedAt(),last.runId());}
   authorize(c,access,asset);var result=new Page(access,asset,observed,size,total,rows,next);c.commit();return result;
  }catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}
  catch(DomainException failure){throw failure;}catch(Exception failure){throw unavailable();}
 }
 private void authorize(Connection c,Access a,UUID asset)throws SQLException{authorization.require(c,new PostgresBackupArchiveRepository.Access(a.tenantId(),a.actorId(),a.permissionVersion()),a.tenantId(),asset,BackupTaskGrant.Operation.RUN_HISTORY);PostgresAuthorization.require(c,a.actorId().toString(),a.tenantId().toString(),a.permissionVersion(),"assets:read");}
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(10);return q;}
 private static DomainException missing(){return new DomainException(404,"BACKUP_RUN_NOT_FOUND","当前范围不存在该资产的备份运行");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_RUN_HISTORY_UNAVAILABLE","备份运行快照或完整计数无法核对；未返回空成功");}
}
