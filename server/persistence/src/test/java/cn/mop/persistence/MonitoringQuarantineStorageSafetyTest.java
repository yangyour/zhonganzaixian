package cn.mop.persistence;
import cn.mop.domain.monitoring.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringQuarantineStorageSafetyTest {
    QuarantinedMonitoringEvent event(){return new QuarantinedMonitoringEvent(new RawMonitoringEvent.Key(UUID.randomUUID(),"PRIVATE_SOURCE",RawMonitoringEvent.Phase.PROBLEM),new QuarantinedMonitoringEvent.Claims(null,null),QuarantinedMonitoringEvent.Reason.UNKNOWN_MAPPING,Instant.EPOCH,Instant.EPOCH,new byte[]{1});}
    @Test void disconnectedSourceNeverFallsBack(){DataSource ds=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{throw new SQLException("PRIVATE_CONNECTION");});var e=assertThrows(IllegalStateException.class,()->new PostgresMonitoringQuarantineRepository(ds).hold(event()));assertNull(e.getCause());assertEquals(0,e.getSuppressed().length);assertFalse(e.toString().contains("PRIVATE"));}
    @Test void closeFailureCannotLeakSuppressedDetails(){Connection c=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{throw new SQLException("PRIVATE_OPERATION_OR_CLOSE");});DataSource ds=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->c);var e=assertThrows(IllegalStateException.class,()->new PostgresMonitoringQuarantineRepository(ds).hold(event()));assertNull(e.getCause());assertEquals(0,e.getSuppressed().length);assertFalse(e.toString().contains("PRIVATE"));}
    @Test void receiptsRequireIdentityAndRemainNonAuthorizing(){assertThrows(NullPointerException.class,()->new PostgresMonitoringQuarantineRepository.Receipt(PostgresMonitoringQuarantineRepository.Outcome.HELD,null,Instant.EPOCH));assertTrue(new PostgresMonitoringQuarantineRepository.Receipt(PostgresMonitoringQuarantineRepository.Outcome.HELD,UUID.randomUUID(),Instant.EPOCH).toString().contains("dispatch forbidden"));}
}
