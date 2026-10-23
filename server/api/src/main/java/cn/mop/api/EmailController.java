package cn.mop.api;

import static cn.mop.api.ApiResponse.*;

import cn.mop.domain.*;
import cn.mop.persistence.PostgresEmailRepository;
import cn.mop.persistence.PostgresEmailRepository.Access;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
@Profile("remote_test")
@ConditionalOnProperty(name = "MOP_EMAIL_ENABLED", havingValue = "true")
public final class EmailController {
    private final EmailService service;
    private final PostgresEmailRepository repository;
    private final PlatformRepository platform;

    public EmailController(
            EmailService service, PostgresEmailRepository repository, PlatformRepository platform) {
        this.service = service;
        this.repository = repository;
        this.platform = platform;
    }

    /** Optional, explicitly configured disabled demo channel namespaces. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    DemoChannelScope demoChannels;

    @ModelAttribute
    void headers(HttpServletResponse r) {
        r.setHeader("Cache-Control", "no-store");
        r.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/email/status")
    Object status(HttpServletRequest r) {
        Access a = access(r, "users:read");
        noQuery(r);
        return success(isDemo(a) ? demoChannels.status(a.tenantId(), true) : service.status(), r);
    }

    @PostMapping(value = "/email/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object check(HttpServletRequest r) {
        Access a = access(r, "users:write");
        rejectDemoOutbound(a);
        body(r, Set.of());
        Object result =
                platform.idempotentAuthorized(
                        a.actorId(),
                        a.tenantId(),
                        "POST " + route(r),
                        ProjectIdentityService.key(r),
                        WeComService.hash("EMAIL_CHECK_V1\n" + sender(a)),
                        a.permissionVersion(),
                        () -> {},
                        () -> {
                            var checked = service.check();
                            platform.audit(
                                    a.actorId(),
                                    a.tenantId(),
                                    "email.connection.check",
                                    "email_channel",
                                    null,
                                    Boolean.TRUE.equals(checked.get("ready"))
                                            ? "SUCCEEDED"
                                            : "FAILED",
                                    "REDACTED_SMTP_AUTH_CHECK_NO_MESSAGE",
                                    a.traceId());
                            return checked;
                        });
        return success(result, r);
    }

    @GetMapping("/email/bindings")
    Object bindings(HttpServletRequest r) {
        Access a = access(r, "users:read");
        noQuery(r);
        return success(map("items", repository.bindings(a, sender(a)), "source", "postgresql"), r);
    }

    @GetMapping("/my/email-binding")
    Object ownBinding(HttpServletRequest r) {
        Access a = access(r, "tickets:read");
        noQuery(r);
        return success(
                map(
                        "tenant_id",
                        a.tenantId(),
                        "owner_id",
                        a.actorId(),
                        "binding",
                        repository.ownBinding(a, sender(a)),
                        "source",
                        "postgresql"),
                r);
    }

    @GetMapping("/email/bindings/{userId}")
    Object targetBinding(@PathVariable String userId, HttpServletRequest r) {
        Access a = access(r, "users:read");
        noQuery(r);
        return success(
                map(
                        "tenant_id",
                        a.tenantId(),
                        "user_id",
                        userId,
                        "sender",
                        sender(a),
                        "binding",
                        repository.targetBinding(a, userId, sender(a)),
                        "source",
                        "postgresql"),
                r);
    }

    @PutMapping(value = "/email/bindings/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object bind(@PathVariable String userId, HttpServletRequest r) {
        Access a = access(r, "users:write");
        var n = body(r, Set.of("expected_version", "email", "active"));
        long expected = version(n);
        if (!n.path("email").isTextual() || !n.path("active").isBoolean()) throw invalid();
        // Scope is checked inside the command transaction, including on receipt replay.
        boolean active = n.get("active").booleanValue();
        if (demoChannels != null) {
            demoChannels.validateBinding(a.tenantId(), active, n.get("email").textValue(), true);
        }
        String email = service.bindingAddress(n.get("email").textValue(), active);
        return success(
                platform.idempotentAuthorized(
                        a.actorId(),
                        a.tenantId(),
                        "PUT " + route(r),
                        ProjectIdentityService.key(r),
                        WeComService.hash(
                                "EMAIL_BIND_V1\n"
                                        + sender(a)
                                        + "\n"
                                        + userId
                                        + "\n"
                                        + expected
                                        + "\n"
                                        + email
                                        + "\n"
                                        + active),
                        a.permissionVersion(),
                        () -> repository.authorizeBinding(a, userId),
                        () -> repository.bind(a, userId, sender(a), email, expected, active)),
                r);
    }

    @PostMapping(value = "/email/test-messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object test(HttpServletRequest r, HttpServletResponse response) {
        Access a = access(r, "users:write");
        rejectDemoOutbound(a);
        var n = body(r, Set.of("expected_version"));
        if (version(n) != 0) throw invalid();
        Object result =
                platform.idempotentAuthorized(
                        a.actorId(),
                        a.tenantId(),
                        "POST " + route(r),
                        ProjectIdentityService.key(r),
                        WeComService.hash(
                                "EMAIL_FIXED_TEST_V1\n"
                                        + sender(a)
                                        + "\n"
                                        + service.testRecipient()),
                        a.permissionVersion(),
                        () -> repository.authorizeSend(a, null),
                        () -> {
                            service.requireDispatch();
                            return repository.enqueueTest(
                                    a,
                                    sender(a),
                                    service.testRecipient(),
                                    EmailService.TEST_SUBJECT,
                                    EmailService.TEST_MESSAGE);
                        });
        response.setStatus(202);
        return success(result, r);
    }

    @PostMapping(
            value = "/my/notifications/{id}/email",
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
                        WeComService.hash("EMAIL_OWN_NOTIFICATION_V1\n" + sender(a) + "\n" + id),
                        a.permissionVersion(),
                        () -> repository.authorizeSend(a, id),
                        () -> {
                            service.requireDispatch();
                            return repository.enqueueNotification(a, sender(a), id);
                        });
        response.setStatus(202);
        return success(result, r);
    }

    @GetMapping("/email/deliveries")
    Object deliveries(HttpServletRequest r) {
        Access a = access(r, "tickets:read");
        noQuery(r);
        return success(
                map(
                        "items",
                        repository.deliveries(a, administrator(a), sender(a)),
                        "source",
                        "postgresql"),
                r);
    }

    @GetMapping("/email/deliveries/{id}")
    Object delivery(@PathVariable String id, HttpServletRequest r) {
        Access a = access(r, "tickets:read");
        noQuery(r);
        return success(repository.delivery(a, id, administrator(a), sender(a)), r);
    }

    private boolean administrator(Access a) {
        return platform.membership(a.actorId(), a.tenantId())
                .filter(Membership::active)
                .orElseThrow()
                .capabilities()
                .contains("users:read");
    }

    private boolean isDemo(Access access) {
        return demoChannels != null && demoChannels.contains(access.tenantId());
    }

    private String sender(Access access) {
        return isDemo(access) ? demoChannels.sender(access.tenantId()) : service.sender();
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
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份没有邮件操作权限");
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

    private static long version(JsonNode n) {
        var v = n.get("expected_version");
        if (v == null
                || !v.isIntegralNumber()
                || !v.canConvertToLong()
                || v.longValue() < 0
                || v.longValue() > 9007199254740991L) throw invalid();
        return v.longValue();
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "邮件请求参数无效");
    }
}
