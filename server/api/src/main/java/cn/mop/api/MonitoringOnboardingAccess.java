package cn.mop.api;

import cn.mop.domain.Asset;
import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/** Shared current-session and asset boundary for monitoring preparation and probes. */
final class MonitoringOnboardingAccess {
    private MonitoringOnboardingAccess() {}

    static Membership member(PlatformRepository platform, HttpServletRequest request, boolean privileged) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null) throw expired();
        String actor = request.getUserPrincipal().getName();
        Object chosen = request.getSession(false).getAttribute("mop.tenant_id");
        if (!(chosen instanceof String tenant) || !MonitoringPresetRegistry.uuid(actor) || !MonitoringPresetRegistry.uuid(tenant)) throw expired();
        var member = platform.membership(actor, tenant).orElseThrow(MonitoringOnboardingAccess::expired);
        if (!member.active() || member.permissionVersion() < 1 || !member.userId().equals(actor) || !member.tenantId().equals(tenant)) throw expired();
        if (!member.capabilities().containsAll(List.of("assets:read", "configurations:read")) ||
            privileged && (!member.role().equals("padmin") || !member.capabilities().contains("configurations:write")))
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份没有监控接入读取或准备权限");
        return member;
    }

    static void recheck(PlatformRepository platform, Membership before, HttpServletRequest request, boolean privileged) {
        if (!before.equals(member(platform, request, privileged))) throw expired();
    }

    static Asset asset(PlatformRepository platform, Membership member, String id) {
        var asset = platform.authorizedAsset(member.tenantId(), member.userId(), member.permissionVersion(), id)
            .orElseThrow(MonitoringOnboardingAccess::notFound);
        if (!asset.tenantId().equals(member.tenantId()) || !asset.id().equals(id)) throw notFound();
        return asset;
    }

    static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty() || !request.getParameterMap().isEmpty()) throw invalid();
    }

    static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "只接受明确的监控接入参数，不接受地址、凭据或租户覆盖"); }
    static DomainException expired() { return new DomainException(401, "SESSION_EXPIRED", "当前会话、资产或授权范围已变化"); }
    static DomainException notFound() { return new DomainException(404, "NOT_FOUND", "当前授权范围没有匹配的资产或监控目标"); }
}
