package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository contract for V37 D15 Monitoring Event Center, Noise Funnel,
 * Maintenance Windows, Quarantine Manual Decisions, and Auto-Ticket Attempts
 * (M1-2, M4-7, M4-8, M4-9, M4-10, M4-11, M4-14, M5-4).
 */
public interface MonitoringEventCenterRepository {
    record Access(String tenantId, String actorId, long permissionVersion, String traceId) {}

    record EventSummary(
        String id,
        String tenantId,
        String assetId,
        long version,
        String ruleCode,
        String ruleName,
        Severity severity,
        int severityRank,
        String fingerprint,
        int occurrenceCount,
        int flapFlipCount,
        Instant flapSilencedUntil,
        MonitorState monitorState,
        HandlingState handlingState,
        SuppressionState suppressionState,
        String suppressionReason,
        NotificationEmptyState notificationEmptyState,
        String notificationEmptyStateLabel,
        boolean slaUnknownMarked,
        String reviewPolicyStatus,
        Instant problemAt,
        Instant lastOccurredAt,
        Instant recoveredAt,
        Instant firstAckAt,
        Instant resolvedAt,
        Instant closedAt,
        String rootCauseNote,
        String ticketId,
        String updatedBy,
        Instant updatedAt
    ) {}

    record EventMember(
        String id,
        String tenantId,
        String eventId,
        String rawEventId,
        int sequenceNo,
        String sourceEventId,
        MonitorState sourcePhase,
        Instant occurredAt,
        boolean mergedWithinWindow,
        Instant recordedAt
    ) {}

    record NoiseRecord(
        String id,
        String tenantId,
        String eventId,
        NoiseLayer layer,
        String detail,
        Instant recordedAt
    ) {}

    record AutoTicketAttempt(
        String id,
        String tenantId,
        String eventId,
        long version,
        int attemptCount,
        int maxRetries,
        String backoffScheduleSec,
        AutoTicketStatus status,
        String mappedPriority,
        String mappingPolicyStatus,
        String ticketId,
        Instant nextRetryAt,
        String lastError,
        String updatedBy,
        Instant updatedAt
    ) {}

    record MaintenanceWindow(
        String id,
        String tenantId,
        long version,
        String name,
        String assetId,
        Instant startsAt,
        Instant endsAt,
        String silenceMode,
        String status,
        boolean slaExclusionRequested,
        boolean slaExclusionConfirmed,
        String slaExclusionConfirmedBy,
        Instant slaExclusionConfirmedAt,
        String reason,
        String createdBy,
        String updatedBy,
        Instant updatedAt
    ) {}

    record QuarantineItem(
        String quarantineId,
        String poolId,
        String sourceEventId,
        String sourcePhase,
        String claimedTenantId,
        String claimedAssetId,
        String quarantineReason,
        Instant occurredAt,
        Instant receivedAt,
        String quarantineStatus,
        String decision,
        String boundAssetId,
        String resultEventId,
        String decisionReason,
        String decidedBy,
        Instant decidedAt
    ) {}

    record NoiseFunnelView(
        String tenantId,
        String window,
        long rawIntakeCount,
        long mergedDeduplicatedCount,
        long flapSilencedCount,
        long dependencySuppressedCount,
        long maintenanceSilencedCount,
        long effectiveAlertCount,
        Instant observedAt
    ) {}

    record EventCenterWorkspace(
        String tenantId,
        List<EventSummary> events,
        NoiseFunnelView noiseFunnel,
        List<MaintenanceWindow> maintenanceWindows,
        List<QuarantineItem> quarantineItems,
        List<AutoTicketAttempt> autoTicketAttempts,
        Instant observedAt
    ) {}

    record EventDetailView(
        EventSummary event,
        List<EventMember> members,
        List<NoiseRecord> noiseRecords,
        Optional<AutoTicketAttempt> autoTicketAttempt,
        Instant observedAt
    ) {}

