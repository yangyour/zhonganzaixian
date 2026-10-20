package cn.mop.persistence;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PostgresTicketAutoCloseBoundaryTest {
    @Test void invalidWorkerSettingsDoNotOpenConnection(){
        DataSource ds=mock(DataSource.class);var p=new PostgresPlatformRepository(ds,new ObjectMapper());var r=new PostgresTicketAutoCloseRepository(ds,p,new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->r.claim("",30));assertThrows(IllegalArgumentException.class,()->r.claim("worker",0));verifyNoInteractions(ds);
    }
    @Test void databaseFailureIsNotEmptySuccessfulQueue()throws Exception{
        DataSource ds=mock(DataSource.class);when(ds.getConnection()).thenThrow(new SQLException("synthetic unavailable"));var r=new PostgresTicketAutoCloseRepository(ds,new PostgresPlatformRepository(ds,new ObjectMapper()),new ObjectMapper());
        assertEquals(503,assertThrows(DomainException.class,()->r.claim("test-worker",30)).status);assertFalse(r.ready());
    }
    @Test void claimUsesDedicatedTransactionAndSkipLockedFencingUpdate()throws Exception{
        var p=mock(PostgresPlatformRepository.class);Connection c=mock(Connection.class);PreparedStatement s=mock(PreparedStatement.class);ResultSet rs=mock(ResultSet.class);
        when(p.ticketJobTransaction(any())).thenAnswer(i->i.<Supplier<?>>getArgument(0).get());when(p.ticketJobConnection()).thenReturn(c);when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(rs);when(rs.next()).thenReturn(false);
        var r=new PostgresTicketAutoCloseRepository(mock(DataSource.class),p,new ObjectMapper());assertTrue(r.claim("test-worker",30).isEmpty());
        verify(c).prepareStatement(argThat(sql->sql.contains("FOR UPDATE SKIP LOCKED")&&sql.contains("fencing_token=j.fencing_token+1")&&sql.contains("lease_until<=clock_timestamp()")));
        verify(s).setString(1,"test-worker");verify(s).setInt(2,30);verify(p,never()).idempotentCommandConnection();
    }
    @Test void missingLeaseCannotWriteLifecycleOrAudit()throws Exception{
        var p=mock(PostgresPlatformRepository.class);Connection c=mock(Connection.class);PreparedStatement s=mock(PreparedStatement.class);ResultSet rs=mock(ResultSet.class);
        when(p.ticketJobTransaction(any())).thenAnswer(i->i.<Supplier<?>>getArgument(0).get());when(p.ticketJobConnection()).thenReturn(c);when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(rs);when(rs.next()).thenReturn(false);
        var r=new PostgresTicketAutoCloseRepository(mock(DataSource.class),p,new ObjectMapper());assertFalse(r.complete(new PostgresTicketAutoCloseRepository.Claim("10000000-0000-0000-0000-000000000001","10000000-0000-0000-0000-000000000002",2,"a".repeat(64),"worker",1,1),3));
        verify(c,never()).prepareStatement(startsWith("INSERT"));verify(p,never()).auditTicketWorker(any(),any(),any(),any(),any());
    }
    @Test void staleStatusOwnerTokenOrAttemptCannotWrite()throws Exception{
        for(int mismatch=0;mismatch<4;mismatch++){
            var p=mock(PostgresPlatformRepository.class);Connection c=mock(Connection.class);PreparedStatement s=mock(PreparedStatement.class);ResultSet rs=mock(ResultSet.class);
            when(p.ticketJobTransaction(any())).thenAnswer(i->i.<Supplier<?>>getArgument(0).get());when(p.ticketJobConnection()).thenReturn(c);when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(rs);when(rs.next()).thenReturn(true);
            when(rs.getString("status")).thenReturn(mismatch==0?"PENDING":"LEASED");when(rs.getString("lease_owner")).thenReturn(mismatch==1?"other":"worker");when(rs.getLong("fencing_token")).thenReturn(mismatch==2?2L:1L);when(rs.getInt("attempts")).thenReturn(mismatch==3?2:1);
            var r=new PostgresTicketAutoCloseRepository(mock(DataSource.class),p,new ObjectMapper());assertFalse(r.complete(new PostgresTicketAutoCloseRepository.Claim("10000000-0000-0000-0000-000000000001","10000000-0000-0000-0000-000000000002",2,"a".repeat(64),"worker",1,1),3));
            verify(c,never()).prepareStatement(startsWith("INSERT"));verify(p,never()).auditTicketWorker(any(),any(),any(),any(),any());
        }
    }
    @Test void claimIdentityAndFencingCannotBeOmitted(){assertThrows(IllegalArgumentException.class,()->new PostgresTicketAutoCloseRepository.Claim("bad","bad",0,"bad","",0,0));}
}
