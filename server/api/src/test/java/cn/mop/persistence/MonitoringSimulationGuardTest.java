package cn.mop.persistence;
import cn.mop.domain.DomainException;
import java.sql.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringSimulationGuardTest {
 final String tenant="79000000-0000-4000-8000-000000000002";
 @Test void protectedOrUnknownTenantDeniedBeforeAnyDatabaseRead()throws Exception{for(String value:new String[]{null,"", "55000000-0000-4000-8000-000000000001","55000000-0000-4000-8000-000000000002"}){var c=mock(Connection.class);assertEquals(422,assertThrows(DomainException.class,()->MonitoringSimulationGuard.requireIsolated(c,value)).status);verifyNoInteractions(c);}}
 @Test void actualDatabaseNameAndBothRuntimeIdentitiesAreRequired()throws Exception{for(String[] values:new String[][]{{"mop_test","mop_test_runtime","mop_test_runtime"},{"mop_monitoring_test_000000000001","mop_test_migrator","mop_test_runtime"},{"mop_monitoring_test_000000000001","mop_test_runtime","postgres"},{"mop_monitoring_test_000000000001_extra","mop_test_runtime","mop_test_runtime"}}){var c=connection(values);assertEquals(422,assertThrows(DomainException.class,()->MonitoringSimulationGuard.requireIsolated(c,tenant)).status);}}
 @Test void explicitlyIsolatedInternalTestCanExerciseFaultPlan()throws Exception{var c=connection(new String[]{"mop_monitoring_test_000000000001","mop_test_runtime","mop_test_runtime"});assertDoesNotThrow(()->MonitoringSimulationGuard.requireIsolated(c,tenant));}
 @Test void testHarnessRejectsActualDbAndSecretBearingOrNonLoopbackUrls(){for(String url:new String[]{null,"jdbc:postgresql://127.0.0.1:15432/mop_test","jdbc:postgresql://localhost:15432/mop_monitoring_test_000000000001","jdbc:postgresql://127.0.0.1:15432/mop_monitoring_test_000000000001?password=unit","jdbc:postgresql://127.0.0.1:0/mop_monitoring_test_000000000001","jdbc:postgresql://127.0.0.1:65536/mop_monitoring_test_000000000001"})assertThrows(DomainException.class,()->MonitoringSimulationGuard.requireTestConfiguration(url,tenant));}
 @Test void missingNamespaceNeverFallsBackToHistoricalTenant(){assertThrows(DomainException.class,()->MonitoringSimulationGuard.requireTestConfiguration("jdbc:postgresql://127.0.0.1:15432/mop_monitoring_test_000000000001",null));assertDoesNotThrow(()->MonitoringSimulationGuard.requireTestConfiguration("jdbc:postgresql://127.0.0.1:15432/mop_monitoring_test_000000000001",tenant));}
 Connection connection(String[] values)throws Exception{var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(c.prepareStatement("SELECT current_database(),current_user,session_user")).thenReturn(s);when(s.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);for(int i=0;i<3;i++)when(r.getString(i+1)).thenReturn(values[i]);return c;}
}
