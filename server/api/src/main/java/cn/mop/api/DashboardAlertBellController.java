package cn.mop.api;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardAlertBellReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.mop.api.ApiResponse.map;
import static cn.mop.api.ApiResponse.success;

/** Read-only M1-8 bell; a successful read neither acknowledges events nor sends a notification. */
@RestController
@RequestMapping("/api/v1/dashboard/alert-bell")
@Profile("remote_test")
public final class DashboardAlertBellController {
    /** Fresh membership checks before and after the database snapshot. */
    private final PlatformRepository platform;

    /** Restricted, same-snapshot count and latest event facts. */
    private final DashboardAlertBellReader reader;

    public DashboardAlertBellController(PlatformRepository platform, DashboardAlertBellReader reader) {
        this.platform = platform;
        this.reader = reader;
    }

    @GetMapping
    Object read(HttpServletRequest request) {
        Membership before = access(request);
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty() || request.getContentLengthLong() > 0) {
            throw new DomainException(400, "VALIDATION_ERROR", "告警铃铛不接受租户、状态或数量覆盖");
        }
        var snapshot = reader.read(new DashboardAlertBellReader.Access(before.userId(), before.tenantId(), before.permissionVersion()));
        if (snapshot == null || !before.tenantId().equals(snapshot.tenantId().toString())
                || !before.userId().equals(snapshot.actorId().toString()) || before.permissionVersion() != snapshot.permissionVersion()) {
            throw new DomainException(503, "UPSTREAM_UNAVAILABLE", "告警汇总不属于当前授权范围");
        }
        if (!before.equals(access(request))) {
            throw expired();
        }
        return success(map("tenant_id", before.tenantId(), "actor_id", before.userId(),
                "permission_version", before.permissionVersion(), "observed_at", snapshot.observedAt().toString(),
                "scope", "CURRENT_TENANT_INTERNAL", "source", "postgresql", "metric_version", DashboardAlertBellReader.METRIC_VERSION,
                "filter", map("monitor_state", "PROBLEM", "handling_state", "NEW"), "total", snapshot.total(), "item_limit", 5,
                "sort", "LAST_OCCURRED_AT_DESC_ID_DESC", "items", snapshot.items().stream().map(item -> map(
                        "event_id", item.eventId().toString(), "asset_id", item.assetId().toString(), "rule_name", item.ruleName(),
                        "severity", item.severity(), "monitor_state", item.monitorState(), "handling_state", item.handlingState(),
                        "suppression_state", item.suppressionState(), "occurrence_count", item.occurrenceCount(),
                        "last_occurred_at", item.lastOccurredAt().toString(), "version", item.version())).toList(),
                "writes_performed", false), request);
    }

    private Membership access(HttpServletRequest request) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null
                || !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)) {
            throw expired();
        }
        String actor = request.getUserPrincipal().getName();
        if (!uuid(actor) || !uuid(tenant)) {
            throw expired();
        }
        var member = platform.membership(actor, tenant).orElseThrow(DashboardAlertBellController::expired);
        if (!member.active() || !actor.equals(member.userId()) || !tenant.equals(member.tenantId())
                || member.permissionVersion() < 1 || member.permissionVersion() > DashboardAlertBellReader.MAX_SAFE_INTEGER) {
            throw expired();
        }
        if (!Capabilities.internal(member.role()) || !member.capabilities().contains("dashboard:read")
                || !member.capabilities().contains("tickets:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前授权不可读取内部未确认告警");
        }
        return member;
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前成员或权限版本已失效，请重新读取");
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
