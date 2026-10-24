package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.monitoring.MappedSourcePage;
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

/** Explicit bounded page command. The durable receipt is not a global watermark or a recovery observation. */
@RestController
@Profile("remote_test")
public final class MonitoringPageIntakeController {

    private static final Set<String> FIELDS = Set.of(
        "expected_version",
        "expected_source_definition_sha256",
        "first_event_id",
        "last_event_id",
        "from_clock",
        "through_clock",
        "page_size"
    );
    /** Current session authorization and the existing durable command coordinator. */
    private final PlatformRepository platform;
    /** Resolves ownership and reads the configured source; the caller never supplies event contents. */
    private final MonitoringObjectBindingRepository mappings;
    private final boolean enabled;

    public MonitoringPageIntakeController(
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
            MonitoringProblemIntakeController.enabled(env) &&
            MonitoringEnrollmentConfiguration.flag(env, "MOP_MONITORING_MAPPING_PAGE_INTAKE_ENABLED")
        );
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @PostMapping("/api/v1/monitoring-object-mappings/{id}/page-intakes")
    Object intake(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        var member = MonitoringOnboardingAccess.member(platform, request, true);
        MonitoringOnboardingAccess.noQuery(request);
        if (!MonitoringPresetRegistry.uuid(id) || body == null || !body.isObject() || body.size() != FIELDS.size()) {
            throw invalid();
        }
        body.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) {
                throw invalid();
            }
        });
        long version = number(body, "expected_version");
        long size = number(body, "page_size");
        if (
            version < 1 ||
            version > MonitoringObjectBinding.MAX_VERSION ||
            size < 1 ||
            size > MappedSourcePage.MAX_PAGE_SIZE ||
            !body.path("expected_source_definition_sha256").isTextual() ||
            !body.path("expected_source_definition_sha256").textValue().matches("[a-f0-9]{64}")
        ) {
            throw invalid();
        }
        var window = new MappedSourcePage.Window(
            text(body, "first_event_id"),
            text(body, "last_event_id"),
            number(body, "from_clock"),
            number(body, "through_clock"),
            (int) size
        );
        String key = request.getHeader("Idempotency-Key");
        if (
            key == null ||
            !key.matches("[A-Za-z0-9._:-]{1,128}") ||
            Collections.list(request.getHeaders("Idempotency-Key")).size() != 1
        ) {
            throw invalid();
        }
        UUID mapping = UUID.fromString(id);
        var access = new Access(
            member.tenantId(),
            member.userId(),
            member.permissionVersion(),
            ApiResponse.trace(request)
        );
        var result = platform.idempotentAuthorized(
            member.userId(),
            member.tenantId(),
            request.getMethod() + " " + ApiResponse.route(request),
            key,
            digest(body.toString()),
            member.permissionVersion(),
            () -> mappings.authorize(access, mapping, true),
            () -> {
                if (!enabled) {
                    throw new DomainException(503, "MAPPING_PAGE_INTAKE_DISABLED", "有界来源分页消费尚未显式启用");
                }
                var receipt = mappings.intakePage(
                    access,
                    mapping,
                    version,
                    text(body, "expected_source_definition_sha256"),
                    window
                );
                if (
                    receipt == null ||
                    !member.tenantId().equals(receipt.tenantId().toString()) ||
                    !mapping.equals(receipt.mappingId()) ||
                    receipt.mappingVersion() != version ||
                    !window.equals(receipt.window())
                ) {
                    throw new DomainException(503, "MAPPING_PAGE_INTAKE_INVALID", "分页消费回执范围未通过核验");
                }
                MonitoringOnboardingAccess.recheck(platform, member, request, true);
                    ProjectSessionValidationFilter.recheckBeforeReturn(request);
                return ApiResponse.map(
                    "mapping_id",
                    id,
                    "mapping_version",
                    version,
                    "window",
                    ApiResponse.map(
                        "first_event_id",
                        window.firstEventId(),
                        "last_event_id",
                        window.lastEventId(),
                        "from_clock",
                        window.fromClock(),
                        "through_clock",
                        window.throughClock(),
                        "page_size",
                        window.pageSize()
                    ),
                    "items",
                    receipt
                        .items()
                        .stream()
                        .map(item ->
                            ApiResponse.map(
                                "source_event_id",
                                item.capture().sourceEventId(),
                                "source_phase",
                                item.capture().phase().name(),
                                "record_id",
                                item.capture().recordId().toString(),
                                "raw_outcome",
                                item.capture().outcome(),
                                "first_received_at",
                                item.capture().firstReceivedAt().toString(),
                                "business_processed",
                                item.businessProcessed(),
                                "event_id",
                                item.eventId(),
                                "event_version",
                                item.eventVersion(),
                                "ticket_id",
                                item.ticketId(),
                                "pending_reason",
                                item.pendingReason()
                            )
                        )
                        .toList(),
                    "next_event_id",
                    receipt.nextEventId(),
                    "received_count",
                    receipt.items().size(),
                    "processed_count",
                    receipt.items().stream().filter(MappedSourcePage.Item::businessProcessed).count(),
                    "source_ownership_basis",
                    "CURRENT_MAPPING_AND_SERVER_READ",
                    "complete_window_claimed",
                    false,
                    "global_checkpoint_advanced",
                    false,
                    "recovery_confirmed",
                    false,
                    "dispatch_allowed",
                    false,
                    "acknowledgement_only",
                    true
                );
            }
        );
        MonitoringOnboardingAccess.recheck(platform, member, request, true);
        ProjectSessionValidationFilter.recheckBeforeReturn(request);
        return ApiResponse.success(result, request);
    }

    private static long number(JsonNode body, String field) {
        if (!body.path(field).isIntegralNumber() || !body.path(field).canConvertToLong()) {
            throw invalid();
        }
        return body.path(field).longValue();
    }

    private static String text(JsonNode body, String field) {
        if (!body.path(field).isTextual()) {
            throw invalid();
        }
        return body.path(field).textValue();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static DomainException invalid() {
        return new DomainException(
            400,
            "INVALID_MAPPING_PAGE_INTAKE",
            "仅接受映射版本、来源定义及明确的ID/秒时间窗口和1至10条页大小"
        );
    }
}
