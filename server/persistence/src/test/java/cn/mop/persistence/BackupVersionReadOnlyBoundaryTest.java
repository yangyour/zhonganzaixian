package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class BackupVersionReadOnlyBoundaryTest {
 @Test void readOnlyRegistrationFailsBeforeValidationDatabaseOrExternalProof(){var calls=new AtomicInteger();DataSource source=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{calls.incrementAndGet();throw new AssertionError("No DB for registration");});var repo=PostgresBackupVersionRepository.readOnly(source,(c,a,s,o)->calls.incrementAndGet());var e=assertThrows(DomainException.class,()->repo.register(null,null,null,0,0,null,null,null,null));assertEquals(403,e.status);assertEquals("BACKUP_VERSION_REGISTRATION_DISABLED",e.code);assertEquals(0,calls.get());}
 @Test void independentEnumerationStillRequiredByReadOnlyAssembly(){var db=new BackupVersionInventoryBoundaryTest.EmptyDatabase();var repo=PostgresBackupVersionRepository.readOnly(db.source(),(c,a,s,o)->{assertEquals(Operation.READ,o);});var tenant=UUID.randomUUID();var access=new Access(tenant,UUID.randomUUID(),1);var scope=new InventoryScope(tenant,UUID.randomUUID());assertThrows(DomainException.class,()->repo.inventory(access,scope,null,15,null));var page=repo.inventory(access,scope,null,15,(c,a,s)->db.authorizations.incrementAndGet());assertTrue(page.items().isEmpty());assertEquals(2,db.authorizations.get());assertEquals(1,db.commits.get());}
 @Test void readOnlyAssemblyDoesNotAcceptAbsentPerVersionAuthority(){assertThrows(NullPointerException.class,()->PostgresBackupVersionRepository.readOnly(new BackupVersionInventoryBoundaryTest.EmptyDatabase().source(),null));}
}
