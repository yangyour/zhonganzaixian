package cn.mop.domain.dashboard;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.domain.dashboard.ServiceObservationReader.*;
import static cn.mop.domain.dashboard.ServiceObservationIntervals.*;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.monitoring.MonitoringEventPipeline.Severity;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class ServiceObservationReaderTest {
    static final UUID TENANT=UUID.fromString("10000000-0000-4000-8000-000000000001"),ACTOR=UUID.fromString("20000000-0000-4000-8000-000000000001"),ASSET=UUID.fromString("30000000-0000-4000-8000-000000000001");
    static final Instant MONTH=Instant.parse("2026-09-30T16:00:00Z"),OBSERVED=Instant.parse("2026-10-01T16:00:00Z");
    static Definition definition(int i,BigDecimal target){return new Definition(new UUID(0,i),"服务"+i,List.of(ASSET),FailureMode.ANY_ASSET_UNAVAILABLE,MaintenanceMode.ANY_ASSET_APPROVED,target);}
    static Snapshot snapshot(Instant at,List<Definition> definitions,Evidence evidence){return ServiceObservationReader.evaluate(new Access(TENANT,ACTOR,1),at,"a".repeat(64),definitions,Map.of(ASSET,new AssetEvidence(new Asset(ASSET,"设备",1),evidence,Set.of(),Set.of())));}
    @Test void zeroFaultsNeverShowHundredPercent(){var service=snapshot(OBSERVED,List.of(definition(1,BigDecimal.valueOf(100))),new Evidence(List.of(),List.of(),List.of(),List.of())).services().get(0);assertNull(service.verifiedAvailabilityPct());assertEquals("UNKNOWN",service.healthState());assertEquals(86400000,service.unknownMillis());}
    @Test void unconfirmedTargetDoesNotBecomeDefault(){var service=snapshot(OBSERVED,List.of(definition(1,null)),new Evidence(List.of(),List.of(),List.of(),List.of(new Interval(MONTH,OBSERVED)))).services().get(0);assertEquals(0,service.verifiedAvailabilityPct().compareTo(BigDecimal.valueOf(100)));assertEquals("UNKNOWN",service.healthState());}
    @Test void lessThanTargetIsNotRoundedToHealthy(){var end=MONTH.plusMillis(8640);var service=snapshot(OBSERVED,List.of(definition(1,BigDecimal.valueOf(100))),new Evidence(List.of(new Fault(new Interval(MONTH,end),Severity.HIGH)),List.of(),List.of(),List.of(new Interval(end,OBSERVED)))).services().get(0);assertEquals(new BigDecimal("99.9900"),service.verifiedAvailabilityPct());assertEquals("NOT_MET",service.healthState());}
    @Test void exactMonthStartHasNoFakePercentage(){var service=snapshot(MONTH,List.of(definition(1,BigDecimal.valueOf(100))),new Evidence(List.of(),List.of(),List.of(),List.of())).services().get(0);assertNull(service.verifiedAvailabilityPct());assertEquals("EMPTY_MONTH_WINDOW",service.coverageStatus());assertEquals(0,service.unknownMillis());assertEquals(1,service.timeline().size());}
    @Test void onlyEightTimelinesButAllServicesRetained(){var definitions=java.util.stream.IntStream.rangeClosed(1,9).mapToObj(i->definition(i,null)).toList();var snapshot=snapshot(OBSERVED,definitions,new Evidence(List.of(),List.of(),List.of(),List.of()));assertEquals(9,snapshot.services().size());assertEquals(8,snapshot.services().stream().filter(Service::timelineIncluded).count());assertTrue(snapshot.services().get(8).timeline().isEmpty());}
}
