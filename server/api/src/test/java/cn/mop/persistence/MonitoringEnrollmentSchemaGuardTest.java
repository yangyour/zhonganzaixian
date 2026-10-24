package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEnrollmentRepository.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringEnrollmentSchemaGuardTest {
 @Test void driftFailsClosedBeforeReadingPrivateTasks()throws Exception{var platform=mock(PostgresPlatformRepository.class);var connection=mock(Connection.class);doAnswer(c->((Function<Connection,?>)c.getArgument(0)).apply(connection)).when(platform).identityTransaction(any());try(var verifier=mockStatic(MonitoringEnrollmentSchemaVerifier.class)){
  verifier.when(()->MonitoringEnrollmentSchemaVerifier.verify(connection)).thenThrow(new SQLException("synthetic drift"));var repository=new PostgresMonitoringEnrollmentRepository(platform,new ObjectMapper(),tenant->null,()->"a".repeat(64));
  assertEquals(503,assertThrows(DomainException.class,()->repository.list(new Access("79000000-0000-4000-8000-000000000001","79000000-0000-4000-8000-000000000002",1,"unit"),20,null,null)).status);verifyNoInteractions(connection);
 }}
 @Test void candidateBytesArePinnedWithoutApplyingDDL()throws Exception{MonitoringEnrollmentSchemaVerifier.resource();assertTrue(new String(MonitoringEnrollmentSchemaVerifier.sql(),java.nio.charset.StandardCharsets.UTF_8).contains("Registration cannot activate a target"));}
}
