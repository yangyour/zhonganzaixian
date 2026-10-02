package cn.mop.api;

import cn.mop.domain.time.IntervalSets;
import cn.mop.domain.time.IntervalSets.Interval;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Pure arithmetic and independent discrete oracle; not a business availability calculation. */
class IntervalSetsTest {
    private static Interval interval(long start, long end) { return new Interval(Instant.EPOCH.plusSeconds(start), Instant.EPOCH.plusSeconds(end)); }

    @Test void knownEmptySetsHaveNoDuration() {
        assertEquals(List.of(), IntervalSets.union(List.of()));
        assertEquals(Duration.ZERO, IntervalSets.duration(List.of()));
    }
    @Test void unknownInputsAndInvalidBoundsAreNotSilentlyEmpty() {
        assertThrows(NullPointerException.class, () -> IntervalSets.union(null));
        assertThrows(NullPointerException.class, () -> IntervalSets.union(Arrays.asList((Interval) null)));
        assertThrows(NullPointerException.class, () -> new Interval(null, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> interval(1, 1));
        assertThrows(IllegalArgumentException.class, () -> interval(2, 1));
        assertThrows(NullPointerException.class, () -> IntervalSets.clip(List.of(), null));
    }
    @Test void unorderedOverlappingNestedDuplicateAndAdjacentInputsCoalesce() {
        assertEquals(List.of(interval(0, 12)), IntervalSets.union(List.of(interval(5, 10), interval(0, 6), interval(2, 3), interval(0, 6), interval(10, 12))));
    }
    @Test void disjointAndNegativeInstantRangesStaySeparate() {
        assertEquals(List.of(interval(-10, -5), interval(0, 2), interval(4, 8)), IntervalSets.union(List.of(interval(4, 8), interval(-10, -5), interval(0, 2))));
    }
    @Test void totalDurationDoesNotDoubleCountOverlap() {
        assertEquals(Duration.ofSeconds(15), IntervalSets.duration(List.of(interval(0, 10), interval(5, 15), interval(7, 9), interval(0, 10))));
    }
    @Test void resultsAreImmutableAndInputOrderIsPreserved() {
        var input = new ArrayList<>(List.of(interval(5, 10), interval(0, 7))); var original = List.copyOf(input);
        var result = IntervalSets.union(input);
        assertEquals(original, input); input.clear(); assertEquals(List.of(interval(0, 10)), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(interval(20, 30)));
    }
    @Test void intersectionUsesHalfOpenBoundaries() {
        assertEquals(List.of(), IntervalSets.intersection(List.of(interval(0, 5)), List.of(interval(5, 10))));
        assertEquals(List.of(interval(2, 5), interval(8, 9)), IntervalSets.intersection(List.of(interval(0, 5), interval(8, 12)), List.of(interval(2, 9))));
    }
    @Test void intersectionNormalizesBothOperands() {
        assertEquals(List.of(interval(1, 8)), IntervalSets.intersection(List.of(interval(0, 5), interval(4, 10)), List.of(interval(1, 3), interval(3, 8))));
    }
    @Test void subtractionSplitsAndClipsCuts() {
        assertEquals(List.of(interval(0, 2), interval(4, 6), interval(9, 10)), IntervalSets.subtract(List.of(interval(0, 10)), List.of(interval(2, 4), interval(6, 8), interval(7, 9))));
    }
    @Test void exclusionMayCoverMultipleDisjointInputRanges() {
        assertEquals(List.of(interval(0, 1), interval(9, 10)), IntervalSets.subtract(List.of(interval(0, 2), interval(4, 6), interval(8, 10)), List.of(interval(1, 9))));
    }
    @Test void subtractionHandlesEmptyCompleteAndTouchingExclusions() {
        var input = List.of(interval(0, 5));
        assertEquals(input, IntervalSets.subtract(input, List.of()));
        assertEquals(List.of(), IntervalSets.subtract(input, List.of(interval(-1, 6))));
        assertEquals(input, IntervalSets.subtract(input, List.of(interval(-3, 0), interval(5, 9))));
    }
    @Test void clippingDoesNotMoveOriginalBoundaries() {
        var input = List.of(interval(-5, 5), interval(9, 15));
        assertEquals(List.of(interval(0, 5), interval(9, 10)), IntervalSets.clip(input, interval(0, 10)));
        assertEquals(List.of(interval(-5, 5), interval(9, 15)), input);
    }
    @Test void nanosecondPrecisionSurvivesSetOperations() {
        var a = new Interval(Instant.EPOCH, Instant.EPOCH.plusNanos(3));
        var b = new Interval(Instant.EPOCH.plusNanos(1), Instant.EPOCH.plusNanos(2));
        assertEquals(Duration.ofNanos(3), IntervalSets.duration(List.of(a, b)));
        assertEquals(Duration.ofNanos(2), IntervalSets.duration(IntervalSets.subtract(List.of(a), List.of(b))));
    }
    @Test void monthLeapDayAndYearBoundariesUseAbsoluteInstants() {
        var leap = new Interval(Instant.parse("2024-02-28T00:00:00Z"), Instant.parse("2024-03-01T00:00:00Z"));
        assertEquals(Duration.ofDays(2), IntervalSets.duration(List.of(leap)));
        var year = new Interval(Instant.parse("2026-12-31T23:59:59Z"), Instant.parse("2027-01-01T00:00:01Z"));
        assertEquals(Duration.ofSeconds(2), IntervalSets.duration(List.of(year)));
    }
    @Test void differentOffsetRepresentationsDoNotCreateDifferentIntervals() {
        Instant local = OffsetDateTime.parse("2026-10-01T23:50:00+08:00").toInstant();
        Instant utc = Instant.parse("2026-10-01T15:50:00Z");
        assertEquals(List.of(new Interval(utc, utc.plusSeconds(600))), IntervalSets.union(List.of(new Interval(local, local.plusSeconds(600)), new Interval(utc, utc.plusSeconds(600)))));
    }
    @Test void seededSetOperationsMatchAnIndependentDiscreteOracle() {
        var random = new Random(20261002);
        for (int run = 0; run < 500; run++) {
            var left = samples(random); var right = samples(random);
            var union = IntervalSets.union(left); var intersection = IntervalSets.intersection(left, right); var difference = IntervalSets.subtract(left, right);
            int duration = 0;
            for (int second = -10; second < 10; second++) {
                boolean a = includes(left, second), b = includes(right, second);
                assertEquals(a, includes(union, second));
                assertEquals(a && b, includes(intersection, second));
                assertEquals(a && !b, includes(difference, second));
                if (a) duration++;
            }
            assertEquals(Duration.ofSeconds(duration), IntervalSets.duration(left));
            for (var result : List.of(union, intersection, difference)) for (int index = 1; index < result.size(); index++)
                assertTrue(result.get(index - 1).end().isBefore(result.get(index).start()));
        }
    }
    private static List<Interval> samples(Random random) {
        var result = new ArrayList<Interval>();
        for (int count = random.nextInt(10); count > 0; count--) {
            int a = random.nextInt(21) - 10, b = random.nextInt(21) - 10;
            if (a != b) result.add(interval(Math.min(a, b), Math.max(a, b)));
        }
        return result;
    }
    private static boolean includes(List<Interval> values, int second) {
        Instant point = Instant.EPOCH.plusSeconds(second);
        return values.stream().anyMatch(value -> !point.isBefore(value.start()) && point.isBefore(value.end()));
    }
}
