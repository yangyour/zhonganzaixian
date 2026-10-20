package cn.mop.domain.sla;

import cn.mop.domain.time.IntervalSets;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Candidate deadline arithmetic over an immutable opening snapshot.
 * No database write, effective-calendar approval, reminder or customer SLA claim.
 */
public final class SlaClockCalculator {
    private SlaClockCalculator() {}
    public enum Tier { BASIC, STANDARD, ADVANCED }
    public enum Priority { P1, P2, P3 }
    public enum Status { CALCULATED, UNRESOLVED }
    public enum ReopenPolicy { EXCLUDE_CONFIRMED_PAUSES, INCLUDE_ALL_ELAPSED_TIME }
    public record OpeningSnapshot(Tier tier, Priority priority, Instant openedAt,
                                  String configurationReleaseRef, Duration responseBudget,
                                  Duration recoveryBudget, WorkingTime.CalendarData calendar,
                                  Instant resolvedNextDayDeadline, String nextDayRuleRef) {
        public OpeningSnapshot {
            if (responseBudget != null && (responseBudget.isNegative() || responseBudget.isZero())
                    || recoveryBudget != null && (recoveryBudget.isNegative() || recoveryBudget.isZero()))
                throw new IllegalArgumentException("Configured SLA budgets must be positive");
            if (configurationReleaseRef != null && configurationReleaseRef.isBlank()
                    || nextDayRuleRef != null && nextDayRuleRef.isBlank())
                throw new IllegalArgumentException("Snapshot references must not be blank");
            if (resolvedNextDayDeadline != null && openedAt != null && !resolvedNextDayDeadline.isAfter(openedAt))
                throw new IllegalArgumentException("Next-day deadline must follow opening");
        }
    }
    public record Deadline(Status status, Instant at, String reason) {
        public Deadline {
            if (status == null || status == Status.CALCULATED && (at == null || reason != null)
                    || status == Status.UNRESOLVED && (at != null || reason == null))
                throw new IllegalArgumentException("Deadline status and value disagree");
        }
    }
    public record Result(OpeningSnapshot snapshot, Deadline response, Deadline recovery,
                         List<Interval> excludedPauses) {
        public Result { excludedPauses = List.copyOf(excludedPauses); }
        public String scope() { return "EXPLICIT_OPENING_SNAPSHOT_SLA_CALCULATION"; }
        public boolean persisted() { return false; }
        public boolean productionApproved() { return false; }
    }

    public static Result initial(OpeningSnapshot snapshot) {
        return calculate(snapshot, List.of(), null, false);
    }

    /**
     * Completed, independently verified pause intervals only. An unknown/open pause
     * cannot be supplied as an empty list. Q27 must be explicit before recomputation.
     * Recomputes from the original opening, never resets a budget at reopen/transfer.
     */
    public static Result resume(OpeningSnapshot snapshot, List<Interval> completedPauses, ReopenPolicy policy) {
        return calculate(snapshot, completedPauses, policy, true);
    }

    private static Result calculate(OpeningSnapshot s, List<Interval> pauses, ReopenPolicy policy, boolean resumed) {
        if (s == null) throw new IllegalArgumentException("Opening snapshot required");
        String missing = s.openedAt() == null ? "OPENED_AT_MISSING" : s.tier() == null ? "TIER_MISSING"
                : s.priority() == null ? "PRIORITY_MISSING" : s.configurationReleaseRef() == null ? "CONFIGURATION_SNAPSHOT_MISSING" : null;
        if (missing != null) return new Result(s, pending(missing), pending(missing), List.of());
        Deadline response = deadline(s, s.responseBudget(), List.of());
        if (resumed && (policy == null || pauses == null))
            return new Result(s, response, pending(policy == null ? "REOPEN_POLICY_MISSING" : "PAUSE_HISTORY_MISSING"), List.of());
        List<Interval> exclusions = resumed && policy == ReopenPolicy.EXCLUDE_CONFIRMED_PAUSES
                ? IntervalSets.union(pauses) : List.of();
        if (s.priority() == Priority.P3 && s.tier() != Tier.ADVANCED) {
            // Q08 determines what "next day" means; this layer cannot invent that rule.
            if (s.nextDayRuleRef() == null || s.resolvedNextDayDeadline() == null)
                return new Result(s, response, pending("NEXT_DAY_RULE_MISSING"), exclusions);
            // Q27 must separately define how a calendar-date target moves after pauses.
            if (!exclusions.isEmpty())
                return new Result(s, response, pending("NEXT_DAY_PAUSE_RULE_MISSING"), exclusions);
            return new Result(s, response, calculated(s.resolvedNextDayDeadline()), exclusions);
        }
        return new Result(s, response, deadline(s, s.recoveryBudget(), exclusions), exclusions);
    }

    private static Deadline deadline(OpeningSnapshot s, Duration budget, List<Interval> pauses) {
        if (budget == null) return pending("DURATION_MISSING");
        try {
            if (s.tier() == Tier.BASIC) {
                var calendar = s.calendar();
                if (calendar == null) return pending("CALENDAR_MISSING");
                var filtered = new WorkingTime.CalendarData(calendar.id(), calendar.version(), calendar.coverage(),
                        IntervalSets.subtract(calendar.workingWindows(), pauses), calendar.complete());
                var result = WorkingTime.advance(filtered, s.openedAt(), budget);
                return result.status() == WorkingTime.Status.CALCULATED
                        ? calculated(result.deadline()) : pending(result.reason().name());
            }
            Instant cursor = s.openedAt();
            Duration remaining = budget;
            for (Interval pause : IntervalSets.union(pauses)) {
                if (!pause.end().isAfter(cursor)) continue;
                Instant start = pause.start().isAfter(cursor) ? pause.start() : cursor;
                Duration available = Duration.between(cursor, start);
                // Reaching the exact target at the pause start does not extend a met SLA.
                if (remaining.compareTo(available) <= 0) return calculated(cursor.plus(remaining));
                remaining = remaining.minus(available);
                cursor = pause.end();
            }
            return calculated(cursor.plus(remaining));
        } catch (ArithmeticException | java.time.DateTimeException overflow) {
            return pending("DEADLINE_OUT_OF_RANGE");
        }
    }
    private static Deadline calculated(Instant at) { return new Deadline(Status.CALCULATED, at, null); }
    private static Deadline pending(String reason) { return new Deadline(Status.UNRESOLVED, null, reason); }
}
