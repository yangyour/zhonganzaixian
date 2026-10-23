package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.*;
import cn.mop.domain.sla.SlaClockState;
import cn.mop.domain.sla.TicketSlaClockEvaluation;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.WorkflowLifecycle;
import cn.mop.domain.time.IntervalSets.Interval;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure DTO/math checks reuse the service SLA evaluation; no local database or fabricated live metrics. */
class DashboardTicketSlaTest {
    static final Instant START = Instant.parse("2026-10-23T08:00:00Z");
    static final String ACTOR = "10000000-0000-4000-8000-000000000001";
    static final String TENANT = "20000000-0000-4000-8000-000000000002";

    @Test
    void knownBreachDominatesUnknownButUnknownCannotBecomeHealthy() {
        assertEquals(true, DashboardTicketSla.combine(true, null));
        assertEquals(true, DashboardTicketSla.combine(null, true));
        assertNull(DashboardTicketSla.combine(false, null));
        assertNull(DashboardTicketSla.combine(null, null));
        assertEquals(false, DashboardTicketSla.combine(false, false));
    }

    @Test
    void remainingServiceBudgetExcludesOffHours() {
        var due = START.plus(Duration.ofDays(3)).plusSeconds(3600);
        var windows = List.of(new Interval(START, START.plusSeconds(3600)),
                new Interval(START.plus(Duration.ofDays(3)), due));
        var evaluated = new TicketSlaClockEvaluation.ClockStatus("RESPONSE", due, null, false, false, "RUNNING", null,
                new TicketSlaClockEvaluation.Thresholds(null, null, due), windows);
        var clock = DashboardTicketSla.workflow(evaluated, START.plus(Duration.ofDays(1)));
        assertEquals(3_600_000L, clock.remainingMillis());
        assertEquals("FROZEN_SERVICE_WINDOWS", clock.remainingBasis());
    }

    @Test
    void completedAndBreachedClocksUseTheirAuthoritativeStopInstant() {
        var due = START.plusSeconds(3600);
        var evaluated = new TicketSlaClockEvaluation.ClockStatus("RESPONSE", due, START.plusSeconds(600), false,
                false, "MET", null, new TicketSlaClockEvaluation.Thresholds(null, null, due), List.of(new Interval(START, due)));
        assertEquals(3_000_000L, DashboardTicketSla.workflow(evaluated, START.plusSeconds(7200)).remainingMillis());
        var late = new TicketSlaClockEvaluation.ClockStatus("RESPONSE", due, null, false, true, "BREACHED", null,
                evaluated.thresholds(), evaluated.workingWindows());
        assertEquals(0L, DashboardTicketSla.workflow(late, START.plusSeconds(7200)).remainingMillis());
    }

    @Test
    void missingConfigurationPreservesUnknownClockAndRemainingBudget() {
        var sla = TicketSlaSnapshot.freeze("P1", Tier.STANDARD, START, null, null, null, null, null, List.of(), Map.of());
        var evaluation = TicketSlaClockEvaluation.evaluate(WorkflowLifecycle.open(sla, START), null, START.plusSeconds(100));
        var clock = DashboardTicketSla.workflow(evaluation.responseClock(), START.plusSeconds(100));
        assertNull(clock.breached());
        assertNull(clock.remainingMillis());
        assertEquals("CONFIGURATION_UNAVAILABLE", clock.missingReason());
    }

    @Test
    void lifecycleUsesExistingElapsedAndBudgetWithoutGuessingDateTargetDuration() {
        var clock = new SlaClockState.Clock(SlaClockState.Status.PAUSED, null, null,
                Duration.ofMinutes(20), Duration.ofHours(1), false, null);
        assertEquals(2_400_000L, DashboardTicketSla.lifecycle(clock).remainingMillis());
        var dateTarget = new SlaClockState.Clock(SlaClockState.Status.RUNNING, START.plusSeconds(300), null,
                Duration.ofMinutes(20), null, false, null);
        assertNull(DashboardTicketSla.lifecycle(dateTarget).remainingMillis());
    }

    static DashboardPendingTicketsReader.Snapshot base(long pending, long unconfigured) {
        var items = java.util.stream.IntStream.range(0, (int) Math.min(5, pending)).mapToObj(index ->
                new DashboardPendingTicketsReader.Item(UUID.randomUUID(), "SYNTHETIC", "SYNTHETIC", START,
                        "WORKFLOW_V33", "OPEN", 1)).toList();
        return new DashboardPendingTicketsReader.Snapshot(UUID.fromString(TENANT), UUID.fromString(ACTOR), 1,
                START.plusSeconds(1), "CURRENT_TENANT_INTERNAL", pending + unconfigured, pending, 0, unconfigured, items);
    }

    static DashboardPendingTicketSlaReader.Snapshot snapshot(long pending, long unconfigured, long unknown) {
        var base = base(pending, unconfigured);
        var knownClock = new DashboardTicketSla.Clock("RUNNING", false, null, START.plusSeconds(3600), null,
                3_599_000L, "FROZEN_SERVICE_WINDOWS");
        var items = java.util.stream.IntStream.range(0, base.items().size()).mapToObj(index -> {
            var item = base.items().get(index);
            return new DashboardPendingTicketSlaReader.Item(item, index < unknown
                    ? DashboardTicketSla.unavailable(item.version(), "CONFIGURATION_UNAVAILABLE")
                    : new DashboardTicketSla.Assessment("P1", "STANDARD", "SLA_CLOCK_V2", item.version(), knownClock, knownClock));
        }).toList();
        return new DashboardPendingTicketSlaReader.Snapshot(base, Math.min(100, pending), 0, unknown, items);
    }

    @Test
    void unknownUnconfiguredAndUnevaluatedPopulationPreventsFalseCompleteOverdueCount() {
        assertNull(snapshot(1, 0, 1).overdueSla());
        assertNull(snapshot(1, 1, 0).overdueSla());
        assertNull(snapshot(101, 0, 0).overdueSla());
        assertEquals(1, snapshot(101, 0, 0).unevaluatedPending());
        assertEquals(0L, snapshot(0, 0, 0).overdueSla());
    }

    @Test
    void inconsistentItemVersionAndOverflowCountsAreRejected() {
        var base = base(1, 0);
        assertThrows(IllegalArgumentException.class, () -> new DashboardPendingTicketSlaReader.Item(base.items().get(0),
                DashboardTicketSla.unavailable(2, "CONFIG_PENDING")));
        assertThrows(IllegalArgumentException.class, () -> new DashboardPendingTicketSlaReader.Snapshot(base,
                1, Long.MAX_VALUE, 1, List.of()));
    }

    @Test
    void v2ResponsePreservesUnknownsAndRejectsCallerTimeOrScopeOverrides() {
        var platform = mock(PlatformRepository.class);
        var reader = mock(DashboardPendingTicketSlaReader.class);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 1, true)));
        when(reader.read(any())).thenReturn(snapshot(1, 0, 1));
        var controller = new DashboardPendingTicketSlaController(platform, reader);
        var request = new MockHttpServletRequest("GET", "/api/v1/dashboard/pending-tickets/sla");
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", TENANT);
        var data = (Map<?, ?>) ((Map<?, ?>) controller.read(request)).get("data");
        assertEquals("MOP_PENDING_TICKETS_SLA_V2", data.get("projection_version"));
        assertNull(data.get("overdue_sla"));
        assertEquals(1L, data.get("unknown_sla"));
        request.addParameter("observed_at", START.toString());
        assertEquals(400, assertThrows(DomainException.class, () -> controller.read(request)).status);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 1, true)));
        request.removeAllParameters();
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request)).status);
    }
}