    record IngestEventCommand(
        String assetId,
        String ruleCode,
        String ruleName,
        Severity severity,
        MonitorState sourcePhase,
        String sourceEventId,
        Instant occurredAt,
        boolean simulateAutoTicketFailure
    ) {
        public IngestEventCommand {
            if (assetId == null || assetId.isBlank() || ruleCode == null || ruleCode.isBlank()
                || ruleName == null || ruleName.isBlank() || severity == null || sourcePhase == null
                || sourceEventId == null || sourceEventId.isBlank()) {
                throw new DomainException(400, "VALIDATION_ERROR", "告警事件摄取请求参数不完整");
            }
        }
    }

    record TransitionEventCommand(
        String eventId,
        long expectedVersion,
        String action,
        String rootCauseNote
    ) {
        public TransitionEventCommand {
            if (eventId == null || eventId.isBlank() || expectedVersion < 1 || action == null || action.isBlank()) {
                throw new DomainException(400, "VALIDATION_ERROR", "事件状态流转参数无效");
            }
        }
    }

    record CreateMaintenanceWindowCommand(
        String name,
        String assetId,
        Instant startsAt,
        Instant endsAt,
        String silenceMode,
        boolean slaExclusionRequested,
        String reason
    ) {
        public CreateMaintenanceWindowCommand {
            if (name == null || name.isBlank() || assetId == null || assetId.isBlank()
                || startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)
                || silenceMode == null || (!"SUPPRESS_NOTIFY".equals(silenceMode) && !"PAUSE_COLLECTION".equals(silenceMode))
                || reason == null || reason.isBlank()) {
                throw new DomainException(400, "VALIDATION_ERROR", "维护窗口创建参数无效");
            }
        }
    }

    record UpdateMaintenanceWindowCommand(
        String windowId,
        long expectedVersion,
        String action,
        Instant newEndsAt
    ) {
        public UpdateMaintenanceWindowCommand {
            if (windowId == null || windowId.isBlank() || expectedVersion < 1 || action == null || action.isBlank()) {
                throw new DomainException(400, "VALIDATION_ERROR", "维护窗口更新参数无效");
            }
        }
    }

    record QuarantineDecisionCommand(
        String quarantineId,
        String decision,
        String boundAssetId,
        String reason
    ) {
        public QuarantineDecisionCommand {
            if (quarantineId == null || quarantineId.isBlank() || decision == null
                || (!"BIND_ASSET".equals(decision) && !"DISCARD".equals(decision))
                || reason == null || reason.isBlank()
                || ("BIND_ASSET".equals(decision) && (boundAssetId == null || boundAssetId.isBlank()))
                || ("DISCARD".equals(decision) && boundAssetId != null && !boundAssetId.isBlank())) {
                throw new DomainException(400, "VALIDATION_ERROR", "隔离事件人工核验参数无效");
            }
        }
    }

    record ManualAutoTicketCommand(
        String attemptId,
        long expectedVersion,
        String reason
    ) {
        public ManualAutoTicketCommand {
            if (attemptId == null || attemptId.isBlank() || expectedVersion < 1 || reason == null || reason.isBlank()) {
                throw new DomainException(400, "VALIDATION_ERROR", "隔离自动建单人工补建参数无效");
            }
        }
    }

    EventCenterWorkspace workspace(Access access, String window, int pageSize);
    EventDetailView eventDetail(Access access, String eventId);
    NoiseFunnelView noiseFunnel(Access access, String window);
    void authorizeWrite(Access access);
    void authorizeAdminWrite(Access access);
    EventSummary ingestEvent(Access access, IngestEventCommand command);
    EventSummary transitionEvent(Access access, TransitionEventCommand command);
    MaintenanceWindow createMaintenanceWindow(Access access, CreateMaintenanceWindowCommand command);
    MaintenanceWindow updateMaintenanceWindow(Access access, UpdateMaintenanceWindowCommand command);
    QuarantineItem resolveQuarantine(Access access, QuarantineDecisionCommand command);
    AutoTicketAttempt manualCreateAutoTicket(Access access, ManualAutoTicketCommand command);
    List<String> readinessProblems();
}
