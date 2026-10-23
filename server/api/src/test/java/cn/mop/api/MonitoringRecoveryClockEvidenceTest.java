package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.TicketSlaClockEvaluation;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.WorkflowLifecycle;
import cn.mop.domain.ticket.WorkflowLifecycle.Action;
import cn.mop.domain.ticket.WorkflowLifecycle.Command;
import cn.mop.domain.ticket.WorkflowLifecycle.Operator;
import cn.mop.domain.ticket.WorkflowLifecycle.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The display and worker must consume the same t2 validity rules before any durable clock receipt. */
class MonitoringRecoveryClockEvidenceTest {
    private static final Instant START = Instant.parse("2026-10-23T08:00:00Z");
    private static final Instant OBSERVED = START.plusSeconds(7200);

    private static Snapshot opened() {
        return WorkflowLifecycle.open(TicketSlaSnapshot.freeze("P2", Tier.STANDARD, START,
            Duration.ofMinutes(30), Duration.ofHours(1), null, 48L, null, List.of(), Map.of()), START);
    }

    private static void rejected(Snapshot workflow, boolean linked, Instant recovery) {
        var read = assertThrows(DomainException.class,
            () -> TicketSlaClockEvaluation.evaluate(workflow, Tier.STANDARD, OBSERVED, linked, recovery));
        var worker = assertThrows(DomainException.class,
            () -> WorkflowLifecycle.dueNotices(workflow, OBSERVED, linked, recovery));
        assertEquals(503, read.status);
        assertEquals(503, worker.status);
        assertEquals("RECOVERY_EVIDENCE_INVALID", read.code);
        assertEquals(read.code, worker.code);
    }

    @Test
    void futureRecoveryIsRejectedByBothReadAndWorker() {
        rejected(opened(), true, OBSERVED.plusMillis(1));
    }

    @Test
    void preOpeningRecoveryCannotSilenceEveryReminder() {
        rejected(opened(), true, START.minusMillis(1));
    }

    @Test
    void orphanRecoveryWithoutMonitoringLinkIsRejected() {
        rejected(opened(), false, START.plusSeconds(300));
    }

    @Test
    void subMillisecondRecoveryIsNotSilentlyRounded() {
        rejected(opened(), true, START.plusSeconds(300).plusNanos(1));
    }

    @Test
    void unsupportedTimestampYearsAreNotAccepted() {
        rejected(opened(), true, Instant.parse("0000-12-31T23:59:59Z"));
        rejected(opened(), true, Instant.parse("+10000-01-01T00:00:00Z"));
    }

    @Test
    void cancelledWorkflowDoesNotTurnInvalidEvidenceIntoSuccessfulEmptyWork() {
        var cancelled = WorkflowLifecycle.apply(opened(),
            new Command(Action.CANCEL, 1, null, "明确取消本次工单", null, false),
            new Operator("administrator", true, true, false), START.plusSeconds(1)).snapshot();
        rejected(cancelled, true, START.minusMillis(1));
    }

    @Test
    void recoveryExactlyAtStartIsValidAndSuppressesRecoveryThresholds() {
        var workflow = opened();
        var read = TicketSlaClockEvaluation.evaluate(workflow, Tier.STANDARD, OBSERVED, true, START);
        assertEquals(START, read.recoveryClock().completedAt());
        assertEquals("MET", read.recoveryClock().status());
        assertTrue(WorkflowLifecycle.dueNotices(workflow, OBSERVED, true, START).stream()
            .noneMatch(notice -> notice.key().startsWith("recovery:")));
    }

    @Test
    void recoveryExactlyAtObservationKeepsAlreadyDueReminderFacts() {
        var workflow = opened();
        var read = TicketSlaClockEvaluation.evaluate(workflow, Tier.STANDARD, OBSERVED, true, OBSERVED);
        assertEquals("BREACHED", read.recoveryClock().status());
        assertEquals(3, WorkflowLifecycle.dueNotices(workflow, OBSERVED, true, OBSERVED).stream()
            .filter(notice -> notice.key().startsWith("recovery:")).count());
    }

    @Test
    void recurrenceMissingRecoveryKeepsOriginalDeadlineAndResumesDueFacts() {
        var workflow = opened();
        var recovered = TicketSlaClockEvaluation.evaluate(workflow, Tier.STANDARD, OBSERVED,
            true, START.plusSeconds(600));
        var recurring = TicketSlaClockEvaluation.evaluate(workflow, Tier.STANDARD, OBSERVED, true, null);
        assertEquals(recovered.recoveryClock().dueAt(), recurring.recoveryClock().dueAt());
        assertEquals(START.plusSeconds(3600), recurring.recoveryClock().dueAt());
        assertNull(recurring.recoveryClock().completedAt());
        assertEquals("BREACHED", recurring.recoveryClock().status());
        assertEquals(3, WorkflowLifecycle.dueNotices(workflow, OBSERVED, true, null).stream()
            .filter(notice -> notice.key().startsWith("recovery:")).count());
    }

    @Test
    void unlinkedTicketWithoutRecoveryRetainsExistingWorkflowClockSemantics() {
        var workflow = opened();
        assertEquals(TicketSlaClockEvaluation.evaluate(workflow, Tier.STANDARD, OBSERVED),
            TicketSlaClockEvaluation.evaluate(workflow, Tier.STANDARD, OBSERVED, false, null));
        assertEquals(WorkflowLifecycle.dueNotices(workflow, OBSERVED),
            WorkflowLifecycle.dueNotices(workflow, OBSERVED, false, null));
    }
}
