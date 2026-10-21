package cn.mop.api;

import cn.mop.domain.sla.ServiceTierRepository;
import cn.mop.domain.sla.ServiceTierRepository.EffectiveMode;
import cn.mop.domain.sla.TicketSlaClockEvaluation;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.WorkflowLifecycle;
import cn.mop.domain.ticket.WorkflowLifecycle.*;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime.CalendarData;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 20 boundary scenarios for D16 SLA clock calculation, pause/resume, and service-tier change immutability (M2-10/M5-8/M5-20). */
class TicketSlaClockBoundaryTest {
    private static CalendarData twoDayCalendar() {
        return new CalendarData("cal-1", 1,
            new Interval(Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z")),
            List.of(
                // Friday 2026-10-16: 01:00-04:00Z (09:00-12:00 CST), 05:00-09:00Z (13:00-17:00 CST)
                new Interval(Instant.parse("2026-10-16T01:00:00Z"), Instant.parse("2026-10-16T04:00:00Z")),
                new Interval(Instant.parse("2026-10-16T05:00:00Z"), Instant.parse("2026-10-16T09:00:00Z")),
                // Weekend Oct 17-18 skipped; Monday Oct 19 is holiday; Tuesday Oct 20 resumes:
                new Interval(Instant.parse("2026-10-20T01:00:00Z"), Instant.parse("2026-10-20T04:00:00Z")),
                new Interval(Instant.parse("2026-10-20T05:00:00Z"), Instant.parse("2026-10-20T09:00:00Z")),
                new Interval(Instant.parse("2026-10-21T01:00:00Z"), Instant.parse("2026-10-21T09:00:00Z"))
            ),
            true);
    }

    @Test void case01_lateNight2350BasicStartsAtNextWorkingMorning() {
        // 2026-10-15T15:50:00Z is 23:50 Asia/Shanghai; calendar coverage starts 2026-10-16T00:00:00Z
        var cal = new CalendarData("cal-night", 1,
            new Interval(Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z")),
            List.of(new Interval(Instant.parse("2026-10-16T01:00:00Z"), Instant.parse("2026-10-16T09:00:00Z"))),
            true);
        var s = TicketSlaSnapshot.freeze("P1", Tier.BASIC, Instant.parse("2026-10-15T15:50:00Z"),
            Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, cal, List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-16T01:30:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-10-16T05:00:00Z"), s.recoveryDueAt());
    }

    @Test void case02_lateNight2350StandardRuns24x7AcrossMidnight() {
        var s = TicketSlaSnapshot.freeze("P1", Tier.STANDARD, Instant.parse("2026-10-15T15:50:00Z"),
            Duration.ofMinutes(10), Duration.ofMinutes(120), null, 48L, null, List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-15T16:00:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-10-15T17:50:00Z"), s.recoveryDueAt());
    }

    @Test void case03_lateNight2350AdvancedRuns24x7WithFasterRecovery() {
        var s = TicketSlaSnapshot.freeze("P1", Tier.ADVANCED, Instant.parse("2026-10-15T15:50:00Z"),
            Duration.ofMinutes(10), Duration.ofMinutes(90), null, 24L, null, List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-15T16:00:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-10-15T17:20:00Z"), s.recoveryDueAt());
    }

    @Test void case04_weekendOpeningOnBasicSkipsSaturdayAndSunday() {
        var s = TicketSlaSnapshot.freeze("P2", Tier.BASIC, Instant.parse("2026-10-17T03:00:00Z"),
            Duration.ofMinutes(60), Duration.ofMinutes(240), null, 48L, twoDayCalendar(), List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-20T02:00:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-10-20T06:00:00Z"), s.recoveryDueAt());
    }

    @Test void case05_holidayOpeningOnBasicSkipsHolidayToNextWorkingWindow() {
        var s = TicketSlaSnapshot.freeze("P1", Tier.BASIC, Instant.parse("2026-10-19T02:00:00Z"),
            Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, twoDayCalendar(), List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-20T01:30:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-10-20T06:00:00Z"), s.recoveryDueAt());
    }

    @Test void case06_crossLunchBreakOnBasicBridgesNoonGap() {
        // Starts at 03:50Z (10 min before 04:00-05:00Z lunch gap); 30m response needs 10m before + 20m after lunch
        var s = TicketSlaSnapshot.freeze("P1", Tier.BASIC, Instant.parse("2026-10-16T03:50:00Z"),
            Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, twoDayCalendar(), List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-16T05:20:00Z"), s.responseDueAt());
    }

    @Test void case07_multiDayRecoveryOnBasicSpansAcrossWeekendAndHoliday() {
        // Friday 06:00Z has 3h left on Friday; 480m (8h) P2 recovery consumes 3h Friday + 3h Tue morning + 2h Tue afternoon -> Tue 07:00Z
        var s = TicketSlaSnapshot.freeze("P2", Tier.BASIC, Instant.parse("2026-10-16T06:00:00Z"),
            Duration.ofMinutes(60), Duration.ofMinutes(480), null, 48L, twoDayCalendar(), List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-16T07:00:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-10-20T07:00:00Z"), s.recoveryDueAt());
    }

    @Test void case08_crossMonthBoundaryStandardCalculatesExactNovemberTimestamps() {
        var s = TicketSlaSnapshot.freeze("P2", Tier.STANDARD, Instant.parse("2026-10-31T23:50:00Z"),
            Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, null, List.of(), Map.of());
        assertEquals(Instant.parse("2026-11-01T00:20:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-11-01T03:50:00Z"), s.recoveryDueAt());
    }

    @Test void case09_crossYearBoundaryAdvancedCalculatesExactJanuaryTimestamps() {
        var s = TicketSlaSnapshot.freeze("P1", Tier.ADVANCED, Instant.parse("2026-12-31T23:50:00Z"),
            Duration.ofMinutes(10), Duration.ofMinutes(90), null, 24L, null, List.of(), Map.of());
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2027-01-01T01:20:00Z"), s.recoveryDueAt());
    }

    @Test void case10_sameP1PriorityAcrossThreeTiersProducesDistinctDeadlines_M5_8() {
        Instant start = Instant.parse("2026-10-16T08:50:00Z"); // 10m before Friday close
        var basic = TicketSlaSnapshot.freeze("P1", Tier.BASIC, start, Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, twoDayCalendar(), List.of(), Map.of());
        var standard = TicketSlaSnapshot.freeze("P1", Tier.STANDARD, start, Duration.ofMinutes(10), Duration.ofMinutes(120), null, 48L, null, List.of(), Map.of());
        var advanced = TicketSlaSnapshot.freeze("P1", Tier.ADVANCED, start, Duration.ofMinutes(10), Duration.ofMinutes(90), null, 24L, null, List.of(), Map.of());
        assertNotEquals(basic.responseDueAt(), standard.responseDueAt());
        assertNotEquals(basic.recoveryDueAt(), standard.recoveryDueAt());
        assertNotEquals(standard.recoveryDueAt(), advanced.recoveryDueAt());
    }

    @Test void case11_sameP2PriorityAcrossThreeTiersProducesDistinctDeadlines_M5_8() {
        Instant start = Instant.parse("2026-10-16T01:00:00Z");
        var basic = TicketSlaSnapshot.freeze("P2", Tier.BASIC, start, Duration.ofMinutes(60), Duration.ofMinutes(480), null, 48L, twoDayCalendar(), List.of(), Map.of());
        var standard = TicketSlaSnapshot.freeze("P2", Tier.STANDARD, start, Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, null, List.of(), Map.of());
        var advanced = TicketSlaSnapshot.freeze("P2", Tier.ADVANCED, start, Duration.ofMinutes(20), Duration.ofMinutes(180), null, 24L, null, List.of(), Map.of());
        assertTrue(advanced.responseDueAt().isBefore(standard.responseDueAt()));
        assertTrue(standard.responseDueAt().isBefore(basic.responseDueAt()));
        assertTrue(advanced.recoveryDueAt().isBefore(standard.recoveryDueAt()));
        assertTrue(standard.recoveryDueAt().isBefore(basic.recoveryDueAt()));
    }

    @Test void case12_p3AdvancedUsesMinutesDurationDirectly() {
        Instant start = Instant.parse("2026-10-16T01:00:00Z");
        var s = TicketSlaSnapshot.freeze("P3", Tier.ADVANCED, start, Duration.ofMinutes(60), Duration.ofMinutes(240), null, 24L, null, List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-16T02:00:00Z"), s.responseDueAt());
        assertEquals(Instant.parse("2026-10-16T05:00:00Z"), s.recoveryDueAt());
    }

    @Test void case13_p3BasicExplicitNextWorkingDayDeadlineWithinWorkingWindow() {
        Instant start = Instant.parse("2026-10-16T02:00:00Z");
        Instant nextWorkingDay = Instant.parse("2026-10-20T09:00:00Z");
        var s = TicketSlaSnapshot.freeze("P3", Tier.BASIC, start, Duration.ofMinutes(240), null, nextWorkingDay, 48L, twoDayCalendar(), List.of(), Map.of());
        assertEquals(nextWorkingDay, s.recoveryDueAt());
        assertTrue(s.missing().isEmpty());
    }

    @Test void case14_p3StandardExplicitNextCalendarDayDeadline() {
        Instant start = Instant.parse("2026-10-16T02:00:00Z");
        Instant nextDay = Instant.parse("2026-10-17T10:00:00Z");
        var s = TicketSlaSnapshot.freeze("P3", Tier.STANDARD, start, Duration.ofMinutes(120), null, nextDay, 48L, null, List.of(), Map.of());
        assertEquals(nextDay, s.recoveryDueAt());
        assertEquals(List.of(new Interval(start, nextDay)), s.recoveryWindows());
    }

    @Test void case15_p3BasicNextDayOutsideWorkingWindowRemainsNullWithReason() {
        Instant start = Instant.parse("2026-10-16T02:00:00Z");
        Instant nonWorkingSaturday = Instant.parse("2026-10-17T09:00:00Z");
        var s = TicketSlaSnapshot.freeze("P3", Tier.BASIC, start, Duration.ofMinutes(240), null, nonWorkingSaturday, 48L, twoDayCalendar(), List.of(), Map.of());
        assertNull(s.recoveryDueAt());
        assertEquals("NEXT_DAY_NOT_IN_WORKING_TIME", s.missing().get("recovery"));
    }

    @Test void case16_incompleteCalendarCoverageNeverFakesDeadline() {
        var incomplete = new CalendarData("cal-incomplete", 1,
            new Interval(Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-16T03:00:00Z")),
            List.of(new Interval(Instant.parse("2026-10-16T01:00:00Z"), Instant.parse("2026-10-16T03:00:00Z"))),
            false);
        var s = TicketSlaSnapshot.freeze("P1", Tier.BASIC, Instant.parse("2026-10-16T01:00:00Z"),
            Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, incomplete, List.of(), Map.of());
        assertNull(s.responseDueAt());
        assertNull(s.recoveryDueAt());
    }

    @Test void case17_thresholdsCountWorkingMillisecondsAcrossWeekendGap_M5_18() {
        // Friday 07:00Z has 2h working time left (07:00-09:00Z); Tuesday 01:00-03:00Z gives next 2h (total 4h = 240m)
        var s = TicketSlaSnapshot.freeze("P1", Tier.BASIC, Instant.parse("2026-10-16T07:00:00Z"),
            Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, twoDayCalendar(), List.of(), Map.of());
        assertEquals(Instant.parse("2026-10-16T09:00:00Z"), s.threshold("recovery", 50));
        assertEquals(Instant.parse("2026-10-20T02:12:00Z"), s.threshold("recovery", 80));
        assertEquals(Instant.parse("2026-10-20T03:00:00Z"), s.threshold("recovery", 100));
    }

    @Test void case18_customerConfirmationPausesRecoveryAndReopenPreservesOriginalDeadlines() {
        Instant t0 = Instant.parse("2026-10-16T01:00:00Z");
        var sla = TicketSlaSnapshot.freeze("P1", Tier.STANDARD, t0, Duration.ofMinutes(10), Duration.ofMinutes(120), null, 48L, null, List.of(), Map.of());
        var internal = new Operator("u-msp", true, false, false);
        var customer = new Operator("u-cust", false, false, true);
        Snapshot s1 = WorkflowLifecycle.open(sla, t0);
        Snapshot s2 = WorkflowLifecycle.apply(s1, new Command(Action.ACCEPT, 1, null, null, null, false), internal, t0.plusSeconds(300)).snapshot();
        Snapshot s3 = WorkflowLifecycle.apply(s2, new Command(Action.START, 2, null, null, null, false), internal, t0.plusSeconds(600)).snapshot();
        Snapshot s4 = WorkflowLifecycle.apply(s3, new Command(Action.RESOLVE, 3, null, "已完成故障根因修复并通过连通性与业务流量验证，请确认。", null, false), internal, t0.plusSeconds(1800)).snapshot();

        var evalResolved = TicketSlaClockEvaluation.evaluate(s4, Tier.STANDARD, t0.plusSeconds(10_000));
        assertEquals("MET", evalResolved.responseClock().status());
        assertTrue(evalResolved.recoveryClock().paused());
        assertEquals("PAUSED_FOR_CUSTOMER_CONFIRM", evalResolved.recoveryClock().status());
        assertEquals(Boolean.FALSE, evalResolved.recoveryClock().breached());

        Snapshot s5 = WorkflowLifecycle.apply(s4, new Command(Action.REOPEN, 4, null, "现场业务仍存在间歇性丢包", null, false), customer, t0.plusSeconds(2400)).snapshot();
        var evalReopened = TicketSlaClockEvaluation.evaluate(s5, Tier.STANDARD, t0.plusSeconds(2500));
        assertFalse(evalReopened.recoveryClock().paused());
        assertEquals("RUNNING", evalReopened.recoveryClock().status());
        assertEquals(sla.responseDueAt(), evalReopened.responseClock().dueAt());
        assertEquals(sla.recoveryDueAt(), evalReopened.recoveryClock().dueAt());
        assertEquals("PENDING_Q27", evalReopened.reopenPolicyStatus());
    }

    @Test void case19_changingTenantServiceTierLeavesThreeInFlightTicketsUnchanged_M2_10_M5_20() {
        Instant t0 = Instant.parse("2026-10-16T01:00:00Z");
        var internal = new Operator("u-msp", true, false, false);
        Snapshot ticket1 = WorkflowLifecycle.open(
            TicketSlaSnapshot.freeze("P1", Tier.BASIC, t0, Duration.ofMinutes(30), Duration.ofMinutes(240), null, 48L, twoDayCalendar(), List.of(), Map.of()), t0);
        Snapshot ticket2Open = WorkflowLifecycle.open(
            TicketSlaSnapshot.freeze("P2", Tier.BASIC, t0.plusSeconds(60), Duration.ofMinutes(60), Duration.ofMinutes(480), null, 48L, twoDayCalendar(), List.of(), Map.of()), t0.plusSeconds(60));
        Snapshot ticket2 = WorkflowLifecycle.apply(ticket2Open, new Command(Action.ACCEPT, 1, null, null, null, false), internal, t0.plusSeconds(120)).snapshot();
        Snapshot ticket3Open = WorkflowLifecycle.open(
            TicketSlaSnapshot.freeze("P3", Tier.BASIC, t0.plusSeconds(180), Duration.ofMinutes(240), null, Instant.parse("2026-10-20T09:00:00Z"), 48L, twoDayCalendar(), List.of(), Map.of()), t0.plusSeconds(180));
        Snapshot ticket3Acc = WorkflowLifecycle.apply(ticket3Open, new Command(Action.ACCEPT, 1, null, null, null, false), internal, t0.plusSeconds(240)).snapshot();
        Snapshot ticket3Start = WorkflowLifecycle.apply(ticket3Acc, new Command(Action.START, 2, null, null, null, false), internal, t0.plusSeconds(300)).snapshot();
        Snapshot ticket3 = WorkflowLifecycle.apply(ticket3Start, new Command(Action.RESOLVE, 3, null, "已完成交换机端口配置调整与稳定性观察，请客户确认。", null, false), internal, t0.plusSeconds(600)).snapshot();

        // Tenant upgrades from BASIC to ADVANCED at t0 + 900s:
        for (Snapshot inFlight : List.of(ticket1, ticket2, ticket3)) {
            var eval = TicketSlaClockEvaluation.evaluate(inFlight, Tier.ADVANCED, t0.plusSeconds(900));
            assertEquals(Tier.BASIC, eval.frozenServiceTier());
            assertEquals(Tier.ADVANCED, eval.currentTenantServiceTier());
            assertTrue(eval.tierChangedSinceOpen());
            assertEquals(inFlight.sla().responseDueAt(), eval.responseClock().dueAt());
            assertEquals(inFlight.sla().recoveryDueAt(), eval.recoveryClock().dueAt());
        }
    }

    @Test void case20_serviceTierEffectiveDateHandlesImmediateAndNextMonthRollover() {
        Instant oct = Instant.parse("2026-10-18T09:30:00Z");
        assertEquals(oct, ServiceTierRepository.resolveEffectiveFrom(oct, EffectiveMode.IMMEDIATE));
        // 2026-11-01 00:00:00+08:00 == 2026-10-31T16:00:00Z
        assertEquals(Instant.parse("2026-10-31T16:00:00Z"), ServiceTierRepository.resolveEffectiveFrom(oct, EffectiveMode.NEXT_MONTH_FIRST_DAY));
        // 2026-12-31T20:00:00Z is 2027-01-01 04:00:00+08:00 -> next month first day is 2027-02-01 00:00:00+08:00 (2027-01-31T16:00:00Z)
        Instant decLate = Instant.parse("2026-12-31T20:00:00Z");
        assertEquals(Instant.parse("2027-01-31T16:00:00Z"), ServiceTierRepository.resolveEffectiveFrom(decLate, EffectiveMode.NEXT_MONTH_FIRST_DAY));
    }

    @Test void confirmedMonitoringRecoveryStopsAtT2WhileReviewAndCustomerConfirmationRemainOpen() {
        Instant start=Instant.parse("2026-10-20T01:00:00Z"),t2=start.plusSeconds(1500),observed=start.plusSeconds(7200);
        Snapshot s=WorkflowLifecycle.open(TicketSlaSnapshot.freeze("P1",Tier.ADVANCED,start,Duration.ofMinutes(5),Duration.ofHours(1),null,24L,null,List.of(),Map.of()),start);
        var operator=new Operator("engineer",true,false,false);
        s=WorkflowLifecycle.apply(s,new Command(Action.ACCEPT,1,null,null,null,false),operator,start.plusSeconds(180)).snapshot();
        s=WorkflowLifecycle.apply(s,new Command(Action.START,2,null,null,null,false),operator,start.plusSeconds(200)).snapshot();
        var eval=TicketSlaClockEvaluation.evaluate(s,Tier.ADVANCED,observed,true,t2);
        assertEquals(State.IN_PROGRESS,eval.workflowState());
        assertEquals(t2,eval.recoveryClock().completedAt());
        assertEquals("MET",eval.recoveryClock().status());
        assertFalse(eval.recoveryClock().breached());
        assertEquals(start.plusSeconds(3600),eval.recoveryClock().dueAt());
        assertTrue(WorkflowLifecycle.dueNotices(s,observed,true,t2).stream().noneMatch(n->n.key().startsWith("recovery:")));
        var recurring=TicketSlaClockEvaluation.evaluate(s,Tier.ADVANCED,observed,true,null);
        assertEquals("BREACHED",recurring.recoveryClock().status());
        assertEquals(eval.recoveryClock().dueAt(),recurring.recoveryClock().dueAt());
    }
}
