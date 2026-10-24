package cn.mop.api;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.Access;
import cn.mop.persistence.PostgresMonitoringRecoveryRepository;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

/** Client selects an event and exact published revision; numeric samples always come from the server's bound source. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/events/{eventId}/recovery-observations")
public final class MonitoringRecoveryController {
    private static final ObjectMapper INPUT = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(128).maxNumberLength(16).build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    private final PostgresMonitoringRecoveryRepository repository;
    private final MonitoringRecoverySource source;

    public MonitoringRecoveryController(PlatformRepository platform, PostgresMonitoringRecoveryRepository repository,
                                        MonitoringRecoverySource source) {
        this.platform = platform;
        this.repository = repository;
        this.source = source;
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping
    Object inspect(@PathVariable String eventId, HttpServletRequest request) {
        var subject = access(request, false);
        Access a = subject.access();
        requireNoQuery(request);
        uuid(eventId);
        var result = repository.inspect(a, eventId);
        result.put("source_binding_available", source.available(a.tenantId(), (String) result.get("asset_id")));
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(result, request);
    }

    @GetMapping("/receipts/{commandSha256}")
    Object receipt(@PathVariable String eventId, @PathVariable String commandSha256, HttpServletRequest request) {
        var subject = access(request, false);
        Access a = subject.access();
        requireNoQuery(request);
        uuid(eventId);
        var result = repository.receipt(a, eventId, commandSha256);
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return success(result, request);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Object observe(@PathVariable String eventId, HttpServletRequest request) {
        var subject = access(request, true);
        Access a = subject.access();
        requireNoQuery(request);
        uuid(eventId);
        JsonNode input;
        try {
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) {
                throw invalid();
            }
            input = INPUT.readTree(bytes);
        } catch (java.io.IOException failure) {
            throw invalid();
        }
        Set<String> fields = Set.of("expected_version", "policy_release_id", "policy_release_version");
        if (input == null || !input.isObject() || input.size() != fields.size() || !fields.stream().allMatch(input::has)) {
            throw invalid();
        }
        long version = version(input.get("expected_version"));
        long releaseVersion = version(input.get("policy_release_version"));
        if (!input.get("policy_release_id").isTextual()) {
            throw invalid();
        }
        String release = input.get("policy_release_id").textValue();
        uuid(release);
        String key = key(request);
        var command = new PostgresMonitoringRecoveryRepository.Command(eventId, version, release, releaseVersion,
            requestIdentity(a, eventId, key));
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(request), key,
            hash("RECOVERY_OBSERVATION_V2|" + eventId + "|" + version + "|" + release + "|" + releaseVersion),
            a.permissionVersion(), () -> repository.authorizeCommand(a, eventId), () -> repository.observe(a, command));
        return success(result, request);
    }

    private record Subject(Access access, Membership membership) { }

    private Subject access(HttpServletRequest request, boolean write) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null
                || !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)) {
            throw new DomainException(401, "SESSION_EXPIRED", "当前会话已失效");
        }
        String actor = request.getUserPrincipal().getName();
        try {
            uuid(actor);
            uuid(tenant);
        } catch (DomainException malformedIdentity) {
            throw new DomainException(401, "SESSION_EXPIRED", "当前会话身份无效");
        }
        var member = platform.membership(actor, tenant)
            .orElseThrow(() -> new DomainException(401, "SESSION_EXPIRED", "当前成员已失效"));
        if (!member.active() || !actor.equals(member.userId()) || !tenant.equals(member.tenantId())
                || member.permissionVersion() < 1 || member.permissionVersion() > 9_007_199_254_740_990L) {
            throw new DomainException(401, "SESSION_EXPIRED", "当前成员已失效");
        }
        if (!Capabilities.internal(member.role()) || !member.capabilities().contains(write ? "ticket_notes:write_internal" : "tickets:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员无权核验监控恢复");
        }
        return new Subject(new Access(tenant, actor, member.permissionVersion(), trace(request)), member);
    }
    private static String requestIdentity(Access a, String event, String key) {
        return hash(a.actorId() + "|" + a.tenantId() + "|" + event + "|" + key);
    }
    private static String key(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")
                || Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw invalid();
        }
        return key;
    }
    private static long version(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1 || value.longValue() > 9_007_199_254_740_990L) {
            throw invalid();
        }
        return value.longValue();
    }
    private static void uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw invalid();
            }
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }
    private static void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw invalid();
        }
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "只接受事件版本与策略发布版本；来源、采样和恢复时点不能由客户端填写");
    }
}
