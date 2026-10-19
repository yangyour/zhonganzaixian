package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure domain pipeline for D15 Monitoring Event Center (M1-2, M4-7, M4-8, M4-9, M4-10, M4-11, M4-14, M5-4).
 *
 * Baseline constants from contracts/config-baseline.json:
 * - event.merge_window_sec = 300
 * - event.flap_window_sec = 600
 * - event.flap_flip_count = 3
 * - event.flap_silence_sec = 300
 * - ticket.default_dedupe_window_sec = 1800
 * - ticket.autocreate_retry_max = 5 (backoff: 30s, 120s, 600s, 600s, 600s)
 *
 * Pending customer rules remain explicit status markers:
 * - Q05: availability.unknown_policy
 * - Q14: notification.severity_priority_mapping
 * - Q29: event.review_transition_policy
 */
public final class MonitoringEventPipeline {
    public static final long MERGE_WINDOW_SEC = 300L;
    public static final long FLAP_WINDOW_SEC = 600L;
    public static final int FLAP_FLIP_THRESHOLD = 3;
    public static final long FLAP_SILENCE_SEC = 300L;
    public static final long TICKET_DEDUPE_WINDOW_SEC = 1800L;
    public static final int AUTOCREATE_RETRY_MAX = 5;
    public static final List<Long> AUTOCREATE_BACKOFF_SEC = List.of(30L, 120L, 600L, 600L, 600L);

    private MonitoringEventPipeline() {}

    public enum Severity {
        INFO(1, null),
        WARNING(2, "P3"),
        AVERAGE(3, "P3"),
        HIGH(4, "P2"),
        DISASTER(5, "P1");

        private final int rank;
        private final String candidatePriority;

        Severity(int rank, String candidatePriority) {
            this.rank = rank;
            this.candidatePriority = candidatePriority;
        }

        public int rank() { return rank; }
        public String candidatePriority() { return candidatePriority; }
        public boolean bypassesAggregationWait() { return this == HIGH || this == DISASTER; }
    }

    public enum MonitorState { PROBLEM, RECOVERED }

    public enum HandlingState {
        NEW, ACKNOWLEDGED, IN_PROGRESS, PENDING_REVIEW, RESOLVED, CLOSED
    }

    public enum SuppressionState {
        NONE, FLAP_SILENCED, DEPENDENCY_SUPPRESSED, MAINTENANCE_SILENCED, INFO_DEFAULT_SILENT
    }

    public enum NotificationEmptyState {
        NORMAL_DISPATCH_ELIGIBLE("符合派发条件（外部渠道待Q14/Q18配置）"),
        DEPENDENCY_SUPPRESSED_NO_NOTIFY("依赖抑制：未发送通知"),
        MAINTENANCE_WINDOW_SUPPRESSED("维护窗口：通知已抑制"),
        FLAP_SILENCED_NO_NOTIFY("抖动静默：5分钟内不再重复通知"),
        INFO_LEVEL_NO_NOTIFY("无通知记录（信息级默认不通知）");

