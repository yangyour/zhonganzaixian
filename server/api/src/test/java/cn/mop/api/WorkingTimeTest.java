package cn.mop.api;

import cn.mop.domain.time.IntervalSets;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime;
import cn.mop.domain.time.WorkingTime.CalendarData;
import cn.mop.domain.time.WorkingTime.Reason;
import cn.mop.domain.time.WorkingTime.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** All working hours below are synthetic inputs, never customer defaults or approved calendars. */
class WorkingTimeTest {
    private static Instant minute(long value) { return Instant.EPOCH.plusSeconds(value * 60); }
    private static Interval window(long from, long to) { return new Interval(minute(from), minute(to)); }
    private static CalendarData calendar(List<Interval> windows) { return new CalendarData("SYNTHETIC-ONLY", 3, window(0, 6000), windows, true); }
    record Case(String name, List<Interval> windows, long start, long duration, Long deadline) { @Override public String toString() { return name; } }
    static Stream<Case> scenarios() {
        return Stream.of(
            new Case("within one window", List.of(window(0, 60)), 0, 30, 30L),
            new Case("starts partway through window", List.of(window(0, 60)), 10, 30, 40L),
            new Case("exact closing boundary", List.of(window(0, 60)), 10, 50, 60L),
            new Case("start before opening", List.of(window(10, 60)), 0, 20, 30L),
            new Case("cross a break", List.of(window(0, 60), window(90, 150)), 30, 60, 120L),
            new Case("start inside break", List.of(window(0, 60), window(90, 150)), 70, 20, 110L),
            new Case("start exactly at prior close", List.of(window(0, 60), window(90, 150)), 60, 20, 110L),
            new Case("adjacent windows do not add pause", List.of(window(0, 30), window(30, 60)), 0, 60, 60L),
            new Case("overlap is not extra capacity", List.of(window(0, 60), window(30, 90)), 0, 90, 90L),
            new Case("duplicates are not extra capacity", List.of(window(0, 60), window(0, 60)), 0, 61, null),
            new Case("unordered windows", List.of(window(90, 150), window(0, 60)), 0, 90, 120L),
            new Case("nested windows", List.of(window(0, 60), window(10, 20)), 0, 60, 60L),
            new Case("overnight closed gap", List.of(window(0, 60), window(1440, 1500)), 30, 60, 1470L),
            new Case("explicit holiday gap", List.of(window(0, 60), window(4320, 4380)), 30, 60, 4350L),
            new Case("several breaks", List.of(window(0, 10), window(20, 30), window(40, 50)), 5, 20, 45L),
            new Case("exact final available minute", List.of(window(0, 10), window(20, 30)), 5, 15, 30L),
            new Case("one minute beyond known work", List.of(window(0, 10), window(20, 30)), 5, 16, null),
            new Case("known calendar has no work", List.of(), 0, 1, null),
            new Case("start after all work", List.of(window(0, 60)), 90, 1, null),
            new Case("explicit zero duration", List.of(), 10, 0, 10L)
        );
    }
    @ParameterizedTest(name = "{0}") @MethodSource("scenarios")
    void advancesOnlyWithinExplicitWorkingWindows(Case test) {
        var result = WorkingTime.advance(calendar(test.windows()), minute(test.start()), Duration.ofMinutes(test.duration()));
        assertEquals(test.deadline() == null ? Status.UNRESOLVED : Status.CALCULATED, result.status());
        assertEquals(test.deadline() == null ? null : minute(test.deadline()), result.deadline());
        if (test.deadline() == null) { assertEquals(Reason.INSUFFICIENT_WORKING_TIME, result.reason()); assertTrue(result.remaining().compareTo(Duration.ZERO) > 0); }
        else assertEquals(Duration.ZERO, result.remaining());
        assertEquals(Duration.ofMinutes(test.duration()), IntervalSets.duration(result.consumed()).plus(result.remaining()));
        assertEquals("SYNTHETIC-ONLY", result.calendarId()); assertEquals(3L, result.calendarVersion());
    }
    @Test void missingInputsNeverBecomeDefaults() {
        var known = calendar(List.of(window(0, 60)));
        assertEquals(Reason.CALENDAR_MISSING, WorkingTime.advance(null, minute(0), Duration.ofMinutes(1)).reason());
        var missingDuration = WorkingTime.advance(known, minute(0), null);
        assertEquals(Reason.DURATION_MISSING, missingDuration.reason()); assertNull(missingDuration.deadline()); assertNull(missingDuration.remaining());
        assertEquals(Reason.START_MISSING, WorkingTime.advance(known, null, Duration.ZERO).reason());
        assertThrows(IllegalArgumentException.class, () -> WorkingTime.advance(known, minute(0), Duration.ofNanos(-1)));
    }
    @Test void incompleteOrOutOfCoverageCalendarDoesNotReturnADeadline() {
        var incomplete = new CalendarData("SYNTHETIC-ONLY", 1, window(0, 60), List.of(window(0, 60)), false);
        assertEquals(Reason.COVERAGE_INCOMPLETE, WorkingTime.advance(incomplete, minute(0), Duration.ZERO).reason());
        for (Instant start : List.of(minute(-1), minute(6000))) {
            var result = WorkingTime.advance(calendar(List.of()), start, Duration.ZERO);
            assertEquals(Reason.START_OUTSIDE_COVERAGE, result.reason()); assertNull(result.deadline());
        }
    }
    @Test void calendarSnapshotAndConsumedWindowsAreImmutable() {
        var input = new ArrayList<>(List.of(window(0, 60))); var snapshot = calendar(input); input.clear();
        var result = WorkingTime.advance(snapshot, minute(0), Duration.ofMinutes(30));
        assertEquals(minute(30), result.deadline()); assertEquals(3L, result.calendarVersion());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.workingWindows().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.consumed().clear());
        assertThrows(IllegalArgumentException.class, () -> new CalendarData("SYNTHETIC-ONLY", 1, window(0, 10), List.of(window(0, 11)), true));
    }
    @Test void exactNanosecondsAndCalendarYearBoundaryArePreserved() {
        Instant start = Instant.parse("2026-12-31T23:59:59.999999999Z");
        var snapshot = new CalendarData("SYNTHETIC-ONLY", 1, new Interval(start, start.plusSeconds(2)), List.of(new Interval(start, start.plusSeconds(2))), true);
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), WorkingTime.advance(snapshot, start, Duration.ofNanos(1)).deadline());
    }
    @Test void shanghaiLocalBoundaryIsExplicitlyConvertedByTheCaller() {
        Instant start = OffsetDateTime.parse("2024-02-28T23:50:00+08:00").toInstant();
        Instant end = OffsetDateTime.parse("2024-03-01T00:10:00+08:00").toInstant();
        var snapshot = new CalendarData("SYNTHETIC-ONLY", 1, new Interval(start, end), List.of(new Interval(start, end)), true);
        assertEquals(end, WorkingTime.advance(snapshot, start, Duration.ofHours(24).plusMinutes(20)).deadline());
    }
    @Test void resultCannotClaimAnUnversionedDeadlineOrMixPendingAndCalculatedFields() {
        assertThrows(IllegalArgumentException.class, () -> new WorkingTime.Result(Status.CALCULATED, minute(1), null, null, null, List.of(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkingTime.Result(Status.CALCULATED, minute(1), null, "calendar", null, List.of(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkingTime.Result(Status.UNRESOLVED, minute(1), Reason.COVERAGE_INCOMPLETE, "calendar", 1L, List.of(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkingTime.Result(Status.UNRESOLVED, null, Reason.INSUFFICIENT_WORKING_TIME, "calendar", 1L, List.of(), Duration.ofNanos(-1)));
    }
}
