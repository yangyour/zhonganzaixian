package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.sla.*;
import cn.mop.domain.sla.OnCallEscalationRepository.*;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/**
 * REST controller for On-Call Board, Shift Roster, Append-Only Handoffs,
 * Configurable Escalation Policies/Step Runs, and SLA Reports / Monthly Results (D16: M5-9, M5-10, M5-12, M5-13, M5-19).
 */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public final class OnCallEscalationController {
    private static final long MAX = OnCallEscalationRepository.MAX_VERSION;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final PlatformRepository platform;
    private final OnCallEscalationRepository repository;

    public OnCallEscalationController(PlatformRepository platform, OnCallEscalationRepository repository) {
        this.platform = platform;
        this.repository = repository;
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/oncall/board")
    Object board(HttpServletRequest r) {
        Access a = access(r, "tickets:read", true);
        var q = query(r, Set.of("page_size"));
        int limit = size(q.get("page_size"), 25, 100);
        OnCallBoardView v = repository.board(a, limit);
        if (v == null || !a.tenantId().equals(v.tenantId())) throw unavailable();
        return success(map(
            "tenant_id", v.tenantId(),
            "kpis", map(
                "unacknowledged_count", v.kpis().unacknowledgedCount(),
                "critical_count", v.kpis().criticalCount(),
                "in_progress_count", v.kpis().inProgressCount(),
                "pending_review_count", v.kpis().pendingReviewCount(),
                "recovered_today_count", v.kpis().recoveredTodayCount(),
                "mtta_p50_sec", v.kpis().mttaP50Sec(),
                "mtta_target_sec", v.kpis().mttaTargetSec()
            ),
            "queue", v.queue().stream().map(OnCallEscalationController::queueDto).toList(),
            "rosters", v.rosters().stream().map(x -> rosterDto(x, a.tenantId())).toList(),
            "handoffs", v.handoffs().stream().map(x -> handoffDto(x, a.tenantId())).toList(),
            "policies", v.policies().stream().map(x -> policyDto(x, a.tenantId())).toList(),
            "step_runs", v.stepRuns().stream().map(x -> stepRunDto(x, a.tenantId())).toList(),
            "auto_refresh_sec", v.autoRefreshSec(),
            "handoff_local_time", v.handoffLocalTime(),
            "roster_policy_status", "CONFIG_PENDING_Q15",
            "observed_at", v.observedAt().toString(),
            "source", "postgresql"
        ), r);
    }

    @PostMapping(value = "/oncall/rosters", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object upsertRoster(HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("expected_version", "shift_date", "shift_code", "primary_oncall_user_id", "backup_oncall_user_id", "note"));
        long expected = version(b.get("expected_version"));
        LocalDate date = parseDate(b.get("shift_date"));
        ShiftCode code = parseEnum(b.get("shift_code"), ShiftCode.class);
        String primary = uuid(b.get("primary_oncall_user_id"));
        String backup = b.get("backup_oncall_user_id").isNull() ? null : uuid(b.get("backup_oncall_user_id"));
        String note = b.get("note").isNull() ? null : text(b.get("note"), 500);
        RosterCommand cmd = new RosterCommand(expected, date, code, primary, backup, note);
        String key = idempotencyKey(r);
        String digest = digest("ONCALL_ROSTER_V1\n" + expected + "\n" + date + "\n" + code.name() + "\n" + primary + "\n" + backup + "\n" + note);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> rosterDto(repository.upsertRoster(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @PostMapping(value = "/oncall/handoffs", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object recordHandoff(HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("roster_id", "incoming_user_id", "note"));
        String rosterId = b.get("roster_id").isNull() ? null : uuid(b.get("roster_id"));
        String incoming = uuid(b.get("incoming_user_id"));
        String note = text(b.get("note"), 1000);
        HandoffCommand cmd = new HandoffCommand(rosterId, incoming, note);
        String key = idempotencyKey(r);
        String digest = digest("ONCALL_HANDOFF_V1\n" + rosterId + "\n" + incoming + "\n" + note);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> handoffDto(repository.recordHandoff(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @GetMapping("/escalation-policies")
    Object listPolicies(HttpServletRequest r) {
        Access a = access(r, "tickets:read", true);
        if (r.getQueryString() != null) throw invalid();
        List<EscalationPolicy> policies = repository.policies(a);
        return success(map(
            "tenant_id", a.tenantId(),
            "items", policies.stream().map(x -> policyDto(x, a.tenantId())).toList(),
            "policy_status", "CONFIG_PENDING_Q15",
            "source", "postgresql"
        ), r);
    }

    @PatchMapping(value = "/escalation-policies", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object updatePolicy(HttpServletRequest r) {
        Access a = access(r, "configurations:write", true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("tier", "expected_version", "unanswered_escalate_count", "steps"));
        PolicyTier tier = parseEnum(b.get("tier"), PolicyTier.class);
        long expected = version(b.get("expected_version"));
        int unansweredCount = boundedInt(b.get("unanswered_escalate_count"), 1, 10);
        JsonNode stepsNode = b.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty() || stepsNode.size() > 8) throw invalid();
        var steps = new ArrayList<EscalationStep>();
        var stepSig = new StringBuilder();
        for (JsonNode sn : stepsNode) {
            fields(sn, Set.of("step_order", "trigger_condition", "wait_minutes", "target_role"));
            int order = boundedInt(sn.get("step_order"), 1, 8);
            TriggerCondition cond = parseEnum(sn.get("trigger_condition"), TriggerCondition.class);
            int wait = boundedInt(sn.get("wait_minutes"), 0, 10080);
            TargetRole role = parseEnum(sn.get("target_role"), TargetRole.class);
            steps.add(new EscalationStep(order, cond, wait, role));
            stepSig.append(order).append(':').append(cond.name()).append(':').append(wait).append(':').append(role.name()).append(';');
        }
        PolicyUpdateCommand cmd = new PolicyUpdateCommand(tier, expected, unansweredCount, steps);
        String key = idempotencyKey(r);
        String digest = digest("ESCALATION_POLICY_V1\n" + tier.name() + "\n" + expected + "\n" + unansweredCount + "\n" + stepSig);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "PATCH " + route(r), key, digest, a.permissionVersion(),
            () -> {},
            () -> policyDto(repository.updatePolicy(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @PostMapping(value = "/oncall/escalations", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object triggerEscalation(HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("ticket_id", "tier", "step_order", "trigger_condition", "unanswered_phone_count"));
        String ticketId = uuid(b.get("ticket_id"));
        PolicyTier tier = parseEnum(b.get("tier"), PolicyTier.class);
        int stepOrder = boundedInt(b.get("step_order"), 1, 8);
        TriggerCondition cond = parseEnum(b.get("trigger_condition"), TriggerCondition.class);
        int unanswered = boundedInt(b.get("unanswered_phone_count"), 0, 10);
        EscalationTriggerCommand cmd = new EscalationTriggerCommand(ticketId, tier, stepOrder, cond, unanswered);
        String key = idempotencyKey(r);
        String digest = digest("ESCALATION_TRIGGER_V1\n" + ticketId + "\n" + tier.name() + "\n" + stepOrder + "\n" + cond.name() + "\n" + unanswered);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> stepRunDto(repository.triggerEscalation(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @GetMapping("/sla/reports")
    Object slaReport(HttpServletRequest r) {
        Access a = access(r, "sla_scenarios:read", true);
        var q = query(r, Set.of("period"));
        String period = q.getOrDefault("period", YearMonth.now(SHANGHAI).toString());
        OnCallEscalationRepository.requireMonth(period);
        SlaReportView v = repository.slaReport(a, period);
        if (v == null || !a.tenantId().equals(v.tenantId())) throw unavailable();
        return success(map(
            "tenant_id", v.tenantId(),
            "period", v.period(),
            "current_tier", v.currentTier()==null?null:v.currentTier().name(),
            "target_availability_pct", v.targetAvailabilityPct(),
            "kpis", map(
                "mean_verified_availability_pct", v.meanVerifiedAvailabilityPct(),
                "unavailable_union_total_sec", v.unavailableUnionTotalSec(),
                "mtta", latencyDto(v.mtta()),
                "mttr", latencyDto(v.mttr()),
                "unrecovered_total_count", v.unrecoveredTotalCount(),
                "unknown_interval_total_sec", v.unknownIntervalTotalSec()
            ),
            "services", v.services().stream().map(OnCallEscalationController::serviceDto).toList(),
            "monthly_result", v.monthlyResult().map(m -> monthlyRecordDto(m, a.tenantId())).orElse(null),
            "live_monthly_evaluation", monthlyEvalDto(v.liveMonthlyEvaluation()),
            "formula_version", v.formulaVersion(),
            "timezone", "Asia/Shanghai",
            "observed_at", v.observedAt().toString(),
            "source", "postgresql"
        ), r);
    }

    @GetMapping("/sla/reports/{serviceId}/details")
    Object slaReportDetails(@PathVariable("serviceId") String serviceId, HttpServletRequest r) {
        Access a = access(r, "sla_scenarios:read", true);
        var q = query(r, Set.of("period"));
        String period = q.getOrDefault("period", YearMonth.now(SHANGHAI).toString());
        OnCallEscalationRepository.requireMonth(period);
        SlaReportView v = repository.slaReport(a, period);
        if (v == null || !a.tenantId().equals(v.tenantId())) throw unavailable();
        ServiceRow found = v.services().stream().filter(s -> s.serviceId().equals(serviceId)).findFirst()
            .orElseThrow(() -> new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该SLA服务节点"));
        return success(map(
            "tenant_id", v.tenantId(),
            "period", v.period(),
            "service", serviceDto(found),
            "interval_details", found.intervalDetails().stream().map(OnCallEscalationController::intervalDto).toList(),
            "ticket_judgments", v.liveMonthlyEvaluation().items().stream().map(OnCallEscalationController::judgmentDto).toList(),
            "formula_version", v.formulaVersion(),
            "timezone", "Asia/Shanghai",
            "observed_at", v.observedAt().toString(),
            "source", "postgresql"
        ), r);
    }

    @PostMapping(value = "/sla/monthly-results", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object evaluateMonthlyResult(HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("report_month", "expected_version", "freeze"));
        String month = text(b.get("report_month"), 7);
        OnCallEscalationRepository.requireMonth(month);
        long expected = version(b.get("expected_version"));
        if (b.get("freeze") == null || !b.get("freeze").isBoolean()) throw invalid();
        boolean freeze = b.get("freeze").booleanValue();
        MonthlyResultCommand cmd = new MonthlyResultCommand(month, expected, freeze);
        String key = idempotencyKey(r);
        String digest = digest("SLA_MONTHLY_RESULT_V1\n" + month + "\n" + expected + "\n" + freeze);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> monthlyRecordDto(repository.evaluateMonthlyResult(a, cmd), a.tenantId()));
        return success(result, r);
    }

    private static Map<String, Object> queueDto(QueueItem q) {
        return map(
            "ticket_id", q.ticketId(),
            "priority", q.priority(),
            "state", q.state(),
            "tier_snapshot", q.tierSnapshot(),
            "assignee_user_id", q.assigneeUserId(),
            "created_at", q.createdAt().toString(),
            "response_due_at", q.responseDueAt() == null ? null : q.responseDueAt().toString(),
            "recovery_due_at", q.recoveryDueAt() == null ? null : q.recoveryDueAt().toString(),
            "countdown_remaining_sec", q.countdownRemainingSec(),
            "countdown_progress_pct", q.countdownProgressPct(),
            "breached", q.breached(),
            "current_escalation_step", q.currentEscalationStep(),
            "next_escalation_step", q.nextEscalationStep(),
            "next_escalation_target_role", q.nextEscalationTargetRole()
        );
    }

    private static Map<String, Object> rosterDto(RosterEntry x, String expectedTenant) {
        if (x == null || !expectedTenant.equals(x.tenantId())) throw unavailable();
        return map(
            "id", x.id(),
            "tenant_id", x.tenantId(),
            "version", x.version(),
            "shift_date", x.shiftDate().toString(),
            "shift_code", x.shiftCode().name(),
            "handoff_local_time", x.handoffLocalTime(),
            "primary_oncall_user_id", x.primaryOncallUserId(),
            "backup_oncall_user_id", x.backupOncallUserId(),
            "note", x.note(),
            "roster_policy_status", x.rosterPolicyStatus(),
            "updated_by", x.updatedBy(),
            "updated_at", x.updatedAt().toString()
        );
    }

    private static Map<String, Object> handoffDto(HandoffEntry x, String expectedTenant) {
        if (x == null || !expectedTenant.equals(x.tenantId())) throw unavailable();
        return map(
            "id", x.id(),
            "tenant_id", x.tenantId(),
            "roster_id", x.rosterId(),
            "outgoing_user_id", x.outgoingUserId(),
            "incoming_user_id", x.incomingUserId(),
            "handoff_local_time", x.handoffLocalTime(),
            "unacknowledged_count", x.unacknowledgedCount(),
            "in_progress_count", x.inProgressCount(),
            "pending_review_count", x.pendingReviewCount(),
            "note", x.note(),
            "handed_off_at", x.handedOffAt().toString()
        );
    }

    private static Map<String, Object> policyDto(EscalationPolicy p, String expectedTenant) {
        if (p == null || !expectedTenant.equals(p.tenantId())) throw unavailable();
        return map(
            "tenant_id", p.tenantId(),
            "tier", p.tier().name(),
            "version", p.version(),
            "unanswered_escalate_count", p.unansweredEscalateCount(),
            "unanswered_retry_interval_sec", p.unansweredRetryIntervalSec(),
            "steps", p.steps().stream().map(s -> map(
                "step_order", s.stepOrder(),
                "trigger_condition", s.triggerCondition().name(),
                "wait_minutes", s.waitMinutes(),
                "target_role", s.targetRole().name()
            )).toList(),
            "policy_status", p.policyStatus(),
            "updated_by", p.updatedBy(),
            "updated_at", p.updatedAt().toString(),
            "persisted", p.persisted()
        );
    }

    private static Map<String, Object> stepRunDto(EscalationStepRun s, String expectedTenant) {
        if (s == null || !expectedTenant.equals(s.tenantId())) throw unavailable();
        return map(
            "id", s.id(),
            "tenant_id", s.tenantId(),
            "ticket_id", s.ticketId(),
            "tier", s.tier().name(),
            "policy_version", s.policyVersion(),
            "step_order", s.stepOrder(),
            "trigger_condition", s.triggerCondition().name(),
            "wait_minutes", s.waitMinutes(),
            "target_role", s.targetRole().name(),
            "unanswered_phone_count", s.unansweredPhoneCount(),
            "escalated_to_step", s.escalatedToStep(),
            "escalated_target_role", s.escalatedTargetRole() == null ? null : s.escalatedTargetRole().name(),
            "sms_notice_status", s.smsNoticeStatus().name(),
            "sms_notice_summary", s.smsNoticeSummary(),
            "recorded_by", s.recordedBy(),
            "recorded_at", s.recordedAt().toString()
        );
    }

    private static Map<String, Object> latencyDto(LatencyStats l) {
        return map(
            "sample_count", l.sampleCount(),
            "unrecovered_count", l.unrecoveredCount(),
            "mean_sec", l.meanSec(),
            "p50_sec", l.p50Sec(),
            "p95_sec", l.p95Sec()
        );
    }

    private static Map<String, Object> serviceDto(ServiceRow s) {
        return map(
            "service_id", s.serviceId(),
            "service_name", s.serviceName(),
            "target_pct", s.targetPct(),
            "agreed_service_sec", s.agreedServiceSec(),
            "approved_exclusion_sec", s.approvedExclusionSec(),
            "effective_service_sec", s.effectiveServiceSec(),
            "unavailable_union_sec", s.unavailableUnionSec(),
            "unknown_interval_sec", s.unknownIntervalSec(),
            "known_available_sec", s.knownAvailableSec(),
            "verified_availability_pct", s.verifiedAvailabilityPct(),
            "upper_bound_availability_pct", s.upperBoundAvailabilityPct(),
            "incident_count", s.incidentCount(),
            "mtta", latencyDto(s.mtta()),
            "mttr", latencyDto(s.mttr()),
            "unrecovered_count", s.unrecoveredCount(),
            "status", s.status().name(),
            "unknown_policy_status", s.unknownPolicyStatus(),
            "interval_details", s.intervalDetails().stream().map(OnCallEscalationController::intervalDto).toList()
        );
    }

    private static Map<String, Object> intervalDto(ExplainedInterval i) {
        return map(
            "kind", i.kind(),
            "start", i.start().toString(),
            "end", i.end().toString(),
            "duration_sec", i.durationSec(),
            "explanation", i.explanation()
        );
    }

    private static Map<String, Object> judgmentDto(TicketJudgment j) {
        return map(
            "ticket_id", j.ticketId(),
            "priority", j.priority(),
            "tier_snapshot", j.tierSnapshot(),
            "applicable", j.applicable(),
            "outcome", j.outcome(),
            "response_check", j.responseCheck(),
            "recovery_check", j.recoveryCheck(),
            "response_due_at", j.responseDueAt() == null ? null : j.responseDueAt().toString(),
            "response_actual_at", j.responseActualAt() == null ? null : j.responseActualAt().toString(),
            "recovery_due_at", j.recoveryDueAt() == null ? null : j.recoveryDueAt().toString(),
            "recovery_actual_at", j.recoveryActualAt() == null ? null : j.recoveryActualAt().toString(),
            "reasons", j.reasons()
        );
    }

    private static Map<String, Object> monthlyEvalDto(MonthlyEvaluation m) {
        return map(
            "tier_snapshot", m.tierSnapshot()==null?null:m.tierSnapshot().name(),
            "total_ticket_count", m.totalTicketCount(),
            "applicable_count", m.applicableCount(),
            "met_count", m.metCount(),
            "not_met_count", m.notMetCount(),
            "unresolved_count", m.unresolvedCount(),
            "not_applicable_count", m.notApplicableCount(),
            "achievement_numerator", m.achievementNumerator(),
            "achievement_denominator", m.achievementDenominator(),
            "achievement_rate_pct", m.achievementRatePct(),
            "compensation_ratio", m.compensationRatio(),
            "monthly_fee_fen", m.monthlyFeeFen(),
            "compensation_amount_fen", m.compensationAmountFen(),
            "compensation_status", m.compensationStatus(),
            "candidate_fixed_ratio_fen", m.candidateFixedRatioFen(),
            "candidate_shortfall_weighted_fen", m.candidateShortfallWeightedFen(),
            "formula_version", m.formulaVersion(),
            "items", m.items().stream().map(OnCallEscalationController::judgmentDto).toList()
        );
    }

    private static Map<String, Object> monthlyRecordDto(MonthlyResultRecord m, String expectedTenant) {
        if (m == null || !expectedTenant.equals(m.tenantId())) throw unavailable();
        return map(
            "id", m.id(),
            "tenant_id", m.tenantId(),
            "report_month", m.reportMonth(),
            "version", m.version(),
            "tier_snapshot", m.tierSnapshot().name(),
            "total_ticket_count", m.totalTicketCount(),
            "applicable_count", m.applicableCount(),
            "met_count", m.metCount(),
            "not_met_count", m.notMetCount(),
            "unresolved_count", m.unresolvedCount(),
            "not_applicable_count", m.notApplicableCount(),
            "achievement_numerator", m.achievementNumerator(),
            "achievement_denominator", m.achievementDenominator(),
            "achievement_rate_pct", m.achievementRatePct(),
            "compensation_ratio", m.compensationRatio(),
            "monthly_fee_fen", m.monthlyFeeFen(),
            "compensation_amount_fen", m.compensationAmountFen(),
            "compensation_status", m.compensationStatus(),
            "candidate_fixed_ratio_fen", m.candidateFixedRatioFen(),
            "candidate_shortfall_weighted_fen", m.candidateShortfallWeightedFen(),
            "formula_version", m.formulaVersion(),
            "items", m.items().stream().map(OnCallEscalationController::judgmentDto).toList(),
            "frozen", m.frozen(),
            "frozen_at", m.frozenAt() == null ? null : m.frozenAt().toString(),
            "evaluated_by", m.evaluatedBy(),
            "evaluated_at", m.evaluatedAt().toString()
        );
    }

    private Access access(HttpServletRequest r, String capability, boolean internalOnly) {
        if (r.getUserPrincipal() == null || r.getSession(false) == null) throw expired();
        Object t = r.getSession(false).getAttribute("mop.tenant_id");
        if (!(t instanceof String tenant) || tenant.isBlank()) throw expired();
        String actor = r.getUserPrincipal().getName();
        var m = platform.membership(actor, tenant).orElseThrow(OnCallEscalationController::expired);
        if (!m.active() || m.permissionVersion() < 1) throw expired();
        if (!m.capabilities().contains(capability) || internalOnly && !Capabilities.internal(m.role()))
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份无此值班或SLA报表权限");
        return new Access(tenant, actor, m.permissionVersion(), trace(r));
    }

    private static Map<String, String> query(HttpServletRequest r, Set<String> keys) {
        var result = new LinkedHashMap<String, String>();
        r.getParameterMap().forEach((k, v) -> {
            if (!keys.contains(k) || v.length != 1 || v[0].isBlank() || v[0].length() > 64) throw invalid();
            result.put(k, v[0]);
        });
        return result;
    }

    private static int size(String raw, int fallback, int max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid();
        int n = Integer.parseInt(raw);
        if (n > max) throw invalid();
        return n;
    }

    private static void fields(JsonNode b, Set<String> allowed) {
        if (b == null || !b.isObject() || b.size() != allowed.size()) throw invalid();
        b.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw invalid(); });
    }

    private static long version(JsonNode v) {
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0 || v.longValue() > MAX)
            throw invalid();
        return v.longValue();
    }

    private static int boundedInt(JsonNode v, int min, int max) {
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < min || v.intValue() > max)
            throw invalid();
        return v.intValue();
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode v, Class<E> type) {
        if (v == null || !v.isTextual()) throw invalid();
        try { return Enum.valueOf(type, v.textValue()); }
        catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static LocalDate parseDate(JsonNode v) {
        if (v == null || !v.isTextual()) throw invalid();
        try { return LocalDate.parse(v.textValue()); }
        catch (RuntimeException e) { throw invalid(); }
    }

    private static String uuid(JsonNode v) {
        if (v == null || !v.isTextual()) throw invalid();
        String s = v.textValue();
        OnCallEscalationRepository.requireUuid(s);
        return s;
    }

    private static String text(JsonNode v, int max) {
        if (v == null || !v.isTextual()) throw invalid();
        String s = v.textValue();
        OnCallEscalationRepository.requireText(s, max);
        return s;
    }

    private static String idempotencyKey(HttpServletRequest r) {
        String key = r.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
        return key;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "值班看板、升级策略或SLA报表请求参数无效"); }
    private static DomainException expired() { return new DomainException(401, "SESSION_EXPIRED", "当前会话已失效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "值班与SLA来源未通过核对"); }
}
