package cn.mop.domain.dashboard;

import cn.mop.domain.sla.SlaClockState;
import cn.mop.domain.sla.TicketSlaClockEvaluation;
import cn.mop.domain.time.IntervalSets;
import java.time.Instant;
import java.util.ArrayList;

/** Dashboard presentation of existing service-side SLA evaluations, without introducing a new SLA policy. */
public final class DashboardTicketSla {
    private DashboardTicketSla() { }

    public record Clock(String status, Boolean breached, String missingReason, Instant deadline,
                        Instant completedAt, Long remainingMillis, String remainingBasis) { }

    public record Assessment(String priority, String frozenServiceTier, String metricVersion, long version,
                             Clock response, Clock recovery) {
        /** A known breach dominates an unknown other clock; two known non-breaches are required for false. */
        public Boolean overdue() {
            return combine(response.breached(), recovery.breached());
        }
    }

    public static Boolean combine(Boolean response, Boolean recovery) {
        if (Boolean.TRUE.equals(response) || Boolean.TRUE.equals(recovery)) {
            return true;
        }
        return response == null || recovery == null ? null : false;
    }

    public static Clock unavailable(String reason) {
        return new Clock("CONFIG_PENDING", null, reason, null, null, null, null);
    }

    public static Assessment unavailable(long version, String reason) {
        return new Assessment(null, null, null, version, unavailable(reason), unavailable(reason));
    }

    /** Remaining budget uses frozen service windows, so weekends and holidays are never counted as service time. */
    public static Clock workflow(TicketSlaClockEvaluation.ClockStatus evaluated, Instant observed) {
        if (evaluated.breached() == null || evaluated.dueAt() == null) {
            return new Clock(evaluated.status(), evaluated.breached(), evaluated.missingReason(),
                    evaluated.dueAt(), evaluated.completedAt(), null, null);
        }
        Instant at = evaluated.completedAt() == null ? observed : evaluated.completedAt();
        var remaining = new ArrayList<IntervalSets.Interval>();
        for (var window : evaluated.workingWindows()) {
            Instant start = window.start().isBefore(at) ? at : window.start();
            if (start.isBefore(window.end())) {
                remaining.add(new IntervalSets.Interval(start, window.end()));
            }
        }
        return new Clock(evaluated.status(), evaluated.breached(), evaluated.missingReason(), evaluated.dueAt(),
                evaluated.completedAt(), IntervalSets.duration(remaining).toMillis(), "FROZEN_SERVICE_WINDOWS");
    }

    public static Clock lifecycle(SlaClockState.Clock evaluated) {
        Long remaining = evaluated.budget() == null || evaluated.elapsed() == null ? null
                : Math.max(0, evaluated.budget().minus(evaluated.elapsed()).toMillis());
        return new Clock(evaluated.status().name(), evaluated.breached(), evaluated.missing(), evaluated.deadline(),
                evaluated.stoppedAt(), remaining, remaining == null ? null : "EXPLICIT_BUDGET_MINUS_ELAPSED");
    }
}
