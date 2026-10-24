package cn.mop.domain.dashboard;
import cn.mop.domain.dashboard.AssetReachability.*;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AssetReachabilityTest {
 static final UUID T=UUID.randomUUID(),A=UUID.randomUUID(),D=UUID.randomUUID(),E=UUID.randomUUID();static final Instant NOW=Instant.parse("2026-10-24T00:00:00Z");
 static Inventory inventory(){return new Inventory(new Access(T,A,1),NOW,List.of(new Asset(D,"设备1","10.0.0.1",1),new Asset(E,"设备2","10.0.0.2",1)));}
 @Test void missingBindingIsUnknownNotHealthy(){var r=AssetReachability.evaluate(inventory(),Map.of(D,new Sample("FRESH","1",NOW)),true);assertEquals(1,r.normal());assertEquals(1,r.unknown());assertNull(r.percentage());}
 @Test void completeFreshPopulationHasExactDenominator(){var r=AssetReachability.evaluate(inventory(),Map.of(D,new Sample("FRESH","1",NOW),E,new Sample("FRESH","0",NOW)),true);assertEquals("50.0000",r.percentage().toPlainString());assertEquals(1,r.noResponse());assertEquals(0,r.unknown());}
 @Test void sixtySecondBoundaryBecomesUnknown(){var r=AssetReachability.evaluate(inventory(),Map.of(D,new Sample("FRESH","1",NOW.minusSeconds(60)),E,new Sample("FRESH","0",NOW.minusSeconds(59))),true);assertEquals(1,r.noResponse());assertEquals(1,r.unknown());assertEquals("STALE",r.items().get(0).reason());}
 @Test void collectorErrorDisabledAndClockSkewStayUnknown(){for(var state:List.of("NO_DATA","STALE","COLLECTOR_ERROR","DISABLED","CLOCK_SKEW")){var r=AssetReachability.evaluate(inventory(),Map.of(D,new Sample(state,null,NOW)),true);assertEquals(2,r.unknown());assertNull(r.percentage());}assertEquals("CLOCK_SKEW",AssetReachability.evaluate(inventory(),Map.of(D,new Sample("FRESH","1",NOW.plusSeconds(1))),true).items().get(0).reason());}
 @Test void unconfiguredSourceKeepsCompleteUnknownLedger(){var r=AssetReachability.evaluate(inventory(),Map.of(),false);assertEquals(2,r.unknown());assertEquals("SOURCE_NOT_CONFIGURED",r.items().get(0).reason());}
 @Test void noAssetsIsEmptyNotOneHundredPercent(){var r=AssetReachability.evaluate(new Inventory(new Access(T,A,1),NOW,List.of()),Map.of(),false);assertEquals(0,r.total());assertNull(r.percentage());}
 @Test void foreignOrDuplicateAssetsAndInvalidSamplesFail(){assertThrows(RuntimeException.class,()->AssetReachability.evaluate(inventory(),Map.of(UUID.randomUUID(),new Sample("FRESH","1",NOW)),true));assertThrows(RuntimeException.class,()->new Inventory(new Access(T,A,1),NOW,List.of(inventory().assets().get(0),inventory().assets().get(0))));assertThrows(RuntimeException.class,()->new Sample("FRESH",null,NOW));}
}
