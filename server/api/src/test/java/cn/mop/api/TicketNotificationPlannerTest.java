package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.TicketNotificationPlanner;
import cn.mop.domain.notification.TicketNotificationPlanner.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TicketNotificationPlannerTest {
    static final String REPORTER = "r", ASSIGNEE = "a", PREVIOUS = "p", HELPER = "h";
    static Input input(String type, String assignee) { return new Input(type, "R-1", "核心交换机端口异常", REPORTER, assignee, List.of(HELPER), PREVIOUS, "P2"); }
    static Map<String, Audience> audiences(Plan plan) { var m = new LinkedHashMap<String, Audience>(); plan.recipients().forEach(r -> m.put(r.userId(), r.audience())); return m; }

    @Test void customerProgressFactsGoOnlyToReporterAndNeverNameEngineer() {
        for (String type : List.of("ticket.accepted", "ticket.started", "ticket.resolved", "ticket.confirmation_reminder", "ticket.cancelled")) {
            var plan = TicketNotificationPlanner.plan(input(type, ASSIGNEE));
            assertEquals(Map.of(REPORTER, Audience.CUSTOMER), audiences(plan), type);
            assertEquals(RecipientStatus.COMPLETE, plan.status(), type);
            var r = plan.recipients().get(0); assertFalse(r.body().contains(ASSIGNEE)); assertTrue(r.title().startsWith("[R-1] "));
        }
    }
    @Test void reassignmentNotifiesNewAndPreviousOwnerButNotCustomer() {
        var plan = TicketNotificationPlanner.plan(input("ticket.reassigned", ASSIGNEE));
        assertEquals(Map.of(ASSIGNEE, Audience.INTERNAL, PREVIOUS, Audience.INTERNAL), audiences(plan));
        assertTrue(plan.recipients().get(0).body().contains("SLA截止时间不变"));
    }
    @Test void reopenGoesToAssigneeAndCollaborators() {
        var plan = TicketNotificationPlanner.plan(input("ticket.reopened", ASSIGNEE));
        assertEquals(List.of(ASSIGNEE, HELPER), plan.recipients().stream().map(Recipient::userId).toList());
    }
    @Test void autoCloseNotifiesBothSides() {
        assertEquals(Map.of(REPORTER, Audience.CUSTOMER, ASSIGNEE, Audience.INTERNAL), audiences(TicketNotificationPlanner.plan(input("ticket.auto_closed", ASSIGNEE))));
    }
    @Test void slaReminderTargetsAssigneeAndBreachEscalationStaysPendingQ15() {
        var half = TicketNotificationPlanner.plan(input("ticket.sla_50", ASSIGNEE));
        assertEquals(RecipientStatus.COMPLETE, half.status()); assertEquals("SLA_50", half.phase()); assertTrue(half.recipients().get(0).title().contains("50%"));
        var breach = TicketNotificationPlanner.plan(input("ticket.sla_100", ASSIGNEE));
        assertEquals(RecipientStatus.PARTIAL, breach.status()); assertEquals(List.of(TicketNotificationPlanner.PENDING_ESCALATION), breach.pendingReasons());
        var unassigned = TicketNotificationPlanner.plan(input("ticket.sla_80", null));
        assertEquals(RecipientStatus.PENDING_RULE, unassigned.status()); assertEquals(List.of(TicketNotificationPlanner.PENDING_QUEUE), unassigned.pendingReasons());
    }
    @Test void unconfirmedRolesAndUnknownEventsAreRecordedNotGuessed() {
        var manager = TicketNotificationPlanner.plan(input("ticket.manager_review_required", ASSIGNEE));
        assertTrue(manager.recipients().isEmpty()); assertEquals(List.of(TicketNotificationPlanner.PENDING_MANAGER), manager.pendingReasons());
        var unknown = TicketNotificationPlanner.plan(input("ticket.future_event", ASSIGNEE));
        assertEquals(RecipientStatus.PENDING_RULE, unknown.status()); assertEquals(List.of(TicketNotificationPlanner.PENDING_UNMAPPED), unknown.pendingReasons());
        var opened = TicketNotificationPlanner.plan(input("ticket.opened", null));
        assertEquals(RecipientStatus.PARTIAL, opened.status()); assertEquals(Map.of(REPORTER, Audience.CUSTOMER), audiences(opened));
    }
    @Test void inactiveRecipientsAreDroppedWithReasonAndNeverReplaced() {
        var plan = TicketNotificationPlanner.plan(input("ticket.reassigned", ASSIGNEE)).retainActive(Set.of(ASSIGNEE));
        assertEquals(List.of(ASSIGNEE), plan.recipients().stream().map(Recipient::userId).toList());
        assertEquals(List.of(TicketNotificationPlanner.PENDING_INACTIVE), plan.pendingReasons()); assertEquals(RecipientStatus.PARTIAL, plan.status());
        var none = TicketNotificationPlanner.plan(input("ticket.closed", ASSIGNEE)).retainActive(Set.of());
        assertEquals(RecipientStatus.PENDING_RULE, none.status());
    }
    @Test void externalDispatchIsAlwaysBlockedByUnconfirmedMatrixAndCredentials() {
        assertEquals(List.of("Q14_CHANNEL_MATRIX", "Q18_PROVIDER_CREDENTIALS"), TicketNotificationPlanner.EXTERNAL_BLOCKERS);
    }
    @Test void longTitlesAreBoundedByCodePointsAndInputIsValidated() {
        var plan = TicketNotificationPlanner.plan(new Input("ticket.accepted", "R-2", "测".repeat(200), REPORTER, ASSIGNEE, null, null, null));
        String title = plan.recipients().get(0).title(); assertTrue(title.codePointCount(0, title.length()) <= 160); assertTrue(title.contains("…"));
        assertThrows(DomainException.class, () -> new Input("TICKET.X", "R", "t", REPORTER, null, List.of(), null, null));
        assertThrows(DomainException.class, () -> new Input("ticket.accepted", "R", "t", REPORTER, null, List.of(), null, "P9"));
        assertThrows(DomainException.class, () -> TicketNotificationPlanner.phase("bad"));
    }
}
