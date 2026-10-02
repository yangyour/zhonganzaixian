package cn.mop.domain.time;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exact half-open interval mathematics only. No availability, SLA, calendar or unknown-data policy.
 * Callers must retain source references and distinguish unavailable input from a known empty set.
 */
public final class IntervalSets {
    private IntervalSets() {}

    public record Interval(Instant start, Instant end) {
        public Interval {
            Objects.requireNonNull(start, "interval start");
            Objects.requireNonNull(end, "interval end");
            if (!start.isBefore(end)) throw new IllegalArgumentException("Intervals must be nonempty and ordered");
        }
    }

    /** Sorted, disjoint immutable intervals; overlaps, duplicates and adjacent ranges coalesce. */
    public static List<Interval> union(Collection<Interval> intervals) {
        var sorted = new ArrayList<>(Objects.requireNonNull(intervals, "known interval collection"));
        sorted.forEach(interval -> Objects.requireNonNull(interval, "known interval"));
        sorted.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));
        var result = new ArrayList<Interval>();
        for (var next : sorted) {
            if (result.isEmpty() || result.get(result.size() - 1).end().isBefore(next.start())) {
                result.add(next);
            } else {
                var previous = result.get(result.size() - 1);
                if (next.end().isAfter(previous.end()))
                    result.set(result.size() - 1, new Interval(previous.start(), next.end()));
            }
        }
        return List.copyOf(result);
    }

    public static List<Interval> intersection(Collection<Interval> left, Collection<Interval> right) {
        var a = union(left); var b = union(right); var result = new ArrayList<Interval>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            var one = a.get(i); var two = b.get(j);
            Instant start = later(one.start(), two.start()), end = earlier(one.end(), two.end());
            if (start.isBefore(end)) result.add(new Interval(start, end));
            if (one.end().compareTo(two.end()) <= 0) i++; else j++;
        }
        return List.copyOf(result);
    }

    /** Set subtraction; deciding what exclusions mean belongs to an approved business policy. */
    public static List<Interval> subtract(Collection<Interval> intervals, Collection<Interval> exclusions) {
        var bases = union(intervals); var cuts = union(exclusions); var result = new ArrayList<Interval>();
        int first = 0;
        for (var base : bases) {
            while (first < cuts.size() && !cuts.get(first).end().isAfter(base.start())) first++;
            Instant cursor = base.start();
            for (int index = first; index < cuts.size(); index++) {
                var cut = cuts.get(index);
                if (!cut.start().isBefore(base.end())) break;
                if (cut.start().isAfter(cursor)) result.add(new Interval(cursor, earlier(cut.start(), base.end())));
                cursor = later(cursor, cut.end());
                if (!cursor.isBefore(base.end())) break;
            }
            if (cursor.isBefore(base.end())) result.add(new Interval(cursor, base.end()));
        }
        return List.copyOf(result);
    }

    public static List<Interval> clip(Collection<Interval> intervals, Interval window) {
        return intersection(intervals, List.of(Objects.requireNonNull(window, "known window")));
    }

    /** Duration of the union, never a sum that counts overlap twice; nanoseconds remain exact. */
    public static Duration duration(Collection<Interval> intervals) {
        Duration total = Duration.ZERO;
        for (var interval : union(intervals)) total = total.plus(Duration.between(interval.start(), interval.end()));
        return total;
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
