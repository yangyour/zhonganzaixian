package cn.mop.persistence;
import cn.mop.domain.DomainException;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;import java.time.*;import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BackupDeviceLeaseRepositoryBoundaryTest {
 final UUID tenant=UUID.randomUUID(),asset=UUID.randomUUID(),request=UUID.randomUUID();
 final DataSource source=mock(DataSource.class);
 PostgresBackupDeviceLeaseRepository repo(){return new PostgresBackupDeviceLeaseRepository(source,(c,t,a,r)->{},(c,s,ref)->{});}
 @Test void requiredAdaptersCannotDefaultToAllow(){assertThrows(NullPointerException.class,()->new PostgresBackupDeviceLeaseRepository(null,(c,t,a,r)->{},(c,s,ref)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupDeviceLeaseRepository(source,null,(c,s,ref)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupDeviceLeaseRepository(source,(c,t,a,r)->{},null));verifyNoInteractions(source);}
 @Test void invalidAcquireIsRejectedBeforeConnection(){for(Duration duration:new Duration[]{null,Duration.ZERO,Duration.ofNanos(1),Duration.ofMinutes(6)})assertThrows(DomainException.class,()->repo().acquire(tenant,asset,request,"worker",duration,"trace"));assertThrows(DomainException.class,()->repo().acquire(tenant,asset,request,"bad\n",Duration.ofSeconds(30),"trace"));assertThrows(DomainException.class,()->repo().acquire(tenant,asset,request,"worker",Duration.ofSeconds(30),""));verifyNoInteractions(source);}
 @Test void invalidRenewDoesNotAccessDatabase(){assertThrows(DomainException.class,()->repo().renew(null,asset,request,"worker",1,1,"trace"));assertThrows(DomainException.class,()->repo().renew(tenant,asset,request,"bad owner",1,1,"trace"));verifyNoInteractions(source);}
 @Test void releaseRequiresStrictReferenceBeforeConnection(){for(String ref:new String[]{null,"","sha256:"+"A".repeat(64),"sha256:"+"a".repeat(64)+"\n"})assertThrows(DomainException.class,()->repo().release(tenant,asset,request,"worker",1,1,ref,"trace"));verifyNoInteractions(source);}
 @Test void invalidReadDoesNotAccessDatabase(){assertThrows(DomainException.class,()->repo().current(tenant,asset,null));verifyNoInteractions(source);}
 @Test void connectionFailureIsSafeAndDoesNotExposeSqlOrCredentials()throws Exception{when(source.getConnection()).thenThrow(new SQLException("password=never-expose SQL private", "08006"));var e=assertThrows(DomainException.class,()->repo().current(tenant,asset,request));assertEquals(503,e.status);assertFalse(e.getMessage().contains("never-expose"));assertNull(e.getCause());}
 @Test void authorizationDenialRollsBackAndNeverQueriesJournal()throws Exception{var c=mock(Connection.class);var statement=mock(Statement.class);when(source.getConnection()).thenReturn(c);when(c.createStatement()).thenReturn(statement);var denied=new PostgresBackupDeviceLeaseRepository(source,(conn,t,a,r)->{assertSame(c,conn);throw new DomainException(403,"DENIED","denied");},(conn,s,ref)->fail("must not verify stop"));assertEquals(403,assertThrows(DomainException.class,()->denied.acquire(tenant,asset,request,"worker",Duration.ofSeconds(30),"trace")).status);verify(c).rollback();verify(c,never()).prepareStatement(anyString());verify(c,never()).commit();verify(c).close();}
}