        private final String label;
        NotificationEmptyState(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum NoiseLayer {
        RAW_INTAKE, MERGE, FLAP, DEPENDENCY, MAINTENANCE, EFFECTIVE_ALERT
    }

    public enum AutoTicketStatus {
        SUCCEEDED, RETRY_SCHEDULED, QUARANTINED_MANUAL_REQUIRED, MANUAL_CREATED, SUPPRESSED_SKIPPED
    }

    public static String fingerprint(String tenantId, String assetId, String ruleCode) {
        if (tenantId == null || assetId == null || ruleCode == null || ruleCode.isBlank())
            throw new DomainException(400, "VALIDATION_ERROR", "事件指纹计算缺少租户、资产或规则编码");
        return "FP_V1|" + tenantId.trim().toLowerCase(Locale.ROOT)
            + "|" + assetId.trim().toLowerCase(Locale.ROOT)
            + "|" + ruleCode.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean shouldMergeWithinWindow(Instant existingLastOccurredAt, Instant incomingOccurredAt, HandlingState existingHandlingState) {
        if (existingLastOccurredAt == null || incomingOccurredAt == null || existingHandlingState == HandlingState.CLOSED) {
            return false;
        }
        long deltaSec = Math.abs(Duration.between(existingLastOccurredAt, incomingOccurredAt).getSeconds());
        return deltaSec <= MERGE_WINDOW_SEC;
    }

    public record FlapDecision(int flipCountInWindow, boolean flapTriggered, Instant silencedUntil) {}

    public static FlapDecision evaluateFlap(int previousFlipCount, boolean phaseFlipped, Instant lastOccurredAt, Instant incomingAt, Instant existingSilencedUntil) {
        Objects.requireNonNull(incomingAt, "incomingAt");
        boolean withinFlapWindow = lastOccurredAt != null
            && Math.abs(Duration.between(lastOccurredAt, incomingAt).getSeconds()) <= FLAP_WINDOW_SEC;
        int baseCount = withinFlapWindow ? previousFlipCount : 0;
        int nextFlipCount = phaseFlipped ? baseCount + 1 : baseCount;
        boolean newlyTriggered = nextFlipCount >= FLAP_FLIP_THRESHOLD;
        Instant silencedUntil = existingSilencedUntil != null && existingSilencedUntil.isAfter(incomingAt)
            ? existingSilencedUntil
            : null;
        if (newlyTriggered) {
            Instant candidate = incomingAt.plusSeconds(FLAP_SILENCE_SEC);
            if (silencedUntil == null || candidate.isAfter(silencedUntil)) {
                silencedUntil = candidate;
            }
        }
        boolean activeSilence = silencedUntil != null && !incomingAt.isAfter(silencedUntil);
        return new FlapDecision(nextFlipCount, activeSilence, silencedUntil);
    }

    public record SuppressionDecision(
        SuppressionState suppressionState,
        String suppressionReason,
        NotificationEmptyState notificationEmptyState,
        boolean slaUnknownMarked,
        NoiseLayer primaryNoiseLayer
    ) {}

    public static SuppressionDecision evaluateSuppression(
        Severity severity,
        boolean flapSilenced,
        String upstreamProblemAssetId,
        String maintenanceWindowName
    ) {
        if (upstreamProblemAssetId != null && !upstreamProblemAssetId.isBlank()) {
            return new SuppressionDecision(
                SuppressionState.DEPENDENCY_SUPPRESSED,
                "上游依赖资产(" + upstreamProblemAssetId + ")处于故障状态，下游级联告警已抑制并记入SLA未知区间(Q05)",
                NotificationEmptyState.DEPENDENCY_SUPPRESSED_NO_NOTIFY,
                true,
                NoiseLayer.DEPENDENCY
            );
        }
        if (maintenanceWindowName != null && !maintenanceWindowName.isBlank()) {
            return new SuppressionDecision(
                SuppressionState.MAINTENANCE_SILENCED,
                "命中有效维护窗口[" + maintenanceWindowName + "]，通知已静默",
                NotificationEmptyState.MAINTENANCE_WINDOW_SUPPRESSED,
                false,
                NoiseLayer.MAINTENANCE
            );
        }
        if (flapSilenced) {
            return new SuppressionDecision(
                SuppressionState.FLAP_SILENCED,
                "10分钟内状态翻转达到" + FLAP_FLIP_THRESHOLD + "次，进入5分钟抖动静默期(M4-7)",
                NotificationEmptyState.FLAP_SILENCED_NO_NOTIFY,
                false,
                NoiseLayer.FLAP
            );
        }
        if (severity == Severity.INFO) {
            return new SuppressionDecision(
                SuppressionState.INFO_DEFAULT_SILENT,
                "信息级(INFO)事件默认仅归档不推送通知",
                NotificationEmptyState.INFO_LEVEL_NO_NOTIFY,
                false,
                NoiseLayer.EFFECTIVE_ALERT
            );
        }
        return new SuppressionDecision(
            SuppressionState.NONE,
            null,
            NotificationEmptyState.NORMAL_DISPATCH_ELIGIBLE,
            false,
            NoiseLayer.EFFECTIVE_ALERT
        );
    }

    /**
     * Enforces M4-11 & M4-14 6-state lifecycle:
     * NEW -> ACKNOWLEDGED -> IN_PROGRESS -> PENDING_REVIEW -> RESOLVED -> CLOSED.
     * Recovery transitions an active event to PENDING_REVIEW (never directly to RESOLVED or CLOSED).
     * RESOLVED requires PENDING_REVIEW and a non-blank rootCauseNote.
     */
    public static HandlingState nextHandlingStateOnSignal(HandlingState current, MonitorState incomingPhase) {
        if (current == null) {
            return incomingPhase == MonitorState.RECOVERED ? HandlingState.PENDING_REVIEW : HandlingState.NEW;
        }
        if (current == HandlingState.CLOSED) {
            throw new DomainException(409, "EVENT_ALREADY_CLOSED", "已关闭的监控事件不可再次转换状态");
        }
        if (incomingPhase == MonitorState.RECOVERED) {
            if (current == HandlingState.NEW || current == HandlingState.ACKNOWLEDGED || current == HandlingState.IN_PROGRESS) {
                return HandlingState.PENDING_REVIEW;
            }
            return current;
        }
        if (current == HandlingState.PENDING_REVIEW || current == HandlingState.RESOLVED) {
            return HandlingState.IN_PROGRESS;
        }
        return current;
    }

    public static HandlingState transitionHandlingState(HandlingState current, MonitorState monitorState, String action, String rootCauseNote) {
        if (current == null || action == null) throw new DomainException(400, "VALIDATION_ERROR", "事件处置状态或动作不能为空");
        if (current == HandlingState.CLOSED) throw new DomainException(409, "EVENT_ALREADY_CLOSED", "已关闭的监控事件不可修改");
        return switch (action.trim().toUpperCase(Locale.ROOT)) {
            case "ACKNOWLEDGE" -> {
                if (current != HandlingState.NEW)
                    throw new DomainException(422, "INVALID_EVENT_STATE_TRANSITION", "仅NEW状态的事件可执行确认(ACKNOWLEDGE)");
                yield HandlingState.ACKNOWLEDGED;
            }
            case "START" -> {
                if (current != HandlingState.NEW && current != HandlingState.ACKNOWLEDGED)
                    throw new DomainException(422, "INVALID_EVENT_STATE_TRANSITION", "仅NEW或ACKNOWLEDGED状态的事件可开始处理(START)");
                yield HandlingState.IN_PROGRESS;
            }
            case "MARK_PENDING_REVIEW" -> {
                if (current == HandlingState.RESOLVED)
                    throw new DomainException(422, "INVALID_EVENT_STATE_TRANSITION", "已解决事件不可退回待复核");
                yield HandlingState.PENDING_REVIEW;
            }
            case "RESOLVE" -> {
                if (current != HandlingState.PENDING_REVIEW)
                    throw new DomainException(422, "INVALID_EVENT_STATE_TRANSITION", "告警恢复后须先处于待复核(PENDING_REVIEW)并经人工确认根因才可进入已解决(M4-11)");
                if (rootCauseNote == null || rootCauseNote.trim().length() < 5)
                    throw new DomainException(400, "VALIDATION_ERROR", "人工复核确认解决必须填写不少于5个字符的根因说明(M4-11)");
                yield HandlingState.RESOLVED;
            }
            case "CLOSE" -> {
                if (current != HandlingState.RESOLVED)
                    throw new DomainException(422, "INVALID_EVENT_STATE_TRANSITION", "仅已解决(RESOLVED)的监控事件允许关闭(CLOSE)");
                yield HandlingState.CLOSED;
            }
            default -> throw new DomainException(400, "VALIDATION_ERROR", "未知的事件处置动作: " + action);
        };
    }

    public record AutoTicketPlan(
        int attemptCount,
        AutoTicketStatus status,
        String mappedPriority,
        Instant nextRetryAt,
        String lastError
    ) {}

    public static AutoTicketPlan evaluateAutoTicketAttempt(
        Severity severity,
        SuppressionState suppressionState,
        int previousAttempts,
        boolean simulateDownstreamFailure,
        Instant now
    ) {
        String priority = severity.candidatePriority();
        if (priority == null || suppressionState != SuppressionState.NONE) {
            return new AutoTicketPlan(
                Math.max(1, previousAttempts),
                AutoTicketStatus.SUPPRESSED_SKIPPED,
                priority,
                null,
                suppressionState != SuppressionState.NONE ? "事件处于抑制/静默状态，跳过自动建单" : "INFO级别事件默认不自动建单"
            );
        }
        int currentAttempt = previousAttempts + 1;
        if (!simulateDownstreamFailure) {
            return new AutoTicketPlan(currentAttempt, AutoTicketStatus.SUCCEEDED, priority, null, null);
        }
        if (currentAttempt >= AUTOCREATE_RETRY_MAX) {
            return new AutoTicketPlan(
                AUTOCREATE_RETRY_MAX,
                AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED,
                priority,
                null,
                "自动建单连续重试" + AUTOCREATE_RETRY_MAX + "次失败（退避30s/2m/10m），已转入建单隔离队列等待人工补建(M5-4)"
            );
        }
        long backoffSec = AUTOCREATE_BACKOFF_SEC.get(Math.min(currentAttempt - 1, AUTOCREATE_BACKOFF_SEC.size() - 1));
        return new AutoTicketPlan(
            currentAttempt,
            AutoTicketStatus.RETRY_SCHEDULED,
            priority,
            now.plusSeconds(backoffSec),
            "第" + currentAttempt + "次自动建单失败，将在" + backoffSec + "秒后退避重试(M5-4)"
        );
    }
}
