package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.inspection.InspectionQuarterlyReport;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.Access;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.CaptureCreation;
import cn.mop.domain.inspection.InspectionSourceCollectionRepository.Source;
import cn.mop.domain.inspection.InspectionSourceManifest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.mop.api.ApiResponse.route;
import static cn.mop.api.ApiResponse.success;
import static cn.mop.api.ApiResponse.trace;

/** Atomically freezes selected real worksheet revisions and their reproducible quarterly draft. */
@RestController
@RequestMapping("/api/v1/inspection-quarterly-reports/capture")
@Profile("remote_test")
public final class InspectionQuarterlyReportCaptureController {
    static final int MAX_BODY_BYTES = 262144;
    static final Set<String> CAPABILITIES = Set.of("inspection_quarterly_reports:write",
            "inspection_source_collections:read", "inspection_source_collections:write",
            "inspection_worksheets:write");
    private static final ObjectMapper INPUT = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                    .maxStringLength(1200).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Current membership and transaction owner; session role names never grant capabilities. */
    private final PlatformRepository platform;

    /** PostgreSQL implementation shares one connection for source, report, audits and receipt. */
    private final InspectionQuarterlyReportRepository reports;

    public InspectionQuarterlyReportCaptureController(PlatformRepository platform,
                                                      InspectionQuarterlyReportRepository reports) {
        this.platform = platform;
        this.reports = reports;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Object capture(HttpServletRequest request) {
        Access access = access(request);
        if (!request.getParameterMap().isEmpty()) {
            throw invalid();
        }
        JsonNode input = body(request);
        fields(input, Set.of("expected_version", "label", "sources", "year", "quarter", "configuration"));
        if (number(input.get("expected_version")) != 0) {
            throw invalid();
        }
        JsonNode rows = input.get("sources");
        if (!rows.isArray()) {
            throw invalid();
        }
        if (rows.size() > InspectionSourceManifest.MAX_SOURCES) {
            throw tooLarge();
        }
        var sources = new ArrayList<Source>();
        for (var row : rows) {
            fields(row, Set.of("worksheet_id", "version", "expected_source_sha256"));
            sources.add(new Source(text(row.get("worksheet_id")), number(row.get("version")),
                    text(row.get("expected_source_sha256"))));
        }
        JsonNode config = input.get("configuration");
        fields(config, Set.of("revision", "time_zone", "duplicate_rule", "archived_rule",
                "metric_version", "report_template_version"));
        var configuration = new InspectionQuarterlyReport.Configuration(
                nullableText(config.get("revision")), nullableText(config.get("time_zone")),
                enumValue(config.get("duplicate_rule"), InspectionQuarterlyReport.DuplicateRule.class),
                enumValue(config.get("archived_rule"), InspectionQuarterlyReport.ArchivedRule.class),
                nullableText(config.get("metric_version")), nullableText(config.get("report_template_version")));
        var creation = new CaptureCreation(text(input.get("label")), sources,
                new InspectionQuarterlyReport.Period(integer(input.get("year")), integer(input.get("quarter"))),
                configuration);
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")
                || Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw invalid();
        }
        Object result = platform.idempotentAuthorized(access.actorId(), access.tenantId(),
                request.getMethod() + " " + route(request), key, hash(input), access.permissionVersion(),
                () -> reports.authorizeCapture(access), () -> {
                    var row = reports.capture(access, 0, creation);
                    if (row == null || !access.tenantId().equals(row.tenantId())) {
                        throw new DomainException(503, "UPSTREAM_UNAVAILABLE", "报告捕获未返回当前租户的完整结果");
                    }
                    var response = InspectionQuarterlyReportController.summaryDto(row);
                    response.put("capture_mode", "ATOMIC_EXPLICIT_WORKSHEET_REVISIONS");
                    return response;
                });
        return success(result, request);
    }

    private Access access(HttpServletRequest request) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null
                || !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)
                || tenant.isBlank()) {
            throw expired();
        }
        String actor = request.getUserPrincipal().getName();
        Membership member = platform.membership(actor, tenant).orElseThrow(
                InspectionQuarterlyReportCaptureController::expired);
        if (!member.active() || member.permissionVersion() < 1) {
            throw expired();
        }
        if (!member.capabilities().containsAll(CAPABILITIES)) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "报告捕获需要当前执行单、来源集合及报告的全部授权");
        }
        return new Access(actor, tenant, member.permissionVersion(), trace(request));
    }

    private static JsonNode body(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                throw tooLarge();
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw tooLarge();
            }
            return INPUT.readTree(bytes);
        } catch (DomainException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static void fields(JsonNode value, Set<String> keys) {
        if (value == null || !value.isObject() || value.size() != keys.size()) {
            throw invalid();
        }
        value.fieldNames().forEachRemaining(key -> {
            if (!keys.contains(key)) {
                throw invalid();
            }
        });
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode value) {
        return value.isNull() ? null : text(value);
    }

    private static long number(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    private static int integer(JsonNode value) {
        long result = number(value);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw invalid();
        }
        return (int) result;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode value, Class<E> type) {
        String name = nullableText(value);
        try {
            return name == null ? null : Enum.valueOf(type, name);
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isArray()) {
            var result = INPUT.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        if (!value.isObject()) {
            return value;
        }
        var result = INPUT.createObjectNode();
        var keys = new TreeSet<String>();
        value.fieldNames().forEachRemaining(keys::add);
        keys.forEach(key -> result.set(key, canonical(value.get(key))));
        return result;
    }

    private static String hash(JsonNode input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(INPUT.writeValueAsBytes(canonical(input))));
        } catch (Exception failure) {
            throw new IllegalStateException("Report capture request hash unavailable");
        }
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "报告捕获仅接受明确来源修订、摘要、期次和草稿配置");
    }

    private static DomainException tooLarge() {
        return new DomainException(413, "INSPECTION_SOURCE_TECHNICAL_LIMIT", "报告捕获请求超过技术上限，未截断或创建");
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员关系已失效");
    }
}
