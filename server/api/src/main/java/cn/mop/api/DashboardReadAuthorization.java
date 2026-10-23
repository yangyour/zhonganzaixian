package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import jakarta.servlet.http.HttpServletRequest;

/** Rechecks the live session and complete effective membership after a potentially long read. */
final class DashboardReadAuthorization {
    private DashboardReadAuthorization() { }

    static void recheck(HttpServletRequest request, PlatformRepository platform, Membership expected) {
        var session = request.getSession(false);
        if (request.getUserPrincipal() == null || session == null
                || !expected.userId().equals(request.getUserPrincipal().getName())
                || !expected.tenantId().equals(session.getAttribute("mop.tenant_id"))) {
            throw expired();
        }
        var current = platform.membership(expected.userId(), expected.tenantId()).orElseThrow(DashboardReadAuthorization::expired);
        if (!expected.equals(current)) {
            throw expired();
        }
        ProjectSessionValidationFilter.recheckBeforeReturn(request);
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "读取期间会话或成员权限已变更，请重新读取");
    }
}
