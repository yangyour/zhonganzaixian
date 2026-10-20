package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventPipeline;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure boundary unit tests for D15 MonitoringEventPipeline (M4-7, M4-8, M4-9, M4-10, M4-11, M5-4).
 */
class MonitoringEventPipelineTest {
    private static final String TENANT = "55000000-0000-4000-8000-000000000001";
    private static final String ASSET = "66000000-0000-4000-8000-000000000001";

    @Test void deduplicationWindowMergesWithin300SecondsAndSplitsAfter300Seconds_M4_8() {
        Instant t0 = Instant.parse("2026-10-18T10:00:00Z");
        String fp1 = MonitoringEventPipeline.fingerprint(TENANT, ASSET, "cpu.high");
        String fp2 = MonitoringEventPipeline.fingerprint(TENANT, ASSET, "CPU.HIGH");
        assertEquals(fp1, fp2);
        assertTrue(MonitoringEventPipeline.shouldMergeWithinWindow(t0, t0.plusSeconds(300), HandlingState.NEW));
        assertFalse(MonitoringEventPipeline.shouldMergeWithinWindow(t0, t0.plusSeconds(301), HandlingState.NEW));
        assertFalse(MonitoringEventPipeline.shouldMergeWithinWindow(t0, t0.plusSeconds(10), HandlingState.CLOSED));
    }

    @Test void threeFlipsWithinTenMinutesSilencesForFiveMinutes_M4_7() {
        Instant t0 = Instant.parse("2026-10-18T10:00:00Z");
        FlapDecision flip1 = MonitoringEventPipeline.evaluateFlap(0, true, t0, t0.plusSeconds(60), null);
        assertEquals(1, flip1.flipCountInWindow());
        assertFalse(flip1.flapTriggered());

        FlapDecision flip2 = MonitoringEventPipeline.evaluateFlap(flip1.flipCountInWindow(), true, t0.plusSeconds(60), t0.plusSeconds(120), flip1.silencedUntil());
        assertEquals(2, flip2.flipCountInWindow());
        assertFalse(flip2.flapTriggered());

        FlapDecision flip3 = MonitoringEventPipeline.evaluateFlap(flip2.flipCountInWindow(), true, t0.plusSeconds(120), t0.plusSeconds(180), flip2.silencedUntil());
        assertEquals(3, flip3.flipCountInWindow());
        assertTrue(flip3.flapTriggered());
        assertEquals(t0.plusSeconds(180 + 300), flip3.silencedUntil());
    }

    @Test void dependencySuppressionMarksSlaUnknownAndMaintenanceSilences_M4_9_M4_10() {
        SuppressionDecision dep = MonitoringEventPipeline.evaluateSuppression(Severity.HIGH, false, "parent-core-sw-01", "夜间割接窗口");
        assertEquals(SuppressionState.DEPENDENCY_SUPPRESSED, dep.suppressionState());
        assertEquals(NotificationEmptyState.DEPENDENCY_SUPPRESSED_NO_NOTIFY, dep.notificationEmptyState());
        assertTrue(dep.slaUnknownMarked());

        SuppressionDecision maint = MonitoringEventPipeline.evaluateSuppression(Severity.HIGH, false, null, "夜间割接窗口");
        assertEquals(SuppressionState.MAINTENANCE_SILENCED, maint.suppressionState());
        assertEquals(NotificationEmptyState.MAINTENANCE_WINDOW_SUPPRESSED, maint.notificationEmptyState());
        assertFalse(maint.slaUnknownMarked());

        SuppressionDecision info = MonitoringEventPipeline.evaluateSuppression(Severity.INFO, false, null, null);
        assertEquals(SuppressionState.INFO_DEFAULT_SILENT, info.suppressionState());
        assertEquals(NotificationEmptyState.INFO_LEVEL_NO_NOTIFY, info.notificationEmptyState());
    }

