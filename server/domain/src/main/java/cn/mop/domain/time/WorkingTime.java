package cn.mop.domain.time;

import cn.mop.domain.time.IntervalSets.Interval;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Arithmetic over explicit caller-supplied windows; never a default calendar or customer approval. */
public final class WorkingTime {
    private WorkingTime() {}
    public enum Status { CALCULATED, UNRESOLVED }
    public enum Reason { CALENDAR_MISSING, DURATION_MISSING, START_MISSING, COVERAGE_INCOMPLETE, START_OUTSIDE_COVERAGE, INSUFFICIENT_WORKING_TIME }

    /** complete describes coverage only. The caller must separately verify provenance and publication. */
    public record CalendarData(String id, long version, Interval coverage, List<Interval> workingWindows, boolean complete) {
        public CalendarData {
            if (id == null || id.isBlank() || version < 1) throw new IllegalArgumentException("Explicit calendar identity/version required");
            Objects.requireNonNull(coverage, "coverage");
            workingWindows = IntervalSets.union(workingWindows);
            for (var window : workingWindows) {
                if (window.start().isBefore(coverage.start()) || window.end().isAfter(coverage.end()))
                    throw new IllegalArgumentException("Working windows must be within declared coverage");
            }
        }
    }

    public record Result(Status status, Instant deadline, Reason reason, String calendarId, Long calendarVersion,
                         List<Interval> consumed, Duration remaining) {
        public Result {
            Objects.requireNonNull(status, "status");
            consumed = List.copyOf(consumed);
            if ((calendarId == null) != (calendarVersion == null)
                    || calendarId != null && (calendarId.isBlank() || calendarVersion < 1))
                throw new IllegalArgumentException("Calendar provenance must be complete or explicitly absent");
            if (remaining != null && remaining.isNegative()) throw new IllegalArgumentException("Remaining time must not be negative");
            if (status == Status.CALCULATED && (calendarId == null || deadline == null || reason != null || !Duration.ZERO.equals(remaining)))
                throw new IllegalArgumentException("Calculated results require an exact deadline and no remainder");
            if (status == Status.UNRESOLVED && (deadline != null || reason == null))
                throw new IllegalArgumentException("Unresolved results must not provide a deadline");
        }
    }

    /** Zero is an explicit mathematical input, never substituted for missing SLA duration. */
    public static Result advance(CalendarData calendar, Instant start, Duration required) {
        if (required != null && required.isNegative()) throw new IllegalArgumentException("Duration must not be negative");
        if (calendar == null) return pending(null, Reason.CALENDAR_MISSING, List.of(), required);
        if (required == null) return pending(calendar, Reason.DURATION_MISSING, List.of(), null);
        if (start == null) return pending(calendar, Reason.START_MISSING, List.of(), required);
        if (!calendar.complete()) return pending(calendar, Reason.COVERAGE_INCOMPLETE, List.of(), required);
        if (start.isBefore(calendar.coverage().start()) || !start.isBefore(calendar.coverage().end()))
            return pending(calendar, Reason.START_OUTSIDE_COVERAGE, List.of(), required);
        if (required.isZero()) return calculated(calendar, start, List.of());
        Duration remaining = required;
        var consumed = new ArrayList<Interval>();
        for (var window : calendar.workingWindows()) {
            if (!window.end().isAfter(start)) continue;
            Instant cursor = window.start().isAfter(start) ? window.start() : start;
            Duration available = Duration.between(cursor, window.end());
            if (remaining.compareTo(available) <= 0) {
                Instant deadline = cursor.plus(remaining);
                consumed.add(new Interval(cursor, deadline));
                return calculated(calendar, deadline, consumed);
            }
            consumed.add(new Interval(cursor, window.end()));
            remaining = remaining.minus(available);
        }
        return pending(calendar, Reason.INSUFFICIENT_WORKING_TIME, consumed, remaining);
    }

    private static Result calculated(CalendarData calendar, Instant deadline, List<Interval> consumed) {
        return new Result(Status.CALCULATED, deadline, null, calendar.id(), calendar.version(), consumed, Duration.ZERO);
    }
    private static Result pending(CalendarData calendar, Reason reason, List<Interval> consumed, Duration remaining) {
        return new Result(Status.UNRESOLVED, null, reason, calendar == null ? null : calendar.id(), calendar == null ? null : calendar.version(), consumed, remaining);
    }
}
