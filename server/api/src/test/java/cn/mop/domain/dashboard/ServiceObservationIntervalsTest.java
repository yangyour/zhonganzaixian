package cn.mop.domain.dashboard;

import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.domain.dashboard.ServiceObservationIntervals.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.Severity;
import cn.mop.domain.time.IntervalSets.Interval;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceObservationIntervalsTest {
    static final Instant START=Instant.parse("2026-10-24T00:00:00Z");
    static Interval interval(long start,long end){return new Interval(START.plusSeconds(start),START.plusSeconds(end));}
    static Evidence evidence(List<Fault> faults,List<Interval> unknown,List<Interval> maintenance,List<Interval> healthy){
        return new Evidence(faults,unknown,maintenance,healthy);
    }
    static Result evaluate(List<Evidence> assets,FailureMode mode){
        return ServiceObservationIntervals.evaluate(assets,mode,MaintenanceMode.ANY_ASSET_APPROVED,interval(0,100));
    }
    @Test void absenceOfFaultsIsEntirelyUnknown(){
        var assets=List.of(evidence(List.of(),List.of(),List.of(),List.of()));
        var result=evaluate(assets,FailureMode.ANY_ASSET_UNAVAILABLE);
        assertEquals(List.of(interval(0,100)),result.unknown());assertTrue(result.healthy().isEmpty());
        assertEquals(Kind.UNKNOWN,timeline(result,assets,interval(0,100)).get(0).kind());
    }
    @Test void redundantServiceUsesIntersectionForFailureAndUnionForPositiveHealth(){
        var assets=List.of(evidence(List.of(new Fault(interval(10,50),Severity.HIGH)),List.of(),List.of(),List.of(interval(50,100))),
            evidence(List.of(new Fault(interval(30,70),Severity.DISASTER)),List.of(),List.of(),List.of(interval(0,30))));
        var any=evaluate(assets,FailureMode.ANY_ASSET_UNAVAILABLE);
        assertEquals(List.of(interval(10,70)),any.unavailable());assertTrue(any.healthy().isEmpty());
        var all=evaluate(assets,FailureMode.ALL_ASSETS_UNAVAILABLE);
        assertEquals(List.of(interval(30,50)),all.unavailable());assertEquals(List.of(interval(0,30),interval(50,100)),all.healthy());
        assertTrue(all.unknown().isEmpty());
    }
    @Test void unknownAndContradictionOverrideAnAssetsFaultAndHealth(){
        var assets=List.of(evidence(List.of(new Fault(interval(10,80),Severity.HIGH)),List.of(interval(20,30)),List.of(),List.of(interval(60,100))));
        var result=evaluate(assets,FailureMode.ANY_ASSET_UNAVAILABLE);
        assertEquals(List.of(interval(10,20),interval(30,60)),result.unavailable());
        assertEquals(List.of(interval(80,100)),result.healthy());
        assertEquals(List.of(interval(0,10),interval(20,30),interval(60,80)),result.unknown());
    }
    @Test void timelineUsesHighestSeverityAndMaintenancePrecedence(){
        var assets=List.of(evidence(List.of(new Fault(interval(10,70),Severity.WARNING),new Fault(interval(30,50),Severity.DISASTER)),List.of(),List.of(interval(40,60)),List.of()));
        var result=evaluate(assets,FailureMode.ANY_ASSET_UNAVAILABLE);
        var spans=timeline(result,assets,interval(0,100));
        assertEquals(List.of(Kind.UNKNOWN,Kind.FAULT,Kind.FAULT,Kind.MAINTENANCE,Kind.FAULT,Kind.UNKNOWN),spans.stream().map(Span::kind).toList());
        assertEquals(Severity.WARNING,spans.get(1).severity());assertEquals(Severity.DISASTER,spans.get(2).severity());
        assertEquals(START.plusSeconds(40),spans.get(2).end());
    }
    @Test void allAssetMaintenanceRequiresOverlap(){
        var assets=List.of(evidence(List.of(),List.of(),List.of(interval(10,50)),List.of()),evidence(List.of(),List.of(),List.of(interval(30,70)),List.of()));
        assertEquals(List.of(interval(30,50)),ServiceObservationIntervals.evaluate(assets,FailureMode.ALL_ASSETS_UNAVAILABLE,MaintenanceMode.ALL_ASSETS_APPROVED,interval(0,100)).maintenance());
    }
    @Test void recoveryNanosecondsAreNotRoundedIntoHealthyTime(){
        var end=START.plusSeconds(20).plusNanos(7);
        var assets=List.of(evidence(List.of(new Fault(new Interval(START,end),Severity.HIGH)),List.of(),List.of(),List.of()));
        var spans=timeline(evaluate(assets,FailureMode.ANY_ASSET_UNAVAILABLE),assets,interval(0,100));
        assertEquals(end,spans.get(0).end()); assertEquals(end,spans.get(1).start()); assertEquals(Kind.UNKNOWN,spans.get(1).kind());
    }
    @Test void missingMappingAndImplicitPoliciesAreRejected(){
        assertThrows(RuntimeException.class,()->evaluate(List.of(),FailureMode.ANY_ASSET_UNAVAILABLE));
        assertThrows(RuntimeException.class,()->evaluate(List.of(evidence(List.of(),List.of(),List.of(),List.of())),null));
    }
    @Test void malformedResultCannotProducePartialTimeline(){
        assertThrows(RuntimeException.class,()->timeline(new Result(List.of(),List.of(),List.of(),List.of()),List.of(),interval(0,100)));
    }
}
