package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.reporting.ReportPublication.*;
import cn.mop.domain.reporting.ReportPublicationRepository;
import cn.mop.domain.reporting.ReportReadership;
import cn.mop.domain.reporting.ReportFollowupWorklist;
import cn.mop.domain.reporting.ReportPublicationRepository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Current-tenant publication, immutable corrections and explicit reading commands; never sends externally. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/report-publications")
public final class ReportPublicationController {

    /** Current identity and durable original-command authorization. */
    private final PlatformRepository platform;
    /** Frozen documents, artifacts and append-only publication history. */
    private final ReportPublicationRepository reports;
    /** Stable public field names and ISO timestamps, separate from repository serialization. */
    private final ObjectMapper json;

    public ReportPublicationController(
        PlatformRepository platform,
        ReportPublicationRepository reports,
        ObjectMapper mapper
    ) {
        this.platform = platform;
        this.reports = reports;
        json = mapper
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    record Subject(Access access, Membership membership) {}

    private Subject subject(HttpServletRequest request) {
        if (
            request.getUserPrincipal() == null ||
            request.getSession(false) == null ||
            !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)
        ) {
            throw expired();
        }
        String actor = request.getUserPrincipal().getName();
        uuid(actor);
        uuid(tenant);
        var m = platform.membership(actor, tenant).orElseThrow(ReportPublicationController::expired);
        if (!m.active() || m.permissionVersion() < 1 || !m.userId().equals(actor) || !m.tenantId().equals(tenant)) {
            throw expired();
        }
        if (!m.capabilities().contains("dashboard:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员无报告阅读权限");
        }
        return new Subject(new Access(actor, tenant, m.permissionVersion(), ApiResponse.trace(request)), m);
    }

