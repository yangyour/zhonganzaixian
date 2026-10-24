package cn.mop.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DeliveryJournalConfigurationTest {
 final String tenant="79000000-0000-4000-8000-000000000001",pool="79000000-0000-4000-8000-000000000002";
 @Test void missingScopeNeverImpliesAllPools(){assertTrue(DeliveryJournalConfiguration.readPools(null).apply(UUID.fromString(tenant)).isEmpty());}
 @Test void explicitScopeIsTenantExactAndImmutable(){var read=DeliveryJournalConfiguration.readPools("{\""+tenant+"\":[\""+pool+"\"]}");assertEquals(Set.of(UUID.fromString(pool)),read.apply(UUID.fromString(tenant)));assertTrue(read.apply(UUID.randomUUID()).isEmpty());assertThrows(UnsupportedOperationException.class,()->read.apply(UUID.fromString(tenant)).clear());}
 @Test void duplicateMalformedAndSecretBearingInputRejected(){for(String raw:List.of("{\""+tenant+"\":[],\""+tenant+"\":[]}","{\""+tenant+"\":[\""+pool+"\",\""+pool+"\"]}","{\""+tenant+"\":{\"token\":\"not allowed\"}}","{} {}"))assertThrows(IllegalStateException.class,()->DeliveryJournalConfiguration.readPools(raw));}
}
