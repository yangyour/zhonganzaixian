package cn.mop.api;

import cn.mop.domain.sla.SlaClockCalculator;
import cn.mop.domain.sla.SlaClockCalculator.*;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime.CalendarData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlaClockCalculatorTest {
    private static Instant time(String text) { return Instant.parse(text); }
    private static final Instant T=time("2026-10-30T15:50:00Z"); // Shanghai Friday 23:50
    private static OpeningSnapshot snapshot(Tier tier, Priority priority, CalendarData calendar) {
        return new OpeningSnapshot(tier,priority,T,"synthetic-release-v1",Duration.ofMinutes(30),
                Duration.ofHours(4),calendar,null,null);
    }
    private static Interval interval(String a,String b){return new Interval(time(a),time(b));}
    private static CalendarData calendar(boolean complete) {
        return new CalendarData("synthetic-calendar",7,
                interval("2026-10-30T00:00:00Z","2026-11-04T00:00:00Z"),
                List.of(interval("2026-11-02T01:00:00Z","2026-11-02T05:00:00Z"),
                        interval("2026-11-02T06:00:00Z","2026-11-02T09:00:00Z"),
                        interval("2026-11-03T01:00:00Z","2026-11-03T09:00:00Z")),complete);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD,P1,2026-10-30T23:50:00Z,2026-10-31T00:20:00Z,2026-10-31T03:50:00Z",
        "STANDARD,P2,2026-10-31T23:50:00Z,2026-11-01T00:20:00Z,2026-11-01T03:50:00Z",
        "ADVANCED,P1,2026-12-31T23:50:00Z,2027-01-01T00:20:00Z,2027-01-01T03:50:00Z",
        "ADVANCED,P2,2028-02-28T23:50:00Z,2028-02-29T00:20:00Z,2028-02-29T03:50:00Z",
        "ADVANCED,P3,2028-02-29T23:50:00Z,2028-03-01T00:20:00Z,2028-03-01T03:50:00Z",
        "STANDARD,P1,2026-10-30T15:50:00Z,2026-10-30T16:20:00Z,2026-10-30T19:50:00Z",
        "ADVANCED,P2,2026-10-31T00:00:00Z,2026-10-31T00:30:00Z,2026-10-31T04:00:00Z",
        "STANDARD,P2,2026-01-01T00:00:00Z,2026-01-01T00:30:00Z,2026-01-01T04:00:00Z"
    })
    void continuousTiersIncludeNightHolidayMonthYearAndLeapBoundaries(Tier tier,Priority priority,String start,String response,String recovery){
        var s=new OpeningSnapshot(tier,priority,time(start),"synthetic-v1",Duration.ofMinutes(30),Duration.ofHours(4),null,null,null);
        var r=SlaClockCalculator.initial(s);
        assertEquals(time(response),r.response().at());assertEquals(time(recovery),r.recovery().at());
        assertFalse(r.persisted());assertFalse(r.productionApproved());
    }
    @Test void basicSkipsExplicitNonworkingDaysAndRetainsCalendarRevision(){
        var s=snapshot(Tier.BASIC,Priority.P1,calendar(true));var r=SlaClockCalculator.initial(s);
        assertEquals(time("2026-11-02T01:30:00Z"),r.response().at());
        assertEquals(time("2026-11-02T05:00:00Z"),r.recovery().at());
        assertEquals(7,r.snapshot().calendar().version());
    }
    @Test void basicSpansLunchAndNextWorkingDay(){
        var s=new OpeningSnapshot(Tier.BASIC,Priority.P2,time("2026-11-02T04:50:00Z"),"v1",Duration.ofMinutes(30),Duration.ofHours(4),calendar(true),null,null);
        var r=SlaClockCalculator.initial(s);
        assertEquals(time("2026-11-02T06:20:00Z"),r.response().at());
        assertEquals(time("2026-11-03T01:50:00Z"),r.recovery().at());
    }
    @Test void incompleteMissingOrExhaustedCalendarDoesNotInventDeadline(){
        assertEquals("CALENDAR_MISSING",SlaClockCalculator.initial(snapshot(Tier.BASIC,Priority.P1,null)).response().reason());
        assertEquals("COVERAGE_INCOMPLETE",SlaClockCalculator.initial(snapshot(Tier.BASIC,Priority.P1,calendar(false))).response().reason());
        var s=new OpeningSnapshot(Tier.BASIC,Priority.P1,T,"v1",Duration.ofDays(20),null,calendar(true),null,null);
        assertEquals("INSUFFICIENT_WORKING_TIME",SlaClockCalculator.initial(s).response().reason());
        assertNull(SlaClockCalculator.initial(s).response().at());
    }
    @Test void unknownDurationAndOpeningInputsStayUnresolved(){
        var s=new OpeningSnapshot(Tier.STANDARD,Priority.P1,T,"v1",null,null,null,null,null);
        assertEquals("DURATION_MISSING",SlaClockCalculator.initial(s).response().reason());
        var noConfig=new OpeningSnapshot(Tier.STANDARD,Priority.P1,T,null,Duration.ofMinutes(1),Duration.ofMinutes(1),null,null,null);
        assertEquals("CONFIGURATION_SNAPSHOT_MISSING",SlaClockCalculator.initial(noConfig).recovery().reason());
        assertEquals("OPENED_AT_MISSING",SlaClockCalculator.initial(new OpeningSnapshot(null,null,null,null,null,null,null,null,null)).response().reason());
    }
    @Test void p3NextDayDoesNotFallBackToFourHours(){
        for(Tier tier:List.of(Tier.BASIC,Tier.STANDARD)){
            var r=SlaClockCalculator.initial(snapshot(tier,Priority.P3,calendar(true)));
            assertEquals("NEXT_DAY_RULE_MISSING",r.recovery().reason());assertNull(r.recovery().at());
        }
    }
    @Test void p3UsesOnlyExplicitResolvedNextDayAndItsRuleReference(){
        Instant due=time("2026-11-02T09:00:00Z");
        var s=new OpeningSnapshot(Tier.STANDARD,Priority.P3,T,"v1",Duration.ofMinutes(30),Duration.ofHours(4),null,due,"Q08-release-v2");
        assertEquals(due,SlaClockCalculator.initial(s).recovery().at());
        assertEquals("NEXT_DAY_PAUSE_RULE_MISSING",SlaClockCalculator.resume(s,List.of(new Interval(T,T.plusSeconds(10))),ReopenPolicy.EXCLUDE_CONFIRMED_PAUSES).recovery().reason());
    }
    @Test void reopeningNeverRestartsResponseAndRequiresExplicitQ27(){
        var s=snapshot(Tier.STANDARD,Priority.P1,null);
        var r=SlaClockCalculator.resume(s,List.of(),null);
        assertEquals("REOPEN_POLICY_MISSING",r.recovery().reason());
        assertEquals(SlaClockCalculator.initial(s).response(),r.response());
        assertEquals("PAUSE_HISTORY_MISSING",SlaClockCalculator.resume(s,null,ReopenPolicy.EXCLUDE_CONFIRMED_PAUSES).recovery().reason());
    }
    @Test void continuousRecoverySubtractsMergedPausesWithoutDoubleCounting(){
        var s=snapshot(Tier.STANDARD,Priority.P1,null);
        var pauses=new ArrayList<>(List.of(new Interval(T.plusSeconds(3600),T.plusSeconds(7200)),
                new Interval(T.plusSeconds(5400),T.plusSeconds(10800))));
        var r=SlaClockCalculator.resume(s,pauses,ReopenPolicy.EXCLUDE_CONFIRMED_PAUSES);
        assertEquals(T.plus(Duration.ofHours(6)),r.recovery().at());assertEquals(1,r.excludedPauses().size());
        pauses.clear();assertEquals(1,r.excludedPauses().size());
        assertThrows(UnsupportedOperationException.class,()->r.excludedPauses().clear());
    }
    @Test void pausesBeforeOpeningOrAtMetDeadlineDoNotExtendTarget(){
        var s=snapshot(Tier.STANDARD,Priority.P1,null);
        var r=SlaClockCalculator.resume(s,List.of(new Interval(T.minusSeconds(50),T),
                new Interval(T.plus(Duration.ofHours(4)),T.plus(Duration.ofHours(6)))),ReopenPolicy.EXCLUDE_CONFIRMED_PAUSES);
        assertEquals(T.plus(Duration.ofHours(4)),r.recovery().at());
    }
    @Test void basicRecoveryExcludesOnlyPauseIntersectionWithWorkingTime(){
        var s=snapshot(Tier.BASIC,Priority.P1,calendar(true));
        var r=SlaClockCalculator.resume(s,List.of(interval("2026-11-02T04:00:00Z","2026-11-02T06:30:00Z")),ReopenPolicy.EXCLUDE_CONFIRMED_PAUSES);
        assertEquals(time("2026-11-02T07:30:00Z"),r.recovery().at());
        assertEquals(time("2026-11-02T01:30:00Z"),r.response().at());
    }
    @Test void includeElapsedPolicyAndLaterTierChangesLeaveOpeningSnapshotUnchanged(){
        var old=snapshot(Tier.STANDARD,Priority.P1,null);
        var original=SlaClockCalculator.initial(old);
        var other=snapshot(Tier.BASIC,Priority.P1,calendar(true));
        SlaClockCalculator.initial(other);
        var resumed=SlaClockCalculator.resume(old,List.of(new Interval(T,T.plusSeconds(100))),ReopenPolicy.INCLUDE_ALL_ELAPSED_TIME);
        assertEquals(original.response(),resumed.response());assertEquals(original.recovery(),resumed.recovery());
        assertSame(old,resumed.snapshot());
    }
    @Test void invalidBudgetsAndOverflowFailWithoutWrappedDeadline(){
        assertThrows(IllegalArgumentException.class,()->new OpeningSnapshot(Tier.STANDARD,Priority.P1,T,"v1",Duration.ZERO,null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new OpeningSnapshot(Tier.STANDARD,Priority.P1,T,"v1",null,Duration.ofSeconds(-1),null,null,null));
        var s=new OpeningSnapshot(Tier.STANDARD,Priority.P1,Instant.MAX,"v1",Duration.ofSeconds(1),null,null,null,null);
        assertEquals("DEADLINE_OUT_OF_RANGE",SlaClockCalculator.initial(s).response().reason());
    }
}
