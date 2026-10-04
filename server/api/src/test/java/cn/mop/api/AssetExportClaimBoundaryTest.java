package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Isolated structural boundary checks; these doubles are not database integration evidence. */
class AssetExportClaimBoundaryTest {
    @Test void invalidWorkerOrLeaseDoesNotOpenAnyDatabaseConnection()throws Exception{
        DataSource source=mock(DataSource.class);var platform=new PostgresPlatformRepository(source,new ObjectMapper());var repository=new PostgresAssetExportRepository(source,platform);
        for(String worker:Arrays.asList(null,"","bad worker","a".repeat(65)))assertThrows(DomainException.class,()->repository.claimNext(worker,Duration.ofSeconds(1)));
        for(Duration lease:Arrays.asList(null,Duration.ZERO,Duration.ofNanos(1),Duration.ofMinutes(11)))assertThrows(DomainException.class,()->repository.claimNext("valid",lease));
        verifyNoInteractions(source);
    }
    @Test void exhaustedLogicalVersionRejectsBeforeMembershipAuditOrClaimMutation()throws Exception{
        DataSource source=mock(DataSource.class);Connection c=mock(Connection.class);PreparedStatement s=mock(PreparedStatement.class);ResultSet r=mock(ResultSet.class);when(source.getConnection()).thenReturn(c);when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),id=UUID.randomUUID();var command=new Command(new Access(tenant,actor,1,"msp",true),new Filters(null,null,null,null));
        var fields=Map.of("id",id.toString(),"tenant_id",tenant.toString(),"created_by",actor.toString(),"accepted_role","msp","trace_id","boundary","input_sha256",command.fingerprint());for(var entry:fields.entrySet())when(r.getString(entry.getKey())).thenReturn(entry.getValue());when(r.getBytes("command_bytes")).thenReturn(command.canonicalBytes());when(r.getLong("permission_version")).thenReturn(1L);when(r.getLong("version")).thenReturn(9007199254740991L);
        var repository=new PostgresAssetExportRepository(source,new PostgresPlatformRepository(source,new ObjectMapper()));var error=assertThrows(DomainException.class,()->repository.claim(id.toString(),"worker",Duration.ofSeconds(1)));assertEquals(409,error.status);assertEquals("VERSION_CONFLICT",error.code);verify(c).rollback();verify(c,never()).commit();verify(c,times(1)).prepareStatement(anyString());verify(c).close();
    }
}
