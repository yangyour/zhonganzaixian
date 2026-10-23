package cn.mop.api;

import cn.mop.domain.notification.TicketNotificationPlanner;
import cn.mop.domain.ticket.MonitoringRecurrenceClock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringRecurrenceClockTest {
    private static final UUID EVENT = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Test
    void eachThresholdRetainsCanonicalBudgetAndIndependentOnsetIdentity() {
        for (int percent : List.of(50, 80, 100)) {
            var clock = new MonitoringRecurrenceClock(percent, EVENT, Integer.MAX_VALUE);
            assertEquals(clock, MonitoringRecurrenceClock.parse(clock.key()));
            assertEquals("recovery:" + percent, clock.canonicalKey());
            assertTrue(clock.key().length() <= 80);
            assertNotEquals(clock.key(), new MonitoringRecurrenceClock(percent, EVENT, 1).key());
        }
    }

    @Test
    void legacyKeysAreSeparateAndMalformedCycleKeysAreRejected() {
        assertNull(MonitoringRecurrenceClock.parse("recovery:50"));
        assertNull(MonitoringRecurrenceClock.parse("response:100"));
        for (String key : List.of("response:50:monitor:" + EVENT + ":1",
                "recovery:30:monitor:" + EVENT + ":1", "recovery:50:monitor:" + EVENT + ":0",
                "recovery:50:monitor:" + EVENT + ":01", "recovery:050:monitor:" + EVENT + ":1",
                "recovery:50:monitor:bad:1", "recovery:50:monitor:" + EVENT + ":2147483648")) {
            assertThrows(IllegalArgumentException.class, () -> MonitoringRecurrenceClock.parse(key), key);
        }
    }

    @Test
    void recurrencePlansAnInternalNoticeWithoutClaimingCustomerReopenOrExternalDelivery() {
        var input = new TicketNotificationPlanner.Input("ticket.monitoring_recurred", "TEST-1", "测试",
            "reporter", "engineer", List.of("engineer", "collaborator"), null, "P2");
        var plan = TicketNotificationPlanner.plan(input);
        assertEquals("MONITORING_RECURRED", plan.phase());
        assertEquals(List.of("engineer", "collaborator"), plan.recipients().stream()
            .map(TicketNotificationPlanner.Recipient::userId).toList());
        assertTrue(plan.recipients().stream().allMatch(r -> r.audience() == TicketNotificationPlanner.Audience.INTERNAL));
        assertTrue(plan.pendingReasons().isEmpty());
    }

    @Test
    void unassignedRecurrenceKeepsMissingQueueExplicit() {
        var input = new TicketNotificationPlanner.Input("ticket.monitoring_recurred", "TEST-1", "测试",
            "reporter", null, List.of(), null, "P2");
        var plan = TicketNotificationPlanner.plan(input);
        assertTrue(plan.recipients().isEmpty());
        assertEquals(List.of(TicketNotificationPlanner.PENDING_QUEUE), plan.pendingReasons());
    }
}
