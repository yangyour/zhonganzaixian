package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.NotificationControlStore;
import cn.mop.persistence.NotificationControlStore.Access;
import cn.mop.persistence.NotificationControlStore.Entry;
import cn.mop.persistence.NotificationControlStore.Kind;
import cn.mop.persistence.NotificationRoutingService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

/** Tenant operator workspace and owner preference publication; normal session/CSRF middleware remains required. */
@RestController
@RequestMapping("/api/v1")
@Profile("remote_test")
public final class NotificationRoutingController {
    private final PlatformRepository platform;
    private final NotificationControlStore store;
    private final NotificationRoutingService routing;
    private final boolean enabled;

    public NotificationRoutingController(PlatformRepository platform, NotificationControlStore store,
                                         NotificationRoutingService routing,
                                         @Value("${MOP_NOTIFICATION_ROUTING_ENABLED:false}") boolean enabled) {
        this.platform = platform;
        this.store = store;
        this.routing = routing;
        this.enabled = enabled;
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/notification-routing/policy")
    Object policy(HttpServletRequest request) {
        noQuery(request);
        var subject = subject(request, "ticket_notes:read_internal");
        var result = map("record", dto(routing.policy(subject.access())),
                "external_dispatch_enabled", false, "customer_production_approved", false);
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(result, request);
    }

    @PostMapping(value = "/notification-routing/policy", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object policyWrite(HttpServletRequest request) {
        Access access = access(request, "configurations:write");
        JsonNode body = body(request, Set.of("expected_version", "policy"));
        return success(dto(routing.savePolicy(access, version(body), command(request), body.get("policy"))), request);
    }

    @GetMapping("/my/notification-preferences/publication")
    Object publication(HttpServletRequest request) {
        noQuery(request);
        var subject = subject(request, "notification_preferences:read_own");
        var result = map("record", dto(routing.preference(subject.access())), "external_dispatch_enabled", false);
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(result, request);
    }

    @PostMapping(value = "/my/notification-preferences/publication", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object publish(HttpServletRequest request) {
        Access access = access(request, "notification_preferences:write_own");
        JsonNode body = body(request, Set.of("expected_version", "draft_id", "draft_version", "published"));
        if (!body.path("draft_version").isIntegralNumber() || !body.path("draft_version").canConvertToLong()
                || body.path("draft_version").asLong() < 1 || !body.path("published").isBoolean()) {
            throw invalid();
        }
        return success(dto(routing.publishPreference(access, version(body), command(request), uuid(body.path("draft_id").asText()),
                body.path("draft_version").longValue(), body.path("published").booleanValue())), request);
    }

    @PostMapping(value = "/notification-routing/bindings", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object binding(HttpServletRequest request) {
        Access access = access(request, "configurations:write");
        JsonNode body = body(request, Set.of("expected_version", "binding"));
        return success(dto(routing.saveBinding(access, version(body), command(request), body.get("binding"))), request);
    }

    @GetMapping("/notification-routing/bindings/{receiver}/{channel}")
    Object bindingRead(@PathVariable String receiver, @PathVariable String channel, HttpServletRequest request) {
        noQuery(request);
        cn.mop.domain.notification.DeliveryReceiptState.Channel selected;
        try {
            selected = cn.mop.domain.notification.DeliveryReceiptState.Channel.valueOf(channel);
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
        var subject = subject(request, "ticket_notes:read_internal");
        var result = map("record", dto(routing.binding(subject.access(), uuid(receiver), selected)));
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(result, request);
    }

    @GetMapping("/notification-routing/entries")
    Object list(HttpServletRequest request) {
        var subject = subject(request, "ticket_notes:read_internal");
        Access access = subject.access();
        Set<String> allowed = Set.of("kind", "page_size", "before_time", "before_id");
        request.getParameterMap().forEach((key, values) -> {
            if (!allowed.contains(key) || values.length != 1 || values[0].isBlank()) {
                throw invalid();
            }
        });
        Kind kind = kind(request.getParameter("kind"));
        String rawLimit = request.getParameter("page_size");
        if (rawLimit != null && !rawLimit.matches("[1-9][0-9]?|100")) {
            throw invalid();
        }
        int limit = rawLimit == null ? 25 : Integer.parseInt(rawLimit);
        String before = request.getParameter("before_time");
        String beforeId = request.getParameter("before_id");
        Instant time = null;
        if ((before == null) != (beforeId == null)) {
            throw invalid();
        }
        if (before != null) {
            try {
                time = Instant.parse(before);
            } catch (RuntimeException failure) {
                throw invalid();
            }
        }
        var page = store.page(access, kind, limit, time, beforeId == null ? null : uuid(beforeId));
        var result = map("items", page.items().stream().map(NotificationRoutingController::dto).toList(),
                "next_id", page.nextId(), "next_time", page.nextTime(), "source", "postgresql");
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(result, request);
    }

    @GetMapping("/notification-routing/entries/{kind}/{id}")
    Object detail(@PathVariable String kind, @PathVariable String id, HttpServletRequest request) {
        noQuery(request);
        var subject = subject(request, "ticket_notes:read_internal");
        var result = dto(routing.detail(subject.access(), kind(kind), uuid(id)));
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(result, request);
    }

    @PostMapping(value = "/notification-routing/requests/{id}/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object plan(@PathVariable String id, HttpServletRequest request) {
        Access access = access(request, "configurations:write");
        JsonNode body = body(request, Set.of("expected_version"));
        UUID command = command(request);
        Entry result = routing.plan(access, uuid(id), version(body), command, Instant.now());
        if (!result.commandId().equals(command)) {
            throw new DomainException(409, "NOTIFICATION_PLAN_NO_CHANGE", "路由未产生变化，请核对当前配置、偏好及渠道绑定");
        }
        return success(dto(result), request);
    }

    @GetMapping("/notification-routing/commands/{id}")
    Object recover(@PathVariable String id, HttpServletRequest request) {
        noQuery(request);
        var subject = subject(request, "notification_preferences:read_own");
        var entry = store.command(subject.access(), uuid(id))
                .orElseThrow(() -> new DomainException(404, "NOT_FOUND", "当前身份未找到这条原命令"));
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(dto(entry), request);
    }

    /** Retains the complete effective membership for the final read boundary after storage releases its locks. */
    private record Subject(Access access, Membership membership) {}

    private Access access(HttpServletRequest request, String capability) {
        return subject(request, capability).access();
    }

    private Subject subject(HttpServletRequest request, String capability) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null) {
            throw new DomainException(401, "SESSION_EXPIRED", "当前会话已失效");
        }
        Object tenant = request.getSession(false).getAttribute("mop.tenant_id");
        if (!(tenant instanceof String tenantId)) {
            throw new DomainException(401, "SESSION_EXPIRED", "当前租户会话已失效");
        }
        String actor = request.getUserPrincipal().getName();
        var member = platform.membership(actor, tenantId).orElseThrow(() -> new DomainException(401, "SESSION_EXPIRED", "当前成员关系已失效"));
        if (!member.active() || !member.capabilities().contains(capability)) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份没有此通知权限");
        }
        if (!enabled) {
            throw new DomainException(503, "NOTIFICATION_ROUTING_NOT_CONFIGURED", "通知路由控制尚未启用，已有站内通知仍可使用");
        }
        return new Subject(new Access(uuid(tenantId), uuid(actor), member.permissionVersion(), trace(request)), member);
    }

    private static Map<String, Object> dto(Entry entry) {
        return entry == null ? null : map("kind", entry.kind().name(), "id", entry.id().toString(), "version", entry.version(),
                "command_id", entry.commandId().toString(), "state", entry.state(), "recorded_at", entry.recordedAt().toString());
    }

    private static JsonNode body(HttpServletRequest request, Set<String> fields) {
        noQuery(request);
        JsonNode body = AssetExportJobController.body(request);
        var actual = new HashSet<String>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!body.isObject() || !actual.equals(fields)) {
            throw invalid();
        }
        return body;
    }

    private static long version(JsonNode body) {
        JsonNode version = body.get("expected_version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()
                || version.longValue() < -1 || version.longValue() >= 100000) {
            throw invalid();
        }
        return version.longValue();
    }

    private static UUID command(HttpServletRequest request) {
        return uuid(request.getHeader("Idempotency-Key"));
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) {
                throw invalid();
            }
            return id;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static Kind kind(String value) {
        try {
            Kind kind = Kind.valueOf(value);
            if (!Set.of(Kind.REQUEST, Kind.ROUTE, Kind.BINDING).contains(kind)) {
                throw invalid();
            }
            return kind;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw invalid();
        }
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "通知控制请求格式无效");
    }
}
