package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader.Access;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.mop.api.ApiResponse.map;
import static cn.mop.api.ApiResponse.success;

/** Read-only dashboard fact projection; customer roles retain reporter-only ticket visibility. */
@RestController
@RequestMapping("/api/v1/dashboard/pending-tickets")
@Profile("remote_test")
public final class DashboardPendingTicketsController {
    private final PlatformRepository platform;
    private final DashboardPendingTicketsReader reader;

    public DashboardPendingTicketsController(PlatformRepository platform, DashboardPendingTicketsReader reader) {
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
        var member = platform.membership(actor, tenant).orElseThrow(DashboardPendingTicketsController::expired);
        if (!member.active() || member.permissionVersion() < 1 || member.permissionVersion() > 9_007_199_254_740_990L
                || !tenant.equals(member.tenantId())
                || !actor.equals(member.userId())) {
            throw expired();
        }
        if (!member.capabilities().contains("dashboard:read") || !member.capabilities().contains("tickets:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前授权不允许读取首页工单汇总");
        }
        if (!request.getParameterMap().isEmpty()) {
            throw new DomainException(400, "VALIDATION_ERROR", "待办工单汇总不接受租户、角色、排序或数量覆盖");
        }
        var snapshot = reader.read(new Access(actor, tenant, member.permissionVersion()));
        String expectedScope = cn.mop.domain.Capabilities.customer(member.role()) ? "REPORTER_ONLY" : "CURRENT_TENANT_INTERNAL";
        if (snapshot == null || !tenant.equals(snapshot.tenantId().toString()) || !actor.equals(snapshot.actorId().toString())
                || member.permissionVersion() != snapshot.permissionVersion() || !expectedScope.equals(snapshot.scope())) {
            throw new DomainException(503, "UPSTREAM_UNAVAILABLE", "待办工单汇总不属于当前授权范围");
        }
        DashboardReadAuthorization.recheck(request, platform, member);
        return success(map("tenant_id", tenant, "actor_id", actor, "permission_version", snapshot.permissionVersion(),
                "observed_at", snapshot.observedAt().toString(), "scope", snapshot.scope(), "source", "postgresql",
                "metric_version", DashboardPendingTicketsReader.METRIC_VERSION, "registrations", snapshot.registrations(),
                "known_pending", snapshot.knownPending(), "terminal", snapshot.terminal(), "unconfigured", snapshot.unconfigured(),
                "pending_tickets", snapshot.pendingTickets(), "state", snapshot.unconfigured() == 0 ? "AVAILABLE" : "PARTIAL",
                "unavailable_reason", snapshot.unconfigured() == 0 ? null : "REGISTRATIONS_WITHOUT_WORKFLOW",
                "item_limit", DashboardPendingTicketsReader.ITEM_LIMIT, "sort", "REGISTRATION_CREATED_AT_DESC_ID_DESC",
                "items", snapshot.items().stream().map(item -> map("ticket_id", item.ticketId().toString(),
                        "receipt_no", item.receiptNo(), "title", item.title(), "registered_at", item.registeredAt().toString(),
                        "workflow_model", item.workflowModel(), "state", item.state(), "version", item.version())).toList(),
                "overdue_sla", null, "writes_performed", false), request);
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员关系已失效");
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
