package cn.mop.api;

import static cn.mop.api.ApiResponse.*;

import cn.mop.domain.*;
import cn.mop.persistence.PostgresWeComRepository;
import cn.mop.persistence.PostgresWeComRepository.Access;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
@Profile("remote_test")
@ConditionalOnProperty(name = "MOP_WECOM_ENABLED", havingValue = "true")
public final class WeComController {
    private final WeComService service;
    private final PostgresWeComRepository repository;
    private final PlatformRepository platform;
    private final ProjectIdentityService identity;
    private final SessionService sessions;

    public WeComController(
            WeComService service,
            PostgresWeComRepository repository,
            PlatformRepository platform,
            ProjectIdentityService identity,
            SessionService sessions) {
        this.service = service;
        this.repository = repository;
        this.platform = platform;
        this.identity = identity;
        this.sessions = sessions;
    }

    /** Optional, explicitly configured disabled demo channel namespaces. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    DemoChannelScope demoChannels;

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/wecom/status")
    Object status(HttpServletRequest r) {
        Access a = access(r, "users:read");
        noQuery(r);
        return success(isDemo(a) ? demoChannels.status(a.tenantId(), false) : service.status(), r);
    }

    @PostMapping(value = "/wecom/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object check(HttpServletRequest r) {
        Access a = access(r, "users:write");
        rejectDemoOutbound(a);
        body(r, Set.of());
        String key = ProjectIdentityService.key(r);
        Object result =
                platform.idempotentAuthorized(
                        a.actorId(),
                        a.tenantId(),
                        "POST " + route(r),
                        key,
                        WeComService.hash("WECOM_CHECK_V1"),
                        a.permissionVersion(),
                        () -> {},
                        () -> {
                            var checked = service.check();
                            platform.audit(
                                    a.actorId(),
                                    a.tenantId(),
                                    "wecom.application.check",
                                    "wecom_application",
                                    null,
                                    Boolean.TRUE.equals(checked.get("ready"))
                                            ? "SUCCEEDED"
                                            : "FAILED",
                                    "REDACTED_PROVIDER_CHECK",
                                    a.traceId());
                            return checked;
                        });
        return success(result, r);
    }

    @GetMapping("/wecom/bindings")
    Object bindings(HttpServletRequest r) {
        Access a = access(r, "users:read");
        noQuery(r);
        return success(map("items", repository.bindings(a, corp(a)), "source", "postgresql"), r);
    }

    @GetMapping("/wecom/bindings/{userId}")
    Object targetBinding(@PathVariable String userId, HttpServletRequest r) {
        Access a = access(r, "users:read");
        noQuery(r);
        return success(
                map(
                        "tenant_id",
                        a.tenantId(),
                        "user_id",
                        userId,
                        "corp_id",
                        corp(a),
                        "binding",
                        repository.targetBinding(a, userId, corp(a)),
                        "source",
                        "postgresql"),
                r);
    }

    @PutMapping(value = "/wecom/bindings/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object bind(@PathVariable String userId, HttpServletRequest r) {
        Access a = access(r, "users:write");
        var n = body(r, Set.of("expected_version", "wecom_user_id", "active"));
        long version = version(n);
        String member = text(n, "wecom_user_id", 64);
        if (!n.get("active").isBoolean()) throw invalid();
        boolean active = n.get("active").booleanValue();
        // Resolve target scope before provider I/O, including on idempotent replays.
        identity.repository()
                .user(
                        new cn.mop.persistence.ProjectIdentityRepository.Access(
                                a.actorId(), a.tenantId(), a.permissionVersion(), a.traceId()),
                        userId);
        if (demoChannels != null) {
            demoChannels.validateBinding(a.tenantId(), active, member, false);
        }
        if (active) {
            service.verifyMember(member);
        }
        return success(
                platform.idempotentAuthorized(
                        a.actorId(),
                        a.tenantId(),
                        "PUT " + route(r),
                        ProjectIdentityService.key(r),
                        WeComService.hash(
                                "WECOM_BIND_V1\n"
                                        + userId
                                        + "\n"
                                        + version
                                        + "\n"
                                        + member
                                        + "\n"
                                        + active),
                        a.permissionVersion(),
                        () -> repository.authorizeBinding(a, userId),
                        () -> repository.bind(a, userId, corp(a), member, version, active)),
                r);
    }

    @PostMapping(value = "/wecom/test-messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object test(HttpServletRequest r, HttpServletResponse response) {
        Access a = access(r, "users:write");
        rejectDemoOutbound(a);
        var n = body(r, Set.of("expected_version"));
        if (version(n) != 0 || service.testUser().isBlank()) throw invalid();
        service.verifyMember(service.testUser());
        Object result =
                platform.idempotentAuthorized(
                        a.actorId(),
                        a.tenantId(),
                        "POST " + route(r),
                        ProjectIdentityService.key(r),
                        WeComService.hash("WECOM_FIXED_TEST_V1\n" + service.testUser()),
                        a.permissionVersion(),
                        () -> repository.authorizeSend(a, null),
                        () ->
                                repository.enqueueTest(
                                        a,
                                        corp(a),
                                        agent(a),
                                        service.testUser(),
                                        WeComService.TEST_MESSAGE));
        response.setStatus(202);
        return success(result, r);
    }

    @PostMapping(
            value = "/my/notifications/{id}/wecom",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    Object notification(
            @PathVariable String id, HttpServletRequest r, HttpServletResponse response) {
        Access a = access(r, "tickets:read");
        rejectDemoOutbound(a);
        var n = body(r, Set.of("expected_version"));
        if (version(n) != 0) throw invalid();
        Object result =
                platform.idempotentAuthorized(
                        a.actorId(),
                        a.tenantId(),
                        "POST " + route(r),
                        ProjectIdentityService.key(r),
                        WeComService.hash("WECOM_OWN_NOTIFICATION_V1\n" + id),
                        a.permissionVersion(),
                        () -> repository.authorizeSend(a, id),
                        () -> repository.enqueueNotification(a, corp(a), agent(a), id));
        response.setStatus(202);
        return success(result, r);
    }

    @GetMapping("/wecom/deliveries")
    Object deliveries(HttpServletRequest r) {
        Access a = access(r, "tickets:read");
        noQuery(r);
        boolean admin =
                platform.membership(a.actorId(), a.tenantId())
                        .orElseThrow()
                        .capabilities()
                        .contains("users:read");
        return success(
                map(
                        "items",
                        repository.deliveries(a, admin, corp(a), agent(a)),
                        "source",
                        "postgresql"),
                r);
    }

    @GetMapping("/wecom/deliveries/{id}")
    Object delivery(@PathVariable String id, HttpServletRequest r) {
        Access a = access(r, "tickets:read");
        noQuery(r);
        boolean admin =
                platform.membership(a.actorId(), a.tenantId())
                        .orElseThrow()
                        .capabilities()
                        .contains("users:read");
        return success(repository.delivery(a, id, admin, corp(a), agent(a)), r);
    }

    @GetMapping("/auth/wecom/options")
    Object options(HttpServletRequest r) {
        noQuery(r);
        return success(
                map(
                        "enabled",
                        service.loginEnabled(),
                        "start_url",
                        service.loginEnabled() ? "/api/v1/auth/wecom/start" : null),
                r);
    }

    @PostMapping(value = "/auth/wecom/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object start(HttpServletRequest r, HttpServletResponse response) {
        sameOrigin(r);
        var callbackOrigin = java.net.URI.create(service.callback());
        int callbackPort =
                callbackOrigin.getPort() < 0
                        ? ("https".equals(callbackOrigin.getScheme()) ? 443 : 80)
                        : callbackOrigin.getPort();
        if (!Objects.equals(callbackOrigin.getScheme(), r.getScheme())
                || !Objects.equals(callbackOrigin.getHost(), r.getServerName())
                || callbackPort != r.getServerPort())
            throw new DomainException(
                    503, "WECOM_CALLBACK_ORIGIN_MISMATCH", "请通过企业微信配置的业务域名打开登录页面");
        var n = body(r, Set.of("mode"));
        var start = service.startLogin(text(n, "mode", 8));
        Cookie cookie = new Cookie("MOP_WECOM_CORRELATION", start.browserCookie());
        cookie.setHttpOnly(true);
        cookie.setSecure(service.callback().startsWith("https://"));
        cookie.setPath("/api/v1/auth/wecom");
        cookie.setMaxAge(300);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return success(map("authorization_url", start.authorizationUrl(), "expires_in", 300), r);
    }

    @GetMapping("/auth/wecom/callback")
    void callback(HttpServletRequest r, HttpServletResponse response) throws java.io.IOException {
        boolean established = false;
        try {
            if (!r.getParameterMap().keySet().equals(Set.of("code", "state"))
                    || r.getParameterValues("code").length != 1
                    || r.getParameterValues("state").length != 1) throw invalid();
            String browser = null;
            if (r.getCookies() != null)
                for (var cookie : r.getCookies())
                    if ("MOP_WECOM_CORRELATION".equals(cookie.getName())) {
                        if (browser != null) throw invalid();
                        browser = cookie.getValue();
                    }
            if (!service.consumeLogin(r.getParameter("state"), browser))
                throw new DomainException(401, "WECOM_LOGIN_STATE_INVALID", "登录校验已失效，请重新发起");
            expireCorrelation(response);
            String member = service.identity(r.getParameter("code"));
            service.verifyMember(member);
            var binding =
                    repository
                            .login(service.tenant(), service.corp(), member)
                            .orElseThrow(
                                    () ->
                                            new DomainException(
                                                    403,
                                                    "WECOM_IDENTITY_NOT_BOUND",
                                                    "企业微信成员尚未绑定项目账号"));
            var account =
                    identity.repository()
                            .sessionState(binding.userId())
                            .orElseThrow(() -> new DomainException(401, "LOGIN_FAILED", "项目账号不可用"));
            var principal = new WeComPrincipal(binding.userId(), account.securityVersion());
            Object idle = identity.repository().policy().get("idle_minutes");
            sessions.establishProjectSession(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()),
                    idle instanceof Number n ? n.intValue() : null,
                    r,
                    response);
            established = true;
            r.getSession().setAttribute("mop.tenant_id", binding.tenantId());
            platform.audit(
                    binding.userId(),
                    binding.tenantId(),
                    "wecom.login",
                    "session",
                    null,
                    "SUCCEEDED",
                    "PROVIDER_IDENTITY_AND_EXPLICIT_BINDING_VERIFIED",
                    trace(r));
            response.sendRedirect("/assets");
        } catch (RuntimeException failure) {
            if (established) {
                if (r.getSession(false) != null) r.getSession(false).invalidate();
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
            String code =
                    failure instanceof DomainException d
                                    && Set.of(
                                                    "WECOM_LOGIN_STATE_INVALID",
                                                    "WECOM_IDENTITY_NOT_BOUND",
                                                    "WECOM_MEMBER_INACTIVE",
                                                    "WECOM_TRUSTED_IP_REQUIRED")
                                            .contains(d.code)
                            ? ((DomainException) failure).code
                            : "WECOM_LOGIN_FAILED";
            try {
                platform.audit(
                        null,
                        service.tenant(),
                        "wecom.login",
                        "session",
                        null,
                        "DENIED",
                        code,
                        trace(r));
            } catch (RuntimeException auditUnavailable) {
                code = "WECOM_LOGIN_FAILED";
            }
            // Invalid callbacks do not destroy another active, authorized project session.
            response.sendRedirect("/login?auth_error=" + code);
        }
    }

    record WeComPrincipal(String userId, long securityVersion) implements ProjectSessionIdentity {
        public String getName() {
            return userId;
        }

        public String identitySource() {
            return "wecom";
        }
    }

    private void expireCorrelation(HttpServletResponse response) {
        Cookie cookie = new Cookie("MOP_WECOM_CORRELATION", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(service.callback().startsWith("https://"));
        cookie.setPath("/api/v1/auth/wecom");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private boolean isDemo(Access access) {
        return demoChannels != null && demoChannels.contains(access.tenantId());
    }

    private String corp(Access access) {
        return isDemo(access) ? demoChannels.corp(access.tenantId()) : service.corp();
    }

    private int agent(Access access) {
        return isDemo(access) ? 1 : service.agent();
    }

    private void rejectDemoOutbound(Access access) {
        if (demoChannels != null) {
            demoChannels.rejectOutbound(access.tenantId());
        }
    }

    private Access access(HttpServletRequest r, String capability) {
        if (r.getUserPrincipal() == null || r.getSession(false) == null)
            throw new DomainException(401, "SESSION_EXPIRED", "请先登录项目账号");
        Object selected = r.getSession(false).getAttribute("mop.tenant_id");
        if (!(selected instanceof String tenant)
                || (!service.tenant().equals(tenant)
                        && (demoChannels == null || !demoChannels.contains(tenant))))
            throw new DomainException(404, "NOT_FOUND", "对象不存在或不在授权范围");
        String actor = r.getUserPrincipal().getName();
        var member =
                platform.membership(actor, tenant)
                        .filter(Membership::active)
                        .orElseThrow(
                                () -> new DomainException(401, "SESSION_EXPIRED", "当前成员关系已失效"));
        if (!member.capabilities().contains(capability))
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份没有企业微信操作权限");
        return new Access(tenant, actor, member.permissionVersion(), trace(r));
    }

    private static JsonNode body(HttpServletRequest r, Set<String> keys) {
        noQuery(r);
        var n = AssetExportJobController.body(r);
        var names = new HashSet<String>();
        n.fieldNames().forEachRemaining(names::add);
        if (!n.isObject() || !names.equals(keys)) throw invalid();
        return n;
    }

    private static void noQuery(HttpServletRequest r) {
        if (r.getQueryString() != null) throw invalid();
    }

    private static void sameOrigin(HttpServletRequest r) {
        String origin = r.getHeader("Origin");
        if (origin == null) return;
        String expected =
                r.getScheme()
                        + "://"
                        + r.getServerName()
                        + (("http".equals(r.getScheme()) && r.getServerPort() == 80
                                        || "https".equals(r.getScheme())
                                                && r.getServerPort() == 443)
                                ? ""
                                : ":" + r.getServerPort());
        if (!expected.equals(origin)) throw new DomainException(403, "CSRF_INVALID", "登录请求必须同源");
    }

    private static long version(JsonNode n) {
        var v = n.get("expected_version");
        if (v == null
                || !v.isIntegralNumber()
                || !v.canConvertToLong()
                || v.longValue() < 0
                || v.longValue() > 9007199254740991L) throw invalid();
        return v.longValue();
    }

    private static String text(JsonNode n, String key, int max) {
        var v = n.get(key);
        if (v == null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length() > max)
            throw invalid();
        return v.textValue();
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "企业微信请求参数无效");
    }
}
