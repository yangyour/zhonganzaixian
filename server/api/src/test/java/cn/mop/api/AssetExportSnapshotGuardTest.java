package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Isolated boundary doubles only; real PostgreSQL snapshot evidence is in persistence integration tests. */
class AssetExportSnapshotGuardTest {
    final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID();
    Command command(){return new Command(new AssetExportJobState.Access(tenant,actor,7,"msp",true),new Filters(null,null,null,null));}
    Current row(UUID tenant,UUID id){var a=new Asset(id.toString(),tenant.toString(),"asset","router","192.0.2.1","site-a","unknown","draft","manual",null,"NOT_OBSERVED",1,Instant.EPOCH,Instant.EPOCH,null,null);return new Current(new Snapshot(a,null,null),null,null,List.of());}
    @Test void selectionCopiesItsListAndRetainsNullMetadata(){var rows=new ArrayList<>(List.of(row(tenant,UUID.randomUUID())));var s=new AssetExportSnapshotRepository.Selection(command(),Instant.EPOCH,rows);rows.clear();assertEquals(1,s.rows().size());assertNull(s.rows().get(0).snapshot().metadata());assertThrows(UnsupportedOperationException.class,()->s.rows().clear());}
    @Test void foreignTenantAndDuplicateAssetAreRejected(){var r=row(tenant,UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->new AssetExportSnapshotRepository.Selection(command(),Instant.EPOCH,List.of(r,r)));assertEquals(404,assertThrows(DomainException.class,()->new AssetExportSnapshotRepository.Selection(command(),Instant.EPOCH,List.of(row(UUID.randomUUID(),UUID.randomUUID())))).status);}
    @Test void missingFactsOrOverflowCannotBeDeclaredComplete(){assertThrows(IllegalArgumentException.class,()->new AssetExportSnapshotRepository.Selection(null,Instant.EPOCH,List.of()));assertThrows(IllegalArgumentException.class,()->new AssetExportSnapshotRepository.Selection(command(),null,List.of()));assertThrows(IllegalArgumentException.class,()->new AssetExportSnapshotRepository.Selection(command(),Instant.EPOCH,null));assertThrows(IllegalArgumentException.class,()->new AssetExportSnapshotRepository.Selection(command(),Instant.EPOCH,Collections.nCopies(1001,row(tenant,UUID.randomUUID()))));}
    @Test void exactlyOneThousandRowsAreAcceptedWithoutDroppingRows(){var rows=new ArrayList<Current>();for(int i=0;i<1000;i++)rows.add(row(tenant,UUID.randomUUID()));assertEquals(rows,new AssetExportSnapshotRepository.Selection(command(),Instant.EPOCH,rows).rows());}
    @Test void thousandAndFirstIdAbortsBeforeFetchingAnyAssetDetails()throws Exception{runSqlBoundary(1001,false);}
    @Test void emptyResultCommitsAnExplicitEmptySnapshot()throws Exception{runSqlBoundary(0,false);}
    @Test void sqlFailureRollsBackAndDoesNotExposeDatabaseMessage()throws Exception{runSqlBoundary(0,true);}
    void runSqlBoundary(int count,boolean fail)throws Exception{
        DataSource source=mock(DataSource.class);Connection c=mock(Connection.class);when(source.getConnection()).thenReturn(c);
        var sqls=new ArrayList<String>();
        when(c.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);sqls.add(sql);if(fail)throw new SQLException("SYNTHETIC_PRIVATE_DATABASE_TEXT","08006");PreparedStatement s=mock(PreparedStatement.class);ResultSet r=mock(ResultSet.class);when(s.executeQuery()).thenReturn(r);
            if(sql.startsWith("SELECT m.role_code")){when(r.next()).thenReturn(true,false);when(r.getLong(2)).thenReturn(7L);when(r.getString(1)).thenReturn("msp");}
            else if(sql.startsWith("SELECT role_code")){when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("msp");}
            else if(sql.startsWith("SELECT transaction_timestamp")){when(r.next()).thenReturn(true,false);when(r.getObject(1,OffsetDateTime.class)).thenReturn(OffsetDateTime.ofInstant(Instant.EPOCH,ZoneOffset.UTC));}
            else if(sql.startsWith("SELECT a.id::text")){var n=new java.util.concurrent.atomic.AtomicInteger();when(r.next()).thenAnswer(x->n.getAndIncrement()<count);when(r.getString(1)).thenAnswer(x->new UUID(0,n.get()).toString());}
            else throw new AssertionError("Unexpected detail read");return s;
        });
        var platform=new PostgresPlatformRepository(source,new ObjectMapper());var repository=new PostgresManualAssetMetadataRepository(source,platform,new ObjectMapper());
        if(fail||count>1000){var error=assertThrows(DomainException.class,()->repository.select(command()));assertEquals(fail?503:413,error.status);assertFalse(error.getMessage().contains("SYNTHETIC_PRIVATE"));verify(c).rollback();verify(c,never()).commit();}
        else{assertTrue(repository.select(command()).rows().isEmpty());verify(c).commit();verify(c,never()).rollback();}
        verify(source,times(1)).getConnection();verify(c).setReadOnly(true);verify(c).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);verify(c).setAutoCommit(false);verify(c).close();assertTrue(sqls.stream().noneMatch(s->s.startsWith("SELECT * FROM")));
    }
}
