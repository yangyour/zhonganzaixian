package cn.mop.api;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardPendingTicketSlaReader;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import cn.mop.domain.dashboard.DashboardTicketSla;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.mop.api.ApiResponse.map;
import static cn.mop.api.ApiResponse.success;

/** Explicit V2 endpoint leaves strict V1 clients unchanged and never delegates SLA calculations to browsers. */
@RestController
@RequestMapping("/api/v1/dashboard/pending-tickets/sla")
@Profile("remote_test")
public final class DashboardPendingTicketSlaController {
    /** Current tenant membership is checked before any aggregate is requested. */
    private final PlatformRepository platform;

    /** Real database snapshot and service-side clock evaluation; no browser fallback. */
    private final DashboardPendingTicketSlaReader reader;

    public DashboardPendingTicketSlaController(PlatformRepository platform, DashboardPendingTicketSlaReader reader) {
        this.platform = platform;
        this.reader = reader;
    }

    @GetMapping
    Object read(HttpServletRequest request) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null
                || !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)) {
            throw expired();
        }
        String actor = request.getUserPrincipal().getName();
        if (!uuid(actor) || !uuid(tenant)) {
            throw expired();
        }
        var member = platform.membership(actor, tenant).orElseThrow(DashboardPendingTicketSlaController::expired);
        if (!member.active() || member.permissionVersion() < 1 || member.permissionVersion() > 9_007_199_254_740_990L
                || !tenant.equals(member.tenantId()) || !actor.equals(member.userId())) {
            throw expired();
        }
        if (!member.capabilities().contains("dashboard:read") || !member.capabilities().contains("tickets:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前权限不允许读取待办工单SLA汇总");
        }
        if (!request.getParameterMap().isEmpty()) {
            throw new DomainException(400, "VALIDATION_ERROR", "SLA汇总不接受租户、时刻、状态或技术上限覆盖");
        }
        var result = reader.read(new DashboardPendingTicketsReader.Access(actor, tenant, member.permissionVersion()));
        var base = result == null ? null : result.base();
        String expectedScope = Capabilities.customer(member.role()) ? "REPORTER_ONLY" : "CURRENT_TENANT_INTERNAL";
        if (base == null || !tenant.equals(base.tenantId().toString()) || !actor.equals(base.actorId().toString())
                || base.permissionVersion() != member.permissionVersion() || !expectedScope.equals(base.scope())) {
            throw new DomainException(503, "UPSTREAM_UNAVAILABLE", "SLA汇总不属于当前授权范围");
        }
        var reasons = new ArrayList<String>();
        if (base.unconfigured() > 0) {
            reasons.add("REGISTRATIONS_WITHOUT_WORKFLOW");
        }
        if (result.unknownEvaluated() > 0) {
            reasons.add("SLA_INPUTS_OR_READER_UNAVAILABLE");
        }
        if (result.unevaluatedPending() > 0) {
            reasons.add("EVALUATION_LIMIT_EXCEEDED");
        }
        DashboardReadAuthorization.recheck(request, platform, member);
        return success(map("tenant_id", tenant, "actor_id", actor, "permission_version", base.permissionVersion(),
                "observed_at", base.observedAt().toString(), "scope", base.scope(), "source", "postgresql",
                "projection_version", DashboardPendingTicketSlaReader.VIEW_VERSION,
                "registrations", base.registrations(), "known_pending", base.knownPending(), "terminal", base.terminal(),
                "unconfigured", base.unconfigured(), "pending_tickets", base.pendingTickets(), "overdue_sla", result.overdueSla(),
                "known_overdue", result.knownOverdue(), "unknown_sla", result.unknownSla(), "evaluated_pending", result.evaluatedPending(),
                "unevaluated_pending", result.unevaluatedPending(), "evaluation_limit", DashboardPendingTicketSlaReader.EVALUATION_LIMIT,
                "unavailable_reasons", reasons, "item_limit", DashboardPendingTicketsReader.ITEM_LIMIT,
                "sort", "REGISTRATION_CREATED_AT_DESC_ID_DESC", "writes_performed", false,
                "items", result.items().stream().map(item -> map("ticket_id", item.ticket().ticketId().toString(),
                        "receipt_no", item.ticket().receiptNo(), "title", item.ticket().title(),
                        "registered_at", item.ticket().registeredAt().toString(), "workflow_model", item.ticket().workflowModel(),
                        "state", item.ticket().state(), "version", item.ticket().version(), "priority", item.sla().priority(),
                        "frozen_service_tier", item.sla().frozenServiceTier(), "sla_metric_version", item.sla().metricVersion(),
                        "sla_version", item.sla().version(), "overdue", item.sla().overdue(),
                        "response_clock", clock(item.sla().response()), "recovery_clock", clock(item.sla().recovery()))).toList()), request);
    }

    private static Map<String, Object> clock(DashboardTicketSla.Clock clock) {
        return map("status", clock.status(), "breached", clock.breached(), "missing_reason", clock.missingReason(),
                "deadline_at", clock.deadline() == null ? null : clock.deadline().toString(),
                "completed_at", clock.completedAt() == null ? null : clock.completedAt().toString(),
                "remaining_millis", clock.remainingMillis(), "remaining_basis", clock.remainingBasis());
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员关系已失效");
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
