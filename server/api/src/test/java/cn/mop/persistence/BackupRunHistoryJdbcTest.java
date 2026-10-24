package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.backup.*;
import cn.mop.domain.backup.BackupRunHistoryReader.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Protocol doubles only. No PostgreSQL connection or real grant. */
class BackupRunHistoryJdbcTest {
 final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),asset=UUID.randomUUID(),run=UUID.randomUUID();final Instant at=Instant.parse("2026-10-23T20:00:00Z");
 final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);final PostgresBackupTaskAuthorization auth=mock(PostgresBackupTaskAuthorization.class);final List<String> sql=new ArrayList<>();boolean missing=false,corrupt=false,wrongHeader=false;long total=1;
 BackupRunHistoryJdbcTest()throws Exception{when(source.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenAnswer(call->{String s=call.getArgument(0);sql.add(s);var q=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(q.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);
  if(s.startsWith("SELECT id"))when(r.next()).thenAnswer(a->!missing);
  else if(s.contains("clock_timestamp"))when(r.getObject(1,OffsetDateTime.class)).thenReturn(at.plusSeconds(10).atOffset(ZoneOffset.UTC));
  else if(s.startsWith("SELECT count"))when(r.getLong(1)).thenAnswer(a->total);
  else {var state=BackupRunState.queued(tenant,asset,at);var encoded=new BackupRunSnapshotCodec().encode(run,state);when(r.getObject("run_id",UUID.class)).thenReturn(run);when(r.getString("snapshot_sha256")).thenReturn(encoded.sha256());when(r.getBytes("snapshot")).thenAnswer(a->corrupt?new byte[]{1}:encoded.bytes());when(r.getString("snapshot_format")).thenReturn(BackupRunSnapshotCodec.FORMAT);when(r.getLong("version")).thenAnswer(a->wrongHeader?1L:0L);when(r.getString("state")).thenReturn("QUEUED");when(r.getInt("attempt_number")).thenReturn(0);for(String field:List.of("queued_at","observed_at"))when(r.getObject(field,OffsetDateTime.class)).thenReturn(at.atOffset(ZoneOffset.UTC));}
  return q;});}
 Page read(){return new PostgresBackupRunHistoryReader(source,auth).read(new Access(tenant,actor,1),asset,null,20);}
 @Test void validatesSnapshotHeadersAndUsesIndependentHistoryGrantTwice()throws Exception{try(var effective=mockStatic(PostgresAuthorization.class)){var p=read();assertEquals(1,p.totalCount());assertEquals(0,p.items().get(0).state().knownFailedAttemptCount());verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);verify(connection).setAutoCommit(false);verify(auth,times(2)).require(eq(connection),any(),eq(tenant),eq(asset),eq(BackupTaskGrant.Operation.RUN_HISTORY));verify(connection).commit();assertTrue(sql.stream().allMatch(s->s.startsWith("SELECT")));assertTrue(sql.stream().anyMatch(s->s.contains("DISTINCT ON (run_id)")&&s.contains("ORDER BY run_id,version DESC")));}}
 @Test void malformedPayloadAndHeaderDriftNeverBecomeEmptySuccess()throws Exception{try(var ignored=mockStatic(PostgresAuthorization.class)){corrupt=true;assertEquals(503,assertThrows(DomainException.class,this::read).status);corrupt=false;wrongHeader=true;assertEquals(503,assertThrows(DomainException.class,this::read).status);verify(connection,times(2)).rollback();verify(connection,never()).commit();}}
 @Test void missingAssetIs404BeforeGrantOrRunQuery(){missing=true;assertEquals(404,assertThrows(DomainException.class,this::read).status);verifyNoInteractions(auth);assertTrue(sql.stream().noneMatch(s->s.contains("mop_backup_run_event")));}
 @Test void missingIndependentGrantDoesNotFallBackToAssetVisibility()throws Exception{doThrow(new DomainException(403,"DENIED","Synthetic")).when(auth).require(any(),any(),any(),any(),eq(BackupTaskGrant.Operation.RUN_HISTORY));assertEquals(403,assertThrows(DomainException.class,this::read).status);assertTrue(sql.stream().noneMatch(s->s.contains("mop_backup_run_event")));verify(connection).rollback();}
 @Test void contradictoryFirstPageCannotHideMissingRows(){total=2;try(var ignored=mockStatic(PostgresAuthorization.class)){assertEquals(503,assertThrows(DomainException.class,this::read).status);}}
}