    @Test void recoveryMovesToPendingReviewAndRequiresRootCauseNoteBeforeResolved_M4_11() {
        HandlingState afterRecover = MonitoringEventPipeline.nextHandlingStateOnSignal(HandlingState.IN_PROGRESS, MonitorState.RECOVERED);
        assertEquals(HandlingState.PENDING_REVIEW, afterRecover);

        assertEquals(422, assertThrows(DomainException.class, () ->
            MonitoringEventPipeline.transitionHandlingState(HandlingState.IN_PROGRESS, MonitorState.PROBLEM, "RESOLVE", "已排查主干光模块故障")).status);

        assertEquals(400, assertThrows(DomainException.class, () ->
            MonitoringEventPipeline.transitionHandlingState(HandlingState.PENDING_REVIEW, MonitorState.RECOVERED, "RESOLVE", "短")).status);

        HandlingState resolved = MonitoringEventPipeline.transitionHandlingState(
            HandlingState.PENDING_REVIEW, MonitorState.RECOVERED, "RESOLVE", "已核对主干光纤尾纤松动原因并完成紧固，观察网络恢复稳定"
        );
        assertEquals(HandlingState.RESOLVED, resolved);
        assertEquals(HandlingState.CLOSED,
            MonitoringEventPipeline.transitionHandlingState(resolved, MonitorState.RECOVERED, "CLOSE", null));
    }

    @Test void customerReviewNeedsTwentyCodePointsAndActualRecovery() {
        assertEquals(400, assertThrows(DomainException.class, () ->
            MonitoringEventPipeline.transitionHandlingState(HandlingState.PENDING_REVIEW, MonitorState.RECOVERED, "RESOLVE", "字".repeat(19))).status);
        assertEquals(400, assertThrows(DomainException.class, () ->
            MonitoringEventPipeline.transitionHandlingState(HandlingState.PENDING_REVIEW, MonitorState.RECOVERED, "RESOLVE", "😀".repeat(10))).status);
        assertEquals(HandlingState.RESOLVED, MonitoringEventPipeline.transitionHandlingState(
            HandlingState.PENDING_REVIEW, MonitorState.RECOVERED, "RESOLVE", "字".repeat(20)));
        for (String action : new String[]{"MARK_PENDING_REVIEW", "RESOLVE", "CLOSE"})
            assertEquals(422, assertThrows(DomainException.class, () ->
                MonitoringEventPipeline.transitionHandlingState(HandlingState.PENDING_REVIEW, MonitorState.PROBLEM, action, "字".repeat(20))).status);
    }

    @Test void autoTicketRetryBackoffFollows30s2m10mAndQuarantinesAtAttemptFive_M5_4() {
        Instant now = Instant.parse("2026-10-18T10:00:00Z");
        AutoTicketPlan a1 = MonitoringEventPipeline.evaluateAutoTicketAttempt(Severity.DISASTER, SuppressionState.NONE, 0, true, now);
        assertEquals(1, a1.attemptCount());
        assertEquals(AutoTicketStatus.RETRY_SCHEDULED, a1.status());
        assertEquals(now.plusSeconds(30), a1.nextRetryAt());

        AutoTicketPlan a2 = MonitoringEventPipeline.evaluateAutoTicketAttempt(Severity.DISASTER, SuppressionState.NONE, 1, true, now);
        assertEquals(2, a2.attemptCount());
        assertEquals(now.plusSeconds(120), a2.nextRetryAt());

        AutoTicketPlan a3 = MonitoringEventPipeline.evaluateAutoTicketAttempt(Severity.DISASTER, SuppressionState.NONE, 2, true, now);
        assertEquals(3, a3.attemptCount());
        assertEquals(now.plusSeconds(600), a3.nextRetryAt());

        AutoTicketPlan a5 = MonitoringEventPipeline.evaluateAutoTicketAttempt(Severity.DISASTER, SuppressionState.NONE, 4, true, now);
        assertEquals(5, a5.attemptCount());
        assertEquals(AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED, a5.status());
        assertNull(a5.nextRetryAt());
    }
}
