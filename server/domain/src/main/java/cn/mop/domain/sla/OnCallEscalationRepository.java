package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Domain repository contract for On-Call Board, Shift Roster, Append-Only Handoff Ledger,
 * Configurable Escalation Policies/Runs, and SLA Monthly Results (D16: M5-9, M5-10, M5-12, M5-13, M5-19).
 */
public interface OnCallEscalationRepository {
    long MAX_VERSION = 9007199254740990L;
    String HANDOFF_LOCAL_TIME = "18:00";
    int DEFAULT_UNANSWERED_ESCALATE_COUNT = 2;
    int AUTO_REFRESH_SEC = 30;
    long MTTA_TARGET_SEC = 300L;
    Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    Pattern MONTH_PATTERN = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");

    enum ShiftCode { DAY, NIGHT, FULL_DAY }
    enum PolicyTier { BASIC, STANDARD, ADVANCED, FALLBACK }
    enum TriggerCondition { IMMEDIATE, UNACKNOWLEDGED, UNRESOLVED, COUNTDOWN_ZERO, PHONE_UNANSWERED }
    enum TargetRole { L1_ONCALL, L2_ONCALL, DUTY_LEAD, CUSTOMER_ADMIN, SERVICE_MANAGER, DECISION_MAKER }
    enum SmsNoticeStatus { NOT_REQUIRED, INTENT_RECORDED_PENDING_GATEWAY }

    record Access(String tenantId, String actorId, long permissionVersion, String traceId) {
        public Access {
            requireUuid(tenantId);
            requireUuid(actorId);
            if (permissionVersion < 1 || permissionVersion > MAX_VERSION) throw invalid();
            if (traceId == null || traceId.isBlank() || traceId.length() > 128) throw invalid();
        }
    }

