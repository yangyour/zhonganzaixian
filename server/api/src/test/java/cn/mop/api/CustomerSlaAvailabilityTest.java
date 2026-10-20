package cn.mop.api;

import cn.mop.domain.sla.SlaAvailabilityAndCompensation;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.*;
import cn.mop.domain.sla.SlaComplianceScenario.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.time.IntervalSets.Interval;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerSlaAvailabilityTest {
    static final Instant START=Instant.parse("2026-09-30T16:00:00Z");
    static final Interval WINDOW=new Interval(START,START.plusSeconds(1000));
    ServiceRow evaluate(List<Interval> excluded,List<Interval> bad,List<Interval> unknown,List<Interval> coverage){
        return SlaAvailabilityAndCompensation.evaluateService("ASSET_SYNTHETIC","合成设备",new BigDecimal("99.9"),
            WINDOW,excluded,bad,unknown,List.of(),coverage);
    }
    @Test void absentSamplesNeverMeanHealthy(){
        var row=evaluate(List.of(),List.of(),List.of(),List.of());
        assertNull(row.verifiedAvailabilityPct());assertEquals(0,row.knownAvailableSec());
        assertEquals(1000,row.unknownIntervalSec());assertEquals(0,row.effectiveServiceSec());
        assertEquals(ServiceStatus.INSUFFICIENT_SAMPLES,row.status());
    }
    @Test void coverageBoundaryIsNinetyPercentWithoutRoundingUp(){
        var low=evaluate(List.of(),List.of(),List.of(),List.of(new Interval(START,START.plusSeconds(899))));
        assertNull(low.verifiedAvailabilityPct());
        var enough=evaluate(List.of(),List.of(),List.of(),List.of(new Interval(START,START.plusSeconds(900))));
        assertEquals(new BigDecimal("100.0000"),enough.verifiedAvailabilityPct());
        assertEquals(900,enough.effectiveServiceSec());assertEquals(100,enough.unknownIntervalSec());
    }
    @Test void unknownIsExcludedFromBothNumeratorAndDenominatorAndExclusionsAreUnioned(){
        var row=evaluate(List.of(new Interval(START,START.plusSeconds(50)),new Interval(START.plusSeconds(20),START.plusSeconds(100))),
            List.of(new Interval(START.plusSeconds(100),START.plusSeconds(190))),
            List.of(new Interval(START.plusSeconds(910),START.plusSeconds(1000))),List.of(WINDOW));
        assertEquals(100,row.approvedExclusionSec());assertEquals(810,row.effectiveServiceSec());
        assertEquals(90,row.unknownIntervalSec());assertEquals(720,row.knownAvailableSec());
        assertEquals(new BigDecimal("88.8889"),row.verifiedAvailabilityPct());
        assertEquals(ServiceStatus.NOT_MET,row.status());
    }
    @Test void crossMonthProblemIsClippedAndDoesNotEstablishHealthyCoverage(){
        var row=evaluate(List.of(),List.of(new Interval(START.minusSeconds(60),START.plusSeconds(30))),List.of(),List.of());
        assertEquals(30,row.unavailableUnionSec());assertEquals(970,row.unknownIntervalSec());
        assertNull(row.verifiedAvailabilityPct());
    }
    @Test void compensationCandidateUsesFixedRatioAndRoundsToWholeYuan(){
        var ticket=new TicketSample("SYNTHETIC_TICKET",Priority.P1,Tier.STANDARD,Exclusion.NONE,null,
            START,START.plusSeconds(10),START.plusSeconds(20),START.plusSeconds(30),START.plusSeconds(40));
        var result=SlaAvailabilityAndCompensation.evaluateMonthly(Tier.STANDARD,12345L,List.of(ticket));
        assertEquals(2500L,result.candidateFixedRatioFen());assertNull(result.candidateShortfallWeightedFen());
        assertNull(result.compensationAmountFen());
    }
}
