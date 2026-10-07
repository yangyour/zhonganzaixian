package cn.mop.persistence;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
class RawMonitoringEventStorageSafetyTest {
    @Test void disconnectedDatabaseFailsClosedAndRedactsCause(){
        DataSource source=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{throw new SQLException("SECRET_CONNECTION_AND_PAYLOAD");});
        var event=new RawMonitoringEvent(new RawMonitoringEvent.Key(UUID.randomUUID(),"private-source",RawMonitoringEvent.Phase.PROBLEM),new RawMonitoringEvent.Binding(UUID.randomUUID(),UUID.randomUUID()),Instant.EPOCH,Instant.EPOCH,new byte[]{1});
        var e=assertThrows(IllegalStateException.class,()->new PostgresRawMonitoringEventRepository(source).store(event));assertNull(e.getCause());assertFalse(e.toString().contains("SECRET"));assertFalse(e.toString().contains("private-source"));
    }
    @Test void conflictReceiptCannotExposeForeignCanonicalIdentity(){assertThrows(IllegalArgumentException.class,()->new PostgresRawMonitoringEventRepository.Receipt(PostgresRawMonitoringEventRepository.Outcome.CONFLICT_REQUIRES_REVIEW,UUID.randomUUID(),Instant.EPOCH));}
    @Test void successfulReceiptRequiresDurableIdentity(){assertThrows(IllegalArgumentException.class,()->new PostgresRawMonitoringEventRepository.Receipt(PostgresRawMonitoringEventRepository.Outcome.STORED,null,null));}
    @Test void simultaneousOperationAndCloseFailuresCannotLeakSuppressedDetails(){
        var connection=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,a)->{throw new SQLException("SECRET_"+m.getName());});
        DataSource source=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->connection);
        var event=new RawMonitoringEvent(new RawMonitoringEvent.Key(UUID.randomUUID(),"private",RawMonitoringEvent.Phase.PROBLEM),new RawMonitoringEvent.Binding(UUID.randomUUID(),UUID.randomUUID()),Instant.EPOCH,Instant.EPOCH,new byte[]{1});
        var e=assertThrows(IllegalStateException.class,()->new PostgresRawMonitoringEventRepository(source).store(event));assertNull(e.getCause());assertEquals(0,e.getSuppressed().length);assertFalse(e.toString().contains("SECRET"));
    }

}