    record RosterEntry(
        String id,
        String tenantId,
        long version,
        LocalDate shiftDate,
        ShiftCode shiftCode,
        String handoffLocalTime,
        String primaryOncallUserId,
        String backupOncallUserId,
        String note,
        String rosterPolicyStatus,
        String updatedBy,
        Instant updatedAt
    ) {
        public RosterEntry {
            requireUuid(id);
            requireUuid(tenantId);
            Objects.requireNonNull(shiftDate, "shiftDate");
            Objects.requireNonNull(shiftCode, "shiftCode");
            if (!HANDOFF_LOCAL_TIME.equals(handoffLocalTime)) throw invalid();
            requireUuid(primaryOncallUserId);
            if (backupOncallUserId != null) {
                requireUuid(backupOncallUserId);
                if (backupOncallUserId.equals(primaryOncallUserId)) throw invalid();
            }
            if (!"CONFIG_PENDING_Q15".equals(rosterPolicyStatus)) throw invalid();
            requireUuid(updatedBy);
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record RosterCommand(
        long expectedVersion,
        LocalDate shiftDate,
        ShiftCode shiftCode,
        String primaryOncallUserId,
        String backupOncallUserId,
        String note
    ) {
        public RosterCommand {
            if (expectedVersion < 0 || expectedVersion > MAX_VERSION) throw invalid();
            Objects.requireNonNull(shiftDate, "shiftDate");
            Objects.requireNonNull(shiftCode, "shiftCode");
            requireUuid(primaryOncallUserId);
            if (backupOncallUserId != null) {
                requireUuid(backupOncallUserId);
                if (backupOncallUserId.equals(primaryOncallUserId)) throw invalid();
            }
            if (note != null) requireText(note, 500);
        }
    }

    record HandoffEntry(
        String id,
        String tenantId,
        String rosterId,
        String outgoingUserId,
        String incomingUserId,
        String handoffLocalTime,
        int unacknowledgedCount,
        int inProgressCount,
        int pendingReviewCount,
        String note,
        Instant handedOffAt
    ) {
        public HandoffEntry {
            requireUuid(id);
            requireUuid(tenantId);
            if (rosterId != null) requireUuid(rosterId);
            requireUuid(outgoingUserId);
            requireUuid(incomingUserId);
            if (outgoingUserId.equals(incomingUserId)) throw invalid();
            if (!HANDOFF_LOCAL_TIME.equals(handoffLocalTime)) throw invalid();
            if (unacknowledgedCount < 0 || inProgressCount < 0 || pendingReviewCount < 0) throw invalid();
            requireText(note, 1000);
            Objects.requireNonNull(handedOffAt, "handedOffAt");
        }
    }

    record HandoffCommand(String rosterId, String incomingUserId, String note) {
        public HandoffCommand {
            if (rosterId != null) requireUuid(rosterId);
            requireUuid(incomingUserId);
            requireText(note, 1000);
        }
    }

    record EscalationStep(int stepOrder, TriggerCondition triggerCondition, int waitMinutes, TargetRole targetRole) {
        public EscalationStep {
            if (stepOrder < 1 || stepOrder > 8) throw invalid();
            Objects.requireNonNull(triggerCondition, "triggerCondition");
            if (waitMinutes < 0 || waitMinutes > 10080) throw invalid();
            Objects.requireNonNull(targetRole, "targetRole");
        }
    }

    record EscalationPolicy(
        String tenantId,
        PolicyTier tier,
        long version,
        int unansweredEscalateCount,
        Integer unansweredRetryIntervalSec,
        List<EscalationStep> steps,
        String policyStatus,
        String updatedBy,
        Instant updatedAt,
        boolean persisted
    ) {
        public EscalationPolicy {
            requireUuid(tenantId);
            Objects.requireNonNull(tier, "tier");
            if (version < 0 || version > MAX_VERSION) throw invalid();
            if (unansweredEscalateCount < 1 || unansweredEscalateCount > 10) throw invalid();
            if (unansweredRetryIntervalSec != null) throw invalid();
            validateSteps(steps);
            steps = List.copyOf(steps);
            if (!"CONFIG_PENDING_Q15".equals(policyStatus)) throw invalid();
        }
    }

    record PolicyUpdateCommand(
        PolicyTier tier,
        long expectedVersion,
        int unansweredEscalateCount,
        List<EscalationStep> steps
    ) {
        public PolicyUpdateCommand {
            Objects.requireNonNull(tier, "tier");
            if (expectedVersion < 0 || expectedVersion > MAX_VERSION) throw invalid();
            if (unansweredEscalateCount < 1 || unansweredEscalateCount > 10) throw invalid();
            validateSteps(steps);
            steps = List.copyOf(steps);
        }
    }

    record EscalationStepRun(
        String id,
        String tenantId,
        String ticketId,
        PolicyTier tier,
        long policyVersion,
        int stepOrder,
        TriggerCondition triggerCondition,
        int waitMinutes,
        TargetRole targetRole,
        int unansweredPhoneCount,
        Integer escalatedToStep,
        TargetRole escalatedTargetRole,
        SmsNoticeStatus smsNoticeStatus,
        String smsNoticeSummary,
        String recordedBy,
        Instant recordedAt
    ) {
        public EscalationStepRun {
            requireUuid(id);
            requireUuid(tenantId);
            requireUuid(ticketId);
            Objects.requireNonNull(tier, "tier");
            if (policyVersion < 1 || policyVersion > MAX_VERSION) throw invalid();
            if (stepOrder < 1 || stepOrder > 8) throw invalid();
            Objects.requireNonNull(triggerCondition, "triggerCondition");
            if (waitMinutes < 0 || waitMinutes > 10080) throw invalid();
            Objects.requireNonNull(targetRole, "targetRole");
            if (unansweredPhoneCount < 0 || unansweredPhoneCount > 10) throw invalid();
            if ((escalatedToStep == null) != (escalatedTargetRole == null)) throw invalid();
            Objects.requireNonNull(smsNoticeStatus, "smsNoticeStatus");
            requireUuid(recordedBy);
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }

    record EscalationTriggerCommand(
        String ticketId,
        PolicyTier tier,
        int stepOrder,
        TriggerCondition triggerCondition,
        int unansweredPhoneCount
    ) {
        public EscalationTriggerCommand {
            requireUuid(ticketId);
            Objects.requireNonNull(tier, "tier");
            if (stepOrder < 1 || stepOrder > 8) throw invalid();
            Objects.requireNonNull(triggerCondition, "triggerCondition");
            if (unansweredPhoneCount < 0 || unansweredPhoneCount > 10) throw invalid();
        }
    }

    record QueueItem(
        String ticketId,
        String priority,
        String state,
        String tierSnapshot,
        String assigneeUserId,
        Instant createdAt,
        Instant responseDueAt,
        Instant recoveryDueAt,
        Long countdownRemainingSec,
        BigDecimal countdownProgressPct,
        boolean breached,
        int currentEscalationStep,
        Integer nextEscalationStep,
        String nextEscalationTargetRole
    ) {}

    record BoardKpis(
        int unacknowledgedCount,
        int criticalCount,
        int inProgressCount,
        int pendingReviewCount,
        int recoveredTodayCount,
        Long mttaP50Sec,
        long mttaTargetSec
    ) {}

    record OnCallBoardView(
        String tenantId,
        BoardKpis kpis,
        List<QueueItem> queue,
        List<RosterEntry> rosters,
        List<HandoffEntry> handoffs,
        List<EscalationPolicy> policies,
        List<EscalationStepRun> stepRuns,
        int autoRefreshSec,
        String handoffLocalTime,
        Instant observedAt
    ) {}

    record MonthlyResultRecord(
        String id,
        String tenantId,
        String reportMonth,
        long version,
        Tier tierSnapshot,
        int totalTicketCount,
        int applicableCount,
        int metCount,
        int notMetCount,
        int unresolvedCount,
        int notApplicableCount,
        Integer achievementNumerator,
        Integer achievementDenominator,
        BigDecimal achievementRatePct,
        BigDecimal compensationRatio,
        Long monthlyFeeFen,
        Long compensationAmountFen,
        String compensationStatus,
        Long candidateFixedRatioFen,
        Long candidateShortfallWeightedFen,
        String formulaVersion,
        List<TicketJudgment> items,
        boolean frozen,
        Instant frozenAt,
        String evaluatedBy,
        Instant evaluatedAt
    ) {
        public MonthlyResultRecord {
            requireUuid(id);
            requireUuid(tenantId);
            requireMonth(reportMonth);
            Objects.requireNonNull(tierSnapshot, "tierSnapshot");
            Objects.requireNonNull(compensationRatio, "compensationRatio");
            Objects.requireNonNull(compensationStatus, "compensationStatus");
            Objects.requireNonNull(formulaVersion, "formulaVersion");
            items = List.copyOf(items);
            requireUuid(evaluatedBy);
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        }
    }

    record MonthlyResultCommand(
        String reportMonth,
        long expectedVersion,
        boolean freeze
    ) {
        public MonthlyResultCommand {
            requireMonth(reportMonth);
            if (expectedVersion < 0 || expectedVersion > MAX_VERSION) throw invalid();
        }
    }


    record SlaReportView(
        String tenantId,
        String period,
        Tier currentTier,
        BigDecimal targetAvailabilityPct,
        BigDecimal meanVerifiedAvailabilityPct,
        long unavailableUnionTotalSec,
        LatencyStats mtta,
        LatencyStats mttr,
        int unrecoveredTotalCount,
        long unknownIntervalTotalSec,
        List<ServiceRow> services,
        Optional<MonthlyResultRecord> monthlyResult,
        MonthlyEvaluation liveMonthlyEvaluation,
        String formulaVersion,
        Instant observedAt,
        ServiceAvailabilityAggregate.Result availabilityAggregate
    ) {
        /** Legacy projections retain their original shape and do not acquire fabricated aggregation evidence. */
        public SlaReportView(
            String tenantId,
            String period,
            Tier currentTier,
            BigDecimal targetAvailabilityPct,
            BigDecimal meanVerifiedAvailabilityPct,
            long unavailableUnionTotalSec,
            LatencyStats mtta,
            LatencyStats mttr,
            int unrecoveredTotalCount,
            long unknownIntervalTotalSec,
            List<ServiceRow> services,
            Optional<MonthlyResultRecord> monthlyResult,
            MonthlyEvaluation liveMonthlyEvaluation,
            String formulaVersion,
            Instant observedAt
        ) {
            this(
                tenantId,
                period,
                currentTier,
                targetAvailabilityPct,
                meanVerifiedAvailabilityPct,
                unavailableUnionTotalSec,
                mtta,
                mttr,
                unrecoveredTotalCount,
                unknownIntervalTotalSec,
                services,
                monthlyResult,
                liveMonthlyEvaluation,
                formulaVersion,
                observedAt,
                null
            );
        }
    }

    List<String> readinessProblems();
    void authorizeWrite(Access access);
    OnCallBoardView board(Access access, int limit);
    RosterEntry upsertRoster(Access access, RosterCommand command);
    HandoffEntry recordHandoff(Access access, HandoffCommand command);
    List<EscalationPolicy> policies(Access access);
    EscalationPolicy updatePolicy(Access access, PolicyUpdateCommand command);
    EscalationStepRun triggerEscalation(Access access, EscalationTriggerCommand command);
    SlaReportView slaReport(Access access, String period);
    MonthlyResultRecord evaluateMonthlyResult(Access access, MonthlyResultCommand command);

    static List<EscalationStep> defaultSteps(PolicyTier tier) {
        Objects.requireNonNull(tier, "tier");
        return switch (tier) {
            case STANDARD -> List.of(
                new EscalationStep(1, TriggerCondition.IMMEDIATE, 0, TargetRole.L1_ONCALL),
                new EscalationStep(2, TriggerCondition.UNRESOLVED, 240, TargetRole.SERVICE_MANAGER)
            );
            case ADVANCED -> List.of(
                new EscalationStep(1, TriggerCondition.IMMEDIATE, 0, TargetRole.L1_ONCALL),
                new EscalationStep(2, TriggerCondition.UNACKNOWLEDGED, 30, TargetRole.DUTY_LEAD),
                new EscalationStep(3, TriggerCondition.UNRESOLVED, 120, TargetRole.SERVICE_MANAGER)
            );
            case BASIC, FALLBACK -> List.of(
                new EscalationStep(1, TriggerCondition.IMMEDIATE, 0, TargetRole.L1_ONCALL),
                new EscalationStep(2, TriggerCondition.UNACKNOWLEDGED, 15, TargetRole.L2_ONCALL),
                new EscalationStep(3, TriggerCondition.UNACKNOWLEDGED, 30, TargetRole.CUSTOMER_ADMIN),
                new EscalationStep(4, TriggerCondition.UNRESOLVED, 60, TargetRole.SERVICE_MANAGER)
            );
        };
    }

    static void validateSteps(List<EscalationStep> steps) {
        if (steps == null || steps.isEmpty() || steps.size() > 8) throw invalid();
        int prevWait = -1;
        for (int i = 0; i < steps.size(); i++) {
            EscalationStep s = steps.get(i);
            if (s == null || s.stepOrder() != i + 1 || s.waitMinutes() < prevWait) throw invalid();
            prevWait = s.waitMinutes();
        }
    }

    static void requireUuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) throw invalid();
    }

    static void requireMonth(String value) {
        if (value == null || !MONTH_PATTERN.matcher(value).matches()) throw invalid();
    }

    static void requireText(String value, int maxCodePoints) {
        if (value == null || value.trim().isEmpty() || value.codePointCount(0, value.length()) > maxCodePoints)
            throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' || ch == 0x7f || Character.isSurrogate(ch))
                throw invalid();
        }
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "值班看板、升级策略或月度SLA参数无效");
    }
}
