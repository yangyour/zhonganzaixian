package cn.mop.worker.backup;
import com.zaxxer.hikari.HikariDataSource;
import cn.mop.persistence.RuntimeDataSources;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** No database connection or external IO: verifies mandatory guard configuration and fail-before-factory behavior. */
class BackupWorkerDatabaseGuardTest {
 private BackupWorkerSettings settings(String url,String password){return new BackupWorkerSettings(url,password,Path.of("/synthetic/grants"),Path.of("/synthetic/proofs"),Path.of("/synthetic/key"),"backup-collector:unit",BackupTaskSchedule.disabled());}
 @Test void productionOverloadMustInvokeTheSharedPrivilegeCheckingFactory(){
  try(var guard=mockStatic(RuntimeDataSources.class);var expected=new HikariDataSource()){
   guard.when(()->RuntimeDataSources.openRestrictedTest(any())).thenReturn(expected);
   assertSame(expected,BackupWorkerMain.openDataSource(settings("jdbc:postgresql://127.0.0.1:19214/mop_test","synthetic-only")));
   guard.verify(()->RuntimeDataSources.openRestrictedTest(any()));
  }
 }
 @Test void unconfiguredDefaultEntryFailsInTheSharedGuardBeforeOpeningPool(){
  var error=assertThrows(IllegalArgumentException.class,()->BackupWorkerMain.openDataSource(settings("unit-only-not-a-jdbc-url","synthetic")));
  assertTrue(error.getMessage().contains("restricted loopback test runtime"));
 }
 @Test void invalidConfigurationCannotReachAnInjectedFactory(){
  for(var value:new BackupWorkerSettings[]{settings("unit-only-not-a-jdbc-url","synthetic"),settings("jdbc:postgresql://127.0.0.1:0/mop_test","synthetic"),settings("jdbc:postgresql://127.0.0.1:5432/mop_test","")}){
   var called=new AtomicBoolean();assertThrows(IllegalArgumentException.class,()->BackupWorkerMain.openDataSource(value,c->{called.set(true);throw new AssertionError("Factory must not run");}));assertFalse(called.get());
  }
 }
 @Test void guardedFactoryReceivesOnlyRestrictedIdentityAndExistingBoundedSettings(){
  var called=new AtomicBoolean();try(var source=BackupWorkerMain.openDataSource(settings("jdbc:postgresql://127.0.0.1:19214/mop_test","synthetic-only"),c->{
   called.set(true);assertEquals("mop_test_runtime",c.getUsername());assertEquals("jdbc:postgresql://127.0.0.1:19214/mop_test",c.getJdbcUrl());assertEquals(2,c.getMaximumPoolSize());assertEquals(0,c.getMinimumIdle());assertEquals(5000,c.getConnectionTimeout());assertEquals(3000,c.getValidationTimeout());assertEquals("5",c.getDataSourceProperties().getProperty("connectTimeout"));assertEquals("20",c.getDataSourceProperties().getProperty("socketTimeout"));return new HikariDataSource();
  })){assertNotNull(source);assertTrue(called.get());}
 }
 @Test void privilegeGuardFailureIsNotReplacedWithAnUnguardedPool(){
  var expected=new IllegalStateException("synthetic restricted role rejection");assertSame(expected,assertThrows(IllegalStateException.class,()->BackupWorkerMain.openDataSource(settings("jdbc:postgresql://127.0.0.1:19214/mop_test","synthetic-only"),c->{throw expected;})));
 }
}
