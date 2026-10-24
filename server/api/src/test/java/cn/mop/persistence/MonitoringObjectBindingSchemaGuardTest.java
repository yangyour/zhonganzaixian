package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringObjectBindingSchemaGuardTest {
 @Test void structuralDriftFailsBeforeAnyPrivateRead()throws Exception{var platform=mock(PostgresPlatformRepository.class);var c=mock(Connection.class);doAnswer(call->((Function<Connection,?>)call.getArgument(0)).apply(c)).when(platform).identityTransaction(any());try(var verifier=mockStatic(MonitoringObjectBindingSchemaVerifier.class)){verifier.when(()->MonitoringObjectBindingSchemaVerifier.verify(c)).thenThrow(new SQLException("unit drift"));var repository=new PostgresMonitoringObjectBindingRepository(platform,new ObjectMapper(),tenant->null,mock(Source.class));assertEquals(503,assertThrows(DomainException.class,()->repository.get(new Access("79000000-0000-4000-8000-000000000001","79000000-0000-4000-8000-000000000002",1,"unit"),UUID.randomUUID())).status);verifyNoInteractions(c);}}
 @Test void candidatePinnedAndExplicitMigrationOnly()throws Exception{MonitoringObjectBindingSchemaVerifier.resource();assertEquals(TestSchemaMigration.Mode.APPLY_MONITORING_OBJECT_MAPPING,TestSchemaMigration.requestedMode(new String[]{"--apply-monitoring-object-mapping"}));assertEquals(TestSchemaMigration.Mode.READ_ONLY_CHECK,TestSchemaMigration.requestedMode(new String[]{}));String sql=new String(MonitoringObjectBindingSchemaVerifier.sql(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(sql.contains("Mapping optimistic version conflict"));assertTrue(sql.contains("generated".toUpperCase()));assertTrue(sql.contains("No implicit tenant transfer"));}
}
