package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class BackupVersionBoundaryTest {
 final UUID tenant=UUID.randomUUID();final Access access=new Access(tenant,UUID.randomUUID(),1);final Scope scope=new Scope(tenant,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID());final AtomicInteger connections=new AtomicInteger();
 final PostgresBackupRunRepository.LeaseClaim lease=new PostgresBackupRunRepository.LeaseClaim(UUID.randomUUID(),"worker",1);final String proof="sha256:"+"a".repeat(64);
 DataSource source(String state){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{connections.incrementAndGet();throw new SQLException("secret-driver-detail",state);});}
 PostgresBackupVersionRepository repo(DataSource s){return new PostgresBackupVersionRepository(s,(c,a,p,o)->{},(a,p,r)->{},(c,a,p,r,l,e)->{});}
 @Test void allProofAndAuthorizationDependenciesMandatory(){var s=source(null);assertThrows(NullPointerException.class,()->new PostgresBackupVersionRepository(null,(c,a,p,o)->{},(a,p,r)->{},(c,a,p,r,l,e)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupVersionRepository(s,null,(a,p,r)->{},(c,a,p,r,l,e)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupVersionRepository(s,(c,a,p,o)->{},null,(c,a,p,r,l,e)->{}));assertThrows(NullPointerException.class,()->new PostgresBackupVersionRepository(s,(c,a,p,o)->{},(a,p,r)->{},null));assertEquals(0,connections.get());}
 @Test void wrongTenantRejectedBeforeDatabase(){assertEquals(404,assertThrows(DomainException.class,()->repo(source(null)).current(new Access(UUID.randomUUID(),access.actorId(),1),scope)).status);assertEquals(0,connections.get());}
 @Test void invalidExpectedVersionsRejectedWithoutConnection(){var r=repo(source(null));for(long version:new long[]{0,-1,9007199254740991L,Long.MAX_VALUE})assertThrows(DomainException.class,()->r.register(access,scope,UUID.randomUUID(),version,1,lease,proof,proof,"trace"));for(long version:new long[]{0,-1,9007199254740992L})assertThrows(DomainException.class,()->r.register(access,scope,UUID.randomUUID(),1,version,lease,proof,proof,"trace"));assertEquals(0,connections.get());}
 @Test void identityEvidenceAndLeaseRequiredBeforeConnection(){var r=repo(source(null));assertThrows(DomainException.class,()->r.register(access,scope,null,1,1,lease,proof,proof,"trace"));assertThrows(DomainException.class,()->r.register(access,scope,UUID.randomUUID(),1,1,null,proof,proof,"trace"));assertThrows(DomainException.class,()->r.register(access,scope,UUID.randomUUID(),1,1,lease,"hash-only",proof,"trace"));assertThrows(DomainException.class,()->r.register(access,scope,UUID.randomUUID(),1,1,lease,proof,proof+"\n","trace"));assertThrows(DomainException.class,()->r.register(access,scope,UUID.randomUUID(),1,1,lease,proof,proof,"bad trace"));assertEquals(0,connections.get());}
 @Test void nullSqlStateIsSanitizedAsUnconfirmedNotAssumedRolledBack(){var e=assertThrows(DomainException.class,()->repo(source(null)).current(access,scope));assertEquals(503,e.status);assertFalse(e.getMessage().contains("secret-driver-detail"));assertNull(e.getCause());}
 @Test void constraintAndTransportErrorsNeverExposeDriverDetail(){for(String state:new String[]{"23505","23514","23503","08006"}){var e=assertThrows(DomainException.class,()->repo(source(state)).current(access,scope));assertEquals(state.equals("23503")?404:state.startsWith("235")?409:503,e.status);assertFalse(e.getMessage().contains("secret-driver-detail"));}}
 @Test void accessVersionsAreBounded(){assertThrows(DomainException.class,()->new Access(tenant,UUID.randomUUID(),0));assertThrows(DomainException.class,()->new Access(tenant,UUID.randomUUID(),9007199254740992L));assertThrows(DomainException.class,()->new Access(null,UUID.randomUUID(),1));}
}
