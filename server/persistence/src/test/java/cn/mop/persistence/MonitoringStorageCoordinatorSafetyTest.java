package cn.mop.persistence;
import cn.mop.domain.monitoring.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringStorageCoordinatorSafetyTest {
    @Test void rawFailureIsNotConvertedToQuarantineOrSuccess(){
        var calls=new AtomicInteger();DataSource source=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{calls.incrementAndGet();throw new SQLException("PRIVATE_CONNECTION");});
        var event=new RawMonitoringEvent(new RawMonitoringEvent.Key(UUID.randomUUID(),"PRIVATE_SOURCE",RawMonitoringEvent.Phase.PROBLEM),new RawMonitoringEvent.Binding(UUID.randomUUID(),UUID.randomUUID()),Instant.EPOCH,Instant.EPOCH,new byte[]{1});
        var failure=assertThrows(IllegalStateException.class,()->new PostgresMonitoringEventStorageCoordinator(source).store(event));assertEquals(1,calls.get());assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains("PRIVATE"));
    }
    @Test void conflictCannotBePresentedAsRawSuccess(){assertThrows(IllegalArgumentException.class,()->new PostgresMonitoringEventStorageCoordinator.RawResult(new PostgresRawMonitoringEventRepository.Receipt(PostgresRawMonitoringEventRepository.Outcome.CONFLICT_REQUIRES_REVIEW,null,null)));}
    @Test void heldReceiptIsUnverifiedNonDispatchingAndRedacted(){var id=UUID.randomUUID();var result=new PostgresMonitoringEventStorageCoordinator.HeldResult(new PostgresMonitoringQuarantineRepository.Receipt(PostgresMonitoringQuarantineRepository.Outcome.HELD,id,Instant.EPOCH));assertFalse(result.sourceAuthorized());assertFalse(result.dispatchAllowed());assertFalse(result.toString().contains(id.toString()));assertFalse(result.toString().contains(Instant.EPOCH.toString()));}
    @Test void rawReceiptIsAlsoNotAnAuthenticationOrProcessingDecision(){var id=UUID.randomUUID();var result=new PostgresMonitoringEventStorageCoordinator.RawResult(new PostgresRawMonitoringEventRepository.Receipt(PostgresRawMonitoringEventRepository.Outcome.STORED,id,Instant.EPOCH));assertFalse(result.sourceAuthorized());assertFalse(result.dispatchAllowed());assertFalse(result.toString().contains(id.toString()));}
}
