package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.PostgresBackupArchiveRepository.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class BackupArchiveBoundaryTest {
 final UUID tenant=UUID.randomUUID();final Access access=new Access(tenant,UUID.randomUUID(),1);final Scope scope=new Scope(tenant,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID());
 final AtomicInteger connections=new AtomicInteger();
 DataSource unavailable(String sqlState){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{connections.incrementAndGet();throw new SQLException("password=must-not-leak",sqlState);});}
 PostgresBackupArchiveRepository repository(DataSource ds){return new PostgresBackupArchiveRepository(ds,(c,a,s,o)->{},(a,p)->{},(a,s,p,o,e)->{});}
 @Test void allSecurityDependenciesExplicit(){var ds=unavailable(null);assertThrows(NullPointerException.class,()->new PostgresBackupArchiveRepository(null,(c,a,s,o)->{},(a,p)->{},(a,s,p,o,e)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupArchiveRepository(ds,null,(a,p)->{},(a,s,p,o,e)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupArchiveRepository(ds,(c,a,s,o)->{},null,(a,s,p,o,e)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupArchiveRepository(ds,(c,a,s,o)->{},(a,p)->{},null));assertEquals(0,connections.get());}
 @Test void nullSqlStateCannotEscapeAsNullPointerOrLeakDriverDetails(){var e=assertThrows(DomainException.class,()->repository(unavailable(null)).current(access,scope));assertEquals(503,e.status);assertFalse(e.getMessage().contains("must-not-leak"));assertNull(e.getCause());}
 @Test void constraintFailuresMappedWithoutDriverDetails(){for(String state:new String[]{"23505","23514","23503","08006"}){var e=assertThrows(DomainException.class,()->repository(unavailable(state)).current(access,scope));assertEquals(state.equals("23503")?404:state.startsWith("235")?409:503,e.status);assertFalse(e.getMessage().contains("password"));}}
 @Test void foreignTenantRejectedBeforeConnection(){var e=assertThrows(DomainException.class,()->repository(unavailable(null)).current(new Access(UUID.randomUUID(),access.actorId(),1),scope));assertEquals(404,e.status);assertEquals(0,connections.get());}
 @Test void invalidCommandsNeverOpenDatabase(){var r=repository(unavailable(null));var d=new Dispatch(UUID.randomUUID(),"worker");assertThrows(DomainException.class,()->r.startStore(access,scope,UUID.randomUUID(),-1,d,"trace"));assertThrows(DomainException.class,()->r.startStore(access,scope,UUID.randomUUID(),0,null,"trace"));assertThrows(DomainException.class,()->r.markUnconfirmed(access,scope,null,0,d,"trace"));assertThrows(DomainException.class,()->r.confirmNoDispatch(access,scope,UUID.randomUUID(),0,d,"arbitrary","trace"));assertThrows(DomainException.class,()->r.verifyObject(access,scope,UUID.randomUUID(),0,"sha256:"+"a".repeat(64),"bad\ntrace"));assertThrows(DomainException.class,()->r.stage(access,UUID.randomUUID(),1,null,"trace"));assertEquals(0,connections.get());}
 @Test void accessAndDispatchIdentityBounds(){assertThrows(DomainException.class,()->new Access(tenant,UUID.randomUUID(),0));assertThrows(DomainException.class,()->new Access(tenant,UUID.randomUUID(),9007199254740992L));assertThrows(DomainException.class,()->new Dispatch(UUID.randomUUID(),"bad owner"));assertThrows(DomainException.class,()->new Dispatch(null,"worker"));}
}
