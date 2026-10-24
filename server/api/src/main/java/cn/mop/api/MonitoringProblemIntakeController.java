package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.monitoring.MonitoringObjectBinding;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.Access;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Explicit single-problem intake; accepts source identity only and never confirms monitoring recovery. */
@RestController
@Profile("remote_test")
public final class MonitoringProblemIntakeController {

    private final PlatformRepository platform;
    private final MonitoringObjectBindingRepository mappings;
    private final boolean enabled;

    public MonitoringProblemIntakeController(
        PlatformRepository platform,
        MonitoringObjectBindingRepository mappings,
        Environment env
    ) {
        this.platform = platform;
        this.mappings = mappings;
        this.enabled = enabled(env);
    }

    static boolean enabled(Environment env) {
        return (
            MonitoringEnrollmentConfiguration.flag(env, "MOP_MONITORING_MAPPING_ENABLED") &&
            MonitoringEnrollmentConfiguration.flag(env, "MOP_MONITORING_MAPPING_CAPTURE_ENABLED") &&
            MonitoringEnrollmentConfiguration.flag(env, "MOP_MONITORING_MAPPING_PROBLEM_INTAKE_ENABLED")
        );
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @PostMapping("/api/v1/monitoring-object-mappings/{id}/problem-intakes")
    Object intake(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        Membership member = MonitoringOnboardingAccess.member(platform, request, true);
        MonitoringOnboardingAccess.noQuery(request);
        if (!MonitoringPresetRegistry.uuid(id) || body == null || !body.isObject() || body.size() != 3) {
            throw invalid();
        }
        var fields = body.fieldNames();
        while (fields.hasNext()) {
            if (
                !Set.of("expected_version", "source_event_id", "expected_source_definition_sha256").contains(
                    fields.next()
                )
            ) {
                throw invalid();
            }
        }
        JsonNode version = body.path("expected_version");
        if (
            !version.isIntegralNumber() ||
            !version.canConvertToLong() ||
            version.longValue() < 1 ||
            version.longValue() > MonitoringObjectBinding.MAX_VERSION ||
            !body.path("source_event_id").isTextual() ||
            !cn.mop.adapters.monitoring.ZabbixObjectIdentityReader.numeric(body.path("source_event_id").textValue()) ||
            !body.path("expected_source_definition_sha256").isTextual() ||
            !body.path("expected_source_definition_sha256").textValue().matches("[a-f0-9]{64}")
        ) {
            throw invalid();
        }
        String key = request.getHeader("Idempotency-Key");
        if (
            key == null ||
            !key.matches("[A-Za-z0-9._:-]{1,128}") ||
            Collections.list(request.getHeaders("Idempotency-Key")).size() != 1
        ) {
            throw invalid();
        }
        UUID mapping = UUID.fromString(id);
        String sourceEvent = body.path("source_event_id").textValue();
        String definition = body.path("expected_source_definition_sha256").textValue();
        Access access = new Access(
            member.tenantId(),
            member.userId(),
            member.permissionVersion(),
            ApiResponse.trace(request)
        );
        Object result = platform.idempotentAuthorized(
            member.userId(),
            member.tenantId(),
            request.getMethod() + " " + ApiResponse.route(request),
            key,
            digest(body.toString()),
            member.permissionVersion(),
            () -> mappings.authorize(access, mapping, true),
            () -> {
                if (!enabled) {
                    throw new DomainException(503, "MAPPING_PROBLEM_INTAKE_DISABLED", "来源问题事件消费尚未显式启用");
                }
                var receipt = mappings.intakeProblem(access, mapping, version.longValue(), sourceEvent, definition);
                if (
                    receipt == null ||
                    receipt.capture() == null ||
                    !mapping.equals(receipt.capture().mappingId()) ||
                    receipt.capture().mappingVersion() != version.longValue() ||
                    !sourceEvent.equals(receipt.capture().sourceEventId()) ||
                    receipt.capture().phase() != cn.mop.domain.monitoring.RawMonitoringEvent.Phase.PROBLEM ||
                    receipt.businessProcessed() == receipt.capture().outcome().startsWith("CONFLICT") ||
                    (receipt.event() != null &&
                        (!member.tenantId().equals(receipt.event().tenantId()) || receipt.event().version() < 1))
                ) {
                    throw new DomainException(
                        503,
                        "MAPPING_PROBLEM_INTAKE_INVALID",
                        "问题事件回执未通过范围与结果核对"
                    );
                }
                recheck(member, request);
                return ApiResponse.map(
                    "mapping_id",
                    id,
                    "mapping_version",
                    version.longValue(),
                    "source_event_id",
                    sourceEvent,
                    "record_id",
                    receipt.capture().recordId().toString(),
                    "raw_outcome",
                    receipt.capture().outcome(),
                    "first_received_at",
                    receipt.capture().firstReceivedAt().toString(),
                    "business_processed",
                    receipt.businessProcessed(),
                    "event_id",
                    receipt.event() == null ? null : receipt.event().id(),
                    "event_version",
                    receipt.event() == null ? null : receipt.event().version(),
                    "ticket_id",
                    receipt.event() == null ? null : receipt.event().ticketId(),
                    "source_ownership_basis",
                    "CURRENT_MAPPING_AND_SERVER_READ",
                    "recovery_confirmed",
                    false,
                    "dispatch_allowed",
                    false,
                    "acknowledgement_only",
                    true
                );
            }
        );
        recheck(member, request);
        return ApiResponse.success(result, request);
    }

    private void recheck(Membership member, HttpServletRequest request) {
        MonitoringOnboardingAccess.recheck(platform, member, request, true);
        ProjectSessionValidationFilter.recheckBeforeReturn(request);
    }

    private static String digest(String input) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static DomainException invalid() {
        return new DomainException(
            400,
            "INVALID_MONITORING_PROBLEM_INTAKE",
            "仅接受明确映射版本、来源事件ID及来源定义摘要"
        );
    }
}
