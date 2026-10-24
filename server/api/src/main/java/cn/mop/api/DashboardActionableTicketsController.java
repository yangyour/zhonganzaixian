package cn.mop.api;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardActionableTicketsReader;
import cn.mop.domain.dashboard.DashboardActionableTicketsReader.Page;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import cn.mop.domain.dashboard.DashboardTicketSla;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.mop.api.ApiResponse.map;
import static cn.mop.api.ApiResponse.success;

/** Additive M1-6 endpoint: the homepage and complete drill-down share actionable counts and service-side SLA. */
@RestController
@RequestMapping("/api/v1/dashboard/actionable-tickets")
@Profile("remote_test")
public final class DashboardActionableTicketsController {
    /** Current membership and effective capabilities are verified before and after the database snapshot. */
    private final PlatformRepository platform;

    /** Real PostgreSQL population and clock evaluation; missing dependencies never become a fixture response. */
    private final DashboardActionableTicketsReader reader;

    public DashboardActionableTicketsController(PlatformRepository platform, DashboardActionableTicketsReader reader) {
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
        var member = platform.membership(actor, tenant).orElseThrow(DashboardActionableTicketsController::expired);
        if (!member.active() || member.permissionVersion() < 1
                || member.permissionVersion() > DashboardActionableTicketsReader.MAX_SAFE_INTEGER
                || !tenant.equals(member.tenantId()) || !actor.equals(member.userId())) {
            throw expired();
        }
        if (!member.capabilities().contains("dashboard:read") || !member.capabilities().contains("tickets:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前权限不允许读取待处理工单与SLA汇总");
        }
        var page = page(request);
        var result = reader.read(new DashboardPendingTicketsReader.Access(actor, tenant, member.permissionVersion()), page);
        String expectedScope = Capabilities.customer(member.role()) ? "REPORTER_ONLY" : "CURRENT_TENANT_INTERNAL";
        if (result == null || !tenant.equals(result.tenantId().toString()) || !actor.equals(result.actorId().toString())
                || result.permissionVersion() != member.permissionVersion() || !expectedScope.equals(result.scope())
                || !page.equals(result.page())) {
            throw new DomainException(503, "UPSTREAM_UNAVAILABLE", "待处理工单投影不属于当前授权范围或请求分页");
        }
        var reasons = new ArrayList<String>();
        if (result.unconfigured() > 0) {
            reasons.add("REGISTRATIONS_WITHOUT_WORKFLOW");
        }
        if (result.unknownEvaluated() > 0) {
            reasons.add("SLA_INPUTS_OR_READER_UNAVAILABLE");
        }
        DashboardReadAuthorization.recheck(request, platform, member);
        return success(map("tenant_id", tenant, "actor_id", actor, "permission_version", result.permissionVersion(),
                "observed_at", result.observedAt().toString(), "scope", result.scope(), "source", "postgresql",
                "metric_version", DashboardActionableTicketsReader.METRIC_VERSION,
                "projection_version", DashboardActionableTicketsReader.VIEW_VERSION,
                "excluded_states", DashboardActionableTicketsReader.EXCLUDED_STATES,
                "registrations", result.registrations(), "known_pending", result.knownPending(), "excluded", result.excluded(),
                "unconfigured", result.unconfigured(), "pending_tickets", result.pendingTickets(), "overdue_sla", result.overdueSla(),
                "known_overdue", result.knownOverdue(), "unknown_sla", result.unknownSla(),
                "evaluated_pending", result.knownPending(), "unevaluated_pending", 0L, "evaluation_limit", null,
                "unavailable_reasons", reasons, "item_limit", page.size(), "page", page.number(), "page_size", page.size(),
                "total_pages", result.totalPages(), "sort", "REGISTRATION_CREATED_AT_DESC_ID_DESC", "writes_performed", false,
                "items", result.items().stream().map(item -> map("ticket_id", item.ticket().ticketId().toString(),
                        "receipt_no", item.ticket().receiptNo(), "title", item.ticket().title(),
                        "registered_at", item.ticket().registeredAt().toString(), "workflow_model", item.ticket().workflowModel(),
                        "state", item.ticket().state(), "version", item.ticket().version(), "priority", item.sla().priority(),
                        "frozen_service_tier", item.sla().frozenServiceTier(), "sla_metric_version", item.sla().metricVersion(),
                        "sla_version", item.sla().version(), "overdue", item.sla().overdue(),
                        "response_clock", clock(item.sla().response()), "recovery_clock", clock(item.sla().recovery()))).toList()), request);
    }

    /** Reject duplicate, unrecognized and unsafe numeric parameters before requesting an authorized read. */
    private static Page page(HttpServletRequest request) {
        if (!Set.of("page", "page_size").containsAll(request.getParameterMap().keySet())) {
            throw invalidPage();
        }
        try {
            long number = positiveParameter(request, "page", 1);
            long size = positiveParameter(request, "page_size", 5);
            return new Page(number, Math.toIntExact(size));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw invalidPage();
        }
    }

    private static long positiveParameter(HttpServletRequest request, String name, int defaultValue) {
        var values = request.getParameterValues(name);
        if (values == null) {
            return defaultValue;
        }
        if (values.length != 1 || values[0] == null || !values[0].matches("[1-9][0-9]{0,15}")) {
            throw invalidPage();
        }
        return Long.parseLong(values[0]);
    }

    private static Map<String, Object> clock(DashboardTicketSla.Clock clock) {
        return map("status", clock.status(), "breached", clock.breached(), "missing_reason", clock.missingReason(),
                "deadline_at", clock.deadline() == null ? null : clock.deadline().toString(),
                "completed_at", clock.completedAt() == null ? null : clock.completedAt().toString(),
                "remaining_millis", clock.remainingMillis(), "remaining_basis", clock.remainingBasis());
    }

    private static DomainException invalidPage() {
        return new DomainException(400, "VALIDATION_ERROR", "只接受安全正整数page和1至100的page_size，参数不得重复");
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员关系已失效");
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
