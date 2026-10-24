package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class DeliveryDeadLetterConfigurationTest {
 @Test void constructionNeverEnablesDispositionOrContactsDatabase(){var source=mock(DataSource.class);var platform=mock(PostgresPlatformRepository.class);var service=new DeliveryDeadLetterConfiguration().deliveryDeadLetterService(source,platform,new MockEnvironment());var a=new PostgresDeliveryReceiptRepository.Access(UUID.randomUUID(),UUID.randomUUID(),1);assertEquals(503,assertThrows(DomainException.class,()->service.dispose(a,UUID.randomUUID(),2,UUID.randomUUID(),"reason","unit")).status);verifyNoInteractions(source,platform);}
 @Test void fixtureRepositoryCannotBecomeWriteBackend(){assertThrows(IllegalStateException.class,()->new DeliveryDeadLetterConfiguration().deliveryDeadLetterService(mock(DataSource.class),mock(PlatformRepository.class),new MockEnvironment()));}
}
