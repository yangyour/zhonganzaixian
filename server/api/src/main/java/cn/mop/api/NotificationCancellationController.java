package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.NotificationControlStore.Access;
import cn.mop.persistence.NotificationRequestCancellationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Explicit cancellation of internal pre-dispatch planning; never retracts or retries a provider request. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/notification-routing/requests")
public final class NotificationCancellationController {

    /** Current session and capabilities, independently checked again by storage. */
    private final PlatformRepository platform;
    /** Atomic cancellation, original command receipt and audit. */
    private final NotificationRequestCancellationService cancellations;
    /** Reuses the existing routing switch; construction does not enable it. */
    private final boolean enabled;

    public NotificationCancellationController(
        PlatformRepository platform,
        NotificationRequestCancellationService cancellations,
        @Value("${MOP_NOTIFICATION_ROUTING_ENABLED:false}") boolean enabled
    ) {
        this.platform = platform;
        this.cancellations = cancellations;
        this.enabled = enabled;
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @PostMapping(value = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object cancel(@PathVariable String id, HttpServletRequest request) {
        if (
            request.getUserPrincipal() == null ||
            request.getSession(false) == null ||
            !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)
        ) {
            throw new DomainException(401, "SESSION_EXPIRED", "当前通知会话已失效");
        }
        String actor = request.getUserPrincipal().getName();
        UUID tenantId = uuid(tenant),
            actorId = uuid(actor),
            requestId = uuid(id);
        var member = platform
            .membership(actor, tenant)
            .orElseThrow(() -> new DomainException(401, "SESSION_EXPIRED", "当前通知成员关系已失效"));
        if (
            !member.active() ||
            !actor.equals(member.userId()) ||
            !tenant.equals(member.tenantId()) ||
            !member
                .capabilities()
                .containsAll(Set.of("configurations:write", "ticket_notes:read_internal", "audit:read"))
        ) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份无通知取消权限");
        }
        if (!enabled) {
            throw new DomainException(503, "NOTIFICATION_ROUTING_NOT_CONFIGURED", "通知路由控制尚未显式启用");
        }
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw invalid();
        }
        String key = request.getHeader("Idempotency-Key");
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw invalid();
        }
        UUID command = uuid(key);
        JsonNode body = AssetExportJobController.body(request);
        var fields = new HashSet<String>();
        body.fieldNames().forEachRemaining(fields::add);
        if (
            !body.isObject() ||
            !fields.equals(Set.of("expected_version", "reason")) ||
            !body.path("expected_version").isIntegralNumber() ||
            !body.path("expected_version").canConvertToLong() ||
            body.path("expected_version").asLong() < 0 ||
            body.path("expected_version").asLong() >= 100000 ||
            !body.path("reason").isTextual() ||
            body.path("reason").asText().isBlank() ||
            body.path("reason").asText().length() > 1000
        ) {
            throw invalid();
        }
        var result = cancellations.cancel(
            new Access(tenantId, actorId, member.permissionVersion(), ApiResponse.trace(request)),
            requestId,
            body.path("expected_version").asLong(),
            command,
            body.path("reason").asText()
        );
        if (
            result == null ||
            result.kind() != cn.mop.persistence.NotificationControlStore.Kind.REQUEST ||
            !result.id().equals(requestId) ||
            !result.commandId().equals(command) ||
            result.version() != body.path("expected_version").asLong() + 1 ||
            !"CANCELLED".equals(result.state().path("status").asText()) ||
            !body.path("reason").asText().equals(result.state().path("cancellation_reason").asText())
        ) {
            throw new DomainException(
                503,
                "NOTIFICATION_CONTROL_UNAVAILABLE",
                "取消回执未通过核对，请保留原请求"
            );
        }
        DashboardReadAuthorization.recheck(request, platform, member);
        return ApiResponse.success(
            ApiResponse.map(
                "kind",
                result.kind().name(),
                "id",
                result.id().toString(),
                "version",
                result.version(),
                "command_id",
                result.commandId().toString(),
                "state",
                result.state(),
                "recorded_at",
                result.recordedAt().toString()
            ),
            request
        );
    }

    private static UUID uuid(String value) {
        if (value == null || !MonitoringPresetRegistry.uuid(value)) {
            throw invalid();
        }
        return UUID.fromString(value);
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "取消通知的范围、版本、原因或原命令标识无效");
    }
}