    @GetMapping
    Object list(HttpServletRequest request) {
        var s = subject(request);
        var keys = request.getParameterMap().keySet();
        if (
            !Set.of("before", "before_id").containsAll(keys) ||
            (keys.size() != 0 && keys.size() != 2) ||
            request
                .getParameterMap()
                .values()
                .stream()
                .anyMatch(v -> v.length != 1)
        ) {
            throw invalid();
        }
        Instant before = null;
        String id = null;
        if (!keys.isEmpty()) {
            try {
                before = Instant.parse(request.getParameter("before"));
                id = uuid(request.getParameter("before_id"));
                if (before.getNano() % 1000000 != 0) {
                    throw invalid();
                }
            } catch (Exception bad) {
                throw invalid();
            }
        }
        var rows = reports.list(s.access(), 21, before, id);
        if (rows.size() > 21) {
            throw unavailable();
        }
        var visible = rows.stream().limit(20).toList();
        var ids = new HashSet<String>();
        for (var row : rows) {
            bound(s, null, row);
            if (!ids.add(row.state().document().id().toString())) {
                throw unavailable();
            }
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("items", visible.stream().map(this::summary).toList());
        data.put(
            "next",
            rows.size() > 20
                ? Map.of(
                      "before",
                      visible.get(19).state().document().capturedAt().toString(),
                      "before_id",
                      visible.get(19).state().document().id().toString()
                  )
                : null
        );
        data.put("external_dispatch_enabled", false);
        DashboardReadAuthorization.recheck(request, platform, s.membership());
        return ApiResponse.success(data, request);
    }

    @GetMapping("/{id}")
    Object detail(@PathVariable String id, HttpServletRequest request) {
        var s = subject(request);
        noQuery(request);
        var record = reports.get(s.access(), uuid(id));
        bound(s, id, record);
        var data = summary(record);
        DashboardReadAuthorization.recheck(request, platform, s.membership());
        return ApiResponse.success(data, request);
    }

    @PostMapping
    Object prepare(@RequestBody JsonNode body, HttpServletRequest request) {
        var s = subject(request);
        noQuery(request);
        fields(body, Set.of("source_id", "expected_source_sha256"));
        String source = uuid(text(body, "source_id")),
            hash = text(body, "expected_source_sha256");
        if (!hash.matches("[a-f0-9]{64}")) {
            throw invalid();
        }
        return write(
            s,
            null,
            body,
            request,
            () -> {
                var row = reports.prepare(s.access(), source, hash);
                bound(s, null, row);
                return summary(row);
            },
            true
        );
    }

    /** Complete bounded management worklist. Never writes read receipts, reminder intents or provider requests. */
    @GetMapping("/followups")
    Object followups(HttpServletRequest request) {
        var subject=subject(request);
        if(!subject.membership().capabilities().containsAll(List.of("inspection_quarterly_reports:read","inspection_quarterly_reports:write")))
            throw new DomainException(403,"ACTION_FORBIDDEN","当前成员无报告跟进管理权限");
        var keys=request.getParameterMap().keySet();
        if(!Set.of("from","until","filter","before","before_id").containsAll(keys)
            ||!keys.containsAll(Set.of("from","until"))||keys.contains("before")!=keys.contains("before_id")
            ||request.getParameterMap().values().stream().anyMatch(v->v.length!=1||v[0]==null||v[0].isBlank()||v[0].length()>64))throw invalid();
        ReportFollowupWorklist.Query query;
        try{
            var filter=request.getParameter("filter")==null?ReportReadership.Filter.FOLLOWUP_DUE:ReportReadership.Filter.valueOf(request.getParameter("filter"));
            var before=keys.contains("before")?new ReportFollowupWorklist.Cursor(worklistTime(request.getParameter("before")),UUID.fromString(uuid(request.getParameter("before_id")))):null;
            query=new ReportFollowupWorklist.Query(worklistTime(request.getParameter("from")),worklistTime(request.getParameter("until")),filter,before);
        }catch(Exception bad){throw invalid();}
        var page=reports.followups(subject.access(),query);
        if(page==null||!subject.access().tenantId().equals(page.tenantId().toString())||!query.equals(page.query()))throw unavailable();
        DashboardReadAuthorization.recheck(request,platform,subject.membership());
        @SuppressWarnings("unchecked") var data=(Map<String,Object>)json.convertValue(page,Map.class);
        data.put("population_scope","COMPLETE_BOUNDED_CAPTURE_WINDOW");
        data.put("document_scan_limit",ReportFollowupWorklist.DOCUMENT_LIMIT);
        data.put("recipient_scan_limit",ReportFollowupWorklist.RECIPIENT_LIMIT);
        data.put("page_size",ReportFollowupWorklist.PAGE_SIZE);
        data.put("external_dispatch_enabled",false);data.put("writes_performed",false);
        return ApiResponse.success(data,request);
    }
    private static Instant worklistTime(String value){
        var at=Instant.parse(value);
        if(!at.toString().equals(value)||at.getNano()%1000000!=0)throw invalid();
        return at;
    }

    /** Management-only readership of one published revision; reading never creates reminders or sends messages. */
    @GetMapping("/{id}/readership")
    Object readership(@PathVariable String id, HttpServletRequest request) {
        var subject = subject(request);
        uuid(id);
        if (!subject.membership().capabilities().contains("inspection_quarterly_reports:write")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员无报告阅读管理权限");
        }
        if (
            !Set.of("filter", "after").containsAll(request.getParameterMap().keySet()) ||
            request
                .getParameterMap()
                .values()
                .stream()
                .anyMatch(values -> values.length != 1)
        ) {
            throw invalid();
        }
        ReportReadership.Filter filter;
        try {
            filter =
                request.getParameter("filter") == null
                    ? ReportReadership.Filter.ALL
                    : ReportReadership.Filter.valueOf(request.getParameter("filter"));
        } catch (IllegalArgumentException bad) {
            throw invalid();
        }
        UUID after =
            request.getParameter("after") == null
                ? null
                : UUID.fromString(uuid(request.getParameter("after")));
        var page = reports.readership(subject.access(), id, filter, after);
        if (
            page == null ||
            !id.equals(page.documentId().toString()) ||
            !subject.access().tenantId().equals(page.tenantId().toString()) ||
            page.filter() != filter
        ) {
            throw unavailable();
        }
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return ApiResponse.success(json.convertValue(page, Map.class), request);
    }

    @PostMapping("/{id}/actions")
    Object command(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        var s = subject(request);
        noQuery(request);
        uuid(id);
        fields(body, Set.of("action", "expected_version", "reason"));
        String action = text(body, "action");
        long version = version(body);
        if (!Set.of("RENDER", "APPROVE", "RETURN", "PUBLISH", "RESEND").contains(action)) {
            throw invalid();
        }
        String reason = body.get("reason").isNull() ? null : text(body, "reason");
        if (
            (reason != null && (reason.isBlank() || reason.length() > 3000)) ||
            (!action.equals("RETURN") && reason != null)
        ) {
            throw invalid();
        }
        return write(
            s,
            id,
            body,
            request,
            () -> stateDto(reports.command(s.access(), id, version, action, reason)),
            true
        );
    }

    /** Revisions capture an explicitly selected frozen source and retain the original document and receipts. */
    @PostMapping("/{id}/revisions")
    Object revise(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        var subject = subject(request);
        noQuery(request);
        uuid(id);
        fields(body, Set.of("expected_version", "source_id", "expected_source_sha256", "reason"));
        long expectedVersion = version(body);
        String sourceId = uuid(text(body, "source_id"));
        String sourceHash = text(body, "expected_source_sha256");
        String reason = text(body, "reason");
        if (!sourceHash.matches("[a-f0-9]{64}") || reason.isBlank() || reason.length() > 3000) {
            throw invalid();
        }
        return write(
            subject,
            id,
            body,
            request,
            () -> {
                var replacement = reports.revise(subject.access(), id, expectedVersion, sourceId, sourceHash, reason);
                bound(subject, null, replacement);
                var state = replacement.state();
                if (
                    state.version() != 1 ||
                    state.phase() != Phase.DRAFT ||
                    !id.equals(String.valueOf(state.document().parentId())) ||
                    id.equals(state.document().id().toString()) ||
                    state.document().revision() < 2
                ) {
                    throw unavailable();
                }
                var response = summary(replacement);
                if (
                    !sourceId.equals(response.get("source_id")) ||
                    !sourceHash.equals(response.get("source_document_sha256"))
                ) {
                    throw unavailable();
                }
                return response;
            },
            true
        );
    }

    @PostMapping("/{id}/read")
    Object read(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        var s = subject(request);
        noQuery(request);
        uuid(id);
        fields(body, Set.of("expected_version"));
        long version = version(body);
        return write(
            s,
            id,
            body,
            request,
            () -> json.convertValue(reports.read(s.access(), id, version), Map.class),
            false
        );
    }

    @GetMapping("/{id}/content/{format}")
    ResponseEntity<byte[]> content(@PathVariable String id, @PathVariable String format, HttpServletRequest request) {
        var s = subject(request);
        noQuery(request);
        uuid(id);
        Format output;
        try {
            output = Format.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (Exception bad) {
            throw invalid();
        }
        String expected = request.getHeader("If-Match");
        if (expected == null) {
            throw new DomainException(428, "REPORT_PRECONDITION_REQUIRED", "先读取报告状态及摘要");
        }
        if (!expected.matches("\"[a-f0-9]{64}\"") || Collections.list(request.getHeaders("If-Match")).size() != 1) {
            throw invalid();
        }
        var initial = reports.get(s.access(), id);
        bound(s, id, initial);
        if (!expected.equals("\"" + initial.state().document().documentSha256() + "\"")) {
            throw new DomainException(412, "REPORT_CHANGED", "报告文档已变化");
        }
        var result = reports.content(s.access(), id, output);
        var current = reports.get(s.access(), id);
        bound(s, id, current);
        if (!current.state().equals(initial.state())) {
            throw new DomainException(412, "REPORT_CHANGED", "报告状态已变化");
        }
        DashboardReadAuthorization.recheck(request, platform, s.membership());
        var bytes = result.bytes();
        return ResponseEntity.ok()
            .contentType(output == Format.HTML ? MediaType.TEXT_HTML : MediaType.APPLICATION_PDF)
            .contentLength(bytes.length)
            .cacheControl(CacheControl.noStore())
            .header(
                "Content-Disposition",
                "attachment; filename=\"report-" + id + "." + format.toLowerCase(Locale.ROOT) + "\""
            )
            .header("Content-Security-Policy", "sandbox; default-src 'none'; frame-ancestors 'none'")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .header("X-MOP-Report-ID", id)
            .header("X-MOP-Tenant-ID", s.access().tenantId())
            .header("X-MOP-Document-SHA256", current.state().document().documentSha256())
            .header("X-MOP-Content-SHA256", result.sha256())
            .header("X-MOP-Report-Phase", current.state().phase().name())
            .body(bytes);
    }

    private Object write(
        Subject s,
        String id,
        JsonNode body,
        HttpServletRequest r,
        java.util.function.Supplier<Object> run,
        boolean manager
    ) {
        String key = r.getHeader("Idempotency-Key");
        if (
            key == null ||
            !key.matches("[A-Za-z0-9._:-]{1,128}") ||
            Collections.list(r.getHeaders("Idempotency-Key")).size() != 1
        ) {
            throw invalid();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return ApiResponse.success(
            platform.idempotentAuthorized(
                s.access().actorId(),
                s.access().tenantId(),
                r.getMethod() + " " + ApiResponse.route(r),
                key,
                hash,
                s.access().permissionVersion(),
                () -> reports.authorize(s.access(), id, manager),
                run
            ),
            r
        );
    }

    private static void bound(Subject s, String id, Stored row) {
        if (
            row == null ||
            row.state() == null ||
            !s.access().tenantId().equals(row.state().document().tenantId().toString()) ||
            (id != null && !id.equals(row.state().document().id().toString()))
        ) {
            throw unavailable();
        }
    }

    private Map<String, Object> summary(Stored r) {
        var m = stateDto(r.state());
        m.put("label", r.label());
        try {
            var payload = json.readTree(r.payload());
            m.put("source_id", payload.path("source_id").asText());
            m.put("source_document_sha256", payload.path("source_document_sha256").asText());
        } catch (Exception bad) {
            throw unavailable();
        }
        return m;
    }

    private Map<String, Object> stateDto(State state) {
        var map = new LinkedHashMap<String, Object>();
        map.put("state", json.convertValue(state, Map.class));
        map.put("customer_acceptance_confirmed", false);
        map.put("external_dispatch_enabled", false);
        return map;
    }

    private static void noQuery(HttpServletRequest r) {
        if (r.getQueryString() != null || !r.getParameterMap().isEmpty()) {
            throw invalid();
        }
    }

    private static void fields(JsonNode b, Set<String> keys) {
        if (b == null || !b.isObject() || b.size() != keys.size()) {
            throw invalid();
        }
        b.fieldNames().forEachRemaining(k -> {
            if (!keys.contains(k)) {
                throw invalid();
            }
        });
    }

    private static String text(JsonNode b, String k) {
        if (!b.path(k).isTextual()) {
            throw invalid();
        }
        return b.path(k).asText();
    }

    private static long version(JsonNode b) {
        var n = b.get("expected_version");
        if (
            n == null ||
            !n.isIntegralNumber() ||
            !n.canConvertToLong() ||
            n.asLong() < 1 ||
            n.asLong() >= 9007199254740990L
        ) {
            throw invalid();
        }
        return n.asLong();
    }

    private static String uuid(String v) {
        if (v == null || !v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw invalid();
        }
        return v;
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "报告请求字段或范围无效");
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前报告会话已失效");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "REPORT_PUBLICATION_UNAVAILABLE", "报告结果未通过核对");
    }
}
