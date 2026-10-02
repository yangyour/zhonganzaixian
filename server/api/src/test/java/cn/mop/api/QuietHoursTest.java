package cn.mop.api;

import cn.mop.domain.notification.QuietHours;
import cn.mop.domain.notification.QuietHours.Policy;
import cn.mop.domain.notification.QuietHours.Status;
import cn.mop.domain.notification.QuietHours.Reason;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Pure clock inputs only; no provider, tenant store or dispatch is exercised. */
class QuietHoursTest {
    private Policy window(String start, String end, String zone) {
        return new Policy("synthetic-policy-v1", true, ZoneId.of(zone), LocalTime.parse(start), LocalTime.parse(end));
    }

    @ParameterizedTest
    @CsvSource({"08:59:59.999999999,NOT_MUTED", "09:00,MUTED", "12:00,MUTED", "16:59:59.999999999,MUTED", "17:00,NOT_MUTED"})
    void sameDayUsesInclusiveStartExclusiveEnd(String time, Status expected) {
        var result = QuietHours.evaluate(false, window("09:00", "17:00", "UTC"), Instant.parse("2026-10-02T" + time + (time.length()==5 ? ":00" : "") + "Z"));
        assertEquals(expected, result.status());
        assertEquals(LocalTime.parse(time), result.localTime());
        assertEquals("synthetic-policy-v1", result.policyVersion());
    }

    @ParameterizedTest
    @CsvSource({"21:59:59,NOT_MUTED", "22:00:00,MUTED", "23:59:59,MUTED", "00:00:00,MUTED", "06:59:59,MUTED", "07:00:00,NOT_MUTED"})
    void midnightWindowUsesLocalCalendar(String time, Status expected) {
        assertEquals(expected, QuietHours.evaluate(false, window("22:00", "07:00", "UTC"), Instant.parse("2026-10-02T"+time+"Z")).status());
    }

    @Test void explicitZoneIsUsedNotServerDefault() {
        var result = QuietHours.evaluate(false, window("22:00", "07:00", "Asia/Shanghai"), Instant.parse("2026-10-02T14:00:00Z"));
        assertEquals(Status.MUTED, result.status());
        assertEquals(LocalTime.of(22,0), result.localTime());
    }

    @Test void p1BypassesOnlyQuietFilterEvenWithUnknownPolicy() {
        var result = QuietHours.evaluate(true, null, null);
        assertEquals(Status.NOT_MUTED, result.status());
        assertEquals(Reason.P1_BYPASS, result.reason());
        assertNull(result.policyVersion());
    }

    @Test void unknownPriorityDoesNotBecomeNonP1() {
        assertEquals(Reason.PRIORITY_UNKNOWN, QuietHours.evaluate(null, window("22:00", "07:00", "UTC"), Instant.EPOCH).reason());
    }

    @Test void incompletePolicyNeverMeansDisabled() {
        assertEquals(Status.UNRESOLVED, QuietHours.evaluate(false, null, Instant.EPOCH).status());
        for (Policy policy : List.of(new Policy(null, true, ZoneId.of("UTC"), LocalTime.NOON, LocalTime.MIDNIGHT),
                new Policy(" ", false, null, null, null), new Policy("v1", null, null, null, null),
                new Policy("v1", true, null, LocalTime.NOON, LocalTime.MIDNIGHT),
                new Policy("v1", true, ZoneId.of("UTC"), null, LocalTime.MIDNIGHT),
                new Policy("v1", true, ZoneId.of("UTC"), LocalTime.NOON, null))) {
            assertEquals(Reason.POLICY_UNKNOWN, QuietHours.evaluate(false, policy, Instant.EPOCH).reason());
        }
    }

    @Test void explicitDisableNeedsNoWindowOrClock() {
        var result = QuietHours.evaluate(false, new Policy("v1", false, null, null, null), null);
        assertEquals(Status.NOT_MUTED, result.status());
        assertEquals(Reason.DISABLED, result.reason());
    }

    @Test void missingClockIsUnresolvedNotCurrentTime() {
        assertEquals(Reason.INSTANT_UNKNOWN, QuietHours.evaluate(false, window("22:00", "07:00", "UTC"), null).reason());
    }

    @Test void equalBoundariesRejectAmbiguity() {
        assertThrows(IllegalArgumentException.class, () -> window("00:00", "00:00", "UTC"));
    }

    @Test void bothAutumnOverlapInstantsUseTheSameLocalWindow() {
        var policy = window("01:00", "02:00", "America/New_York");
        for (String at : List.of("2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z")) {
            var result = QuietHours.evaluate(false, policy, Instant.parse(at));
            assertEquals(Status.MUTED, result.status());
            assertEquals(LocalTime.of(1,30), result.localTime());
        }
    }

    @Test void springGapDoesNotInventNonexistentLocalTime() {
        var policy = window("02:00", "03:00", "America/New_York");
        assertEquals(Status.NOT_MUTED, QuietHours.evaluate(false, policy, Instant.parse("2026-03-08T06:59:59Z")).status());
        assertEquals(Status.NOT_MUTED, QuietHours.evaluate(false, policy, Instant.parse("2026-03-08T07:00:00Z")).status());
    }
}
