package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.change.*;
import cn.mop.domain.change.ChangeNoticeApproval.*;
import cn.mop.domain.change.ChangeNoticeApprovalRepository.Access;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Exact immutable revision review. Approval never activates execution, matching or suppression. */
@RestController @Profile("remote_test")
@RequestMapping("/api/v1/change-notice-drafts/{id}/revisions/{draft_revision}/approval")
public final class ChangeNoticeApprovalController {
    static final int MAX_BODY_BYTES = 16384;
    static final long MAX_VERSION = 9_007_199_254_740_990L;
    private static final Set<String> FIELDS = Set.of("expected_version", "action", "note");
    private static final ObjectMapper INPUT = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(8000).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    private final ChangeNoticeApprovalRepository approvals;
    public ChangeNoticeApprovalController(PlatformRepository platform, ChangeNoticeApprovalRepository approvals) {
        this.platform = platform; this.approvals = approvals;
    }
    @GetMapping Object get(@PathVariable String id, @PathVariable("draft_revision") String revision, HttpServletRequest r) {
        var a = access(r, false); query(r, Set.of());
        var view = approvals.view(a, uuid(id), version(revision));
        var out = dto(view.snapshot());
        out.put("allowed_actions", view.allowedActions().stream().map(Enum::name).toList());
        return success(out, r);
    }
    @GetMapping("/history") Object history(@PathVariable String id, @PathVariable("draft_revision") String revision, HttpServletRequest r) {
        var a = access(r, false); var q = query(r, Set.of("page_size", "before_version"));
        String size = q.getOrDefault("page_size", "15"); if (!Set.of("10", "15", "20").contains(size)) throw invalid();
        int limit = Integer.parseInt(size); String notice = uuid(id); long rev = version(revision);
        Long before = q.containsKey("before_version") ? version(q.get("before_version")) : null;
        if (before != null && before < 2) throw invalid();
        var rows = approvals.history(a, notice, rev, limit + 1, before);
        boolean more = rows.size() > limit; var items = rows.subList(0, Math.min(limit, rows.size()));
        return success(map("tenant_id", a.tenantId(), "notice_id", notice, "draft_revision", rev,
                "items", items.stream().map(ChangeNoticeApprovalController::eventDto).toList(), "has_more", more,
                "next_before_version", more ? items.get(items.size()-1).version() : null,
                "page_size_limit", 20, "source", "postgresql"), r);
    }
    @PostMapping(value="/actions", consumes=MediaType.APPLICATION_JSON_VALUE)
    Object action(@PathVariable String id, @PathVariable("draft_revision") String revision, HttpServletRequest r) {
        access(r, true); return write(id, revision, body(r), r);
    }
    Object write(String id, String revision, JsonNode input, HttpServletRequest r) {
        var a = access(r, true); query(r, Set.of()); String notice = uuid(id); long rev = version(revision);
        if (input == null || !input.isObject() || input.size() != FIELDS.size()) throw invalid();
        input.fieldNames().forEachRemaining(k -> { if (!FIELDS.contains(k)) throw invalid(); });
        var value = input.get("expected_version");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1 || value.longValue() >= MAX_VERSION) throw invalid();
        long expected = value.longValue(); Action action;
        try { action = Action.valueOf(text(input.get("action"))); } catch (IllegalArgumentException e) { throw invalid(); }
        String note = input.get("note").isNull() ? null : text(input.get("note"));
        if (note != null || action == Action.REJECT) {
            TicketCustomerActionRules.requireUnresolvedReason(note);
            if (note.codePointCount(0, note.length()) > 2000) throw invalid();
        }
        var keys = Collections.list(r.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.get(0).matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
        // Use the actual target in the idempotency scope, and canonical JSON for order-independent payload hashes.
        String commandRoute = "POST /api/v1/change-notice-drafts/" + notice + "/revisions/" + rev + "/approval/actions";
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), commandRoute, keys.get(0), hash(input), a.permissionVersion(),
                () -> approvals.authorizeCurrentAccess(a, notice, rev, action),
                () -> dto(approvals.transition(a, notice, rev, expected, action, note)));
        return success(result, r);
    }
    static Map<String,Object> dto(Snapshot s) {
        var d = s.draft(); var out = safety();
        // Receipt availability is intentionally empty; reload GET for current policy/state authority.
        out.put("allowed_actions", List.of());
        out.putAll(map("notice_id", s.noticeId().toString(), "tenant_id", d.tenantId().toString(), "draft_revision", s.draftRevision(),
                "content_sha256", s.fingerprint(), "version", s.version(), "state", s.state().name(), "applicant_id", d.applicantId().toString(),
                "change_summary", d.changeSummary(), "asset_ids", d.assetIds().stream().map(UUID::toString).toList(),
                "ticket_id", string(d.ticketId()), "planned_start_at", string(d.plannedStartAt()), "planned_end_at", string(d.plannedEndAt()),
                "timezone", d.timezone(), "created_at", string(s.createdAt()), "submitted_at", string(s.submittedAt()),
                "reviewer_id", string(s.reviewerId()), "decided_at", string(s.decidedAt()), "review_policy_release_id", string(s.reviewPolicyReleaseId()),
                "decision_note", s.decisionNote(), "updated_at", string(s.updatedAt())));
        return out;
    }
    static Map<String,Object> eventDto(Event e) {
        var out = safety(); out.putAll(map("notice_id", e.noticeId().toString(), "draft_revision", e.draftRevision(), "content_sha256", e.fingerprint(),
                "version", e.version(), "from_state", e.from().name(), "to_state", e.to().name(), "action", e.action().name(),
                "actor_id", e.actorId().toString(), "permission_version", e.permissionVersion(), "review_policy_release_id", string(e.reviewPolicyReleaseId()),
                "note", e.note(), "occurred_at", string(e.occurredAt()))); return out;
    }
    private static Map<String,Object> safety() { return map("effective", false, "device_execution_allowed", false,
            "alert_suppression_allowed", false, "automatic_p3_decision", null, "source", "postgresql", "record_source", "REVISION_REVIEW_ONLY"); }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private Access access(HttpServletRequest r, boolean write) {
        if (r.getUserPrincipal() == null || r.getSession(false) == null) throw expired();
        Object scoped = r.getSession(false).getAttribute("mop.tenant_id"); if (!(scoped instanceof String tenant) || tenant.isBlank()) throw expired();
        String actor = r.getUserPrincipal().getName(); var member = platform.membership(actor, tenant).orElseThrow(ChangeNoticeApprovalController::expired);
        if (!member.active() || member.permissionVersion() < 1) throw expired();
        if (!member.capabilities().contains(write ? "change_notice_drafts:write" : "change_notice_drafts:read"))
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份没有报备审批技术访问权限");
        return new Access(actor, tenant, member.permissionVersion(), trace(r));
    }
    static JsonNode body(HttpServletRequest r) {
        try {
            if (r.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
            byte[] bytes = r.getInputStream().readNBytes(MAX_BODY_BYTES + 1); if (bytes.length > MAX_BODY_BYTES) throw tooLarge();
            return INPUT.readTree(bytes);
        } catch (DomainException e) { throw e; } catch (Exception e) { throw invalid(); }
    }
    private static Map<String,String> query(HttpServletRequest r, Set<String> allowed) {
        var out = new HashMap<String,String>(); r.getParameterMap().forEach((k,v) -> {
            if (!allowed.contains(k) || v.length != 1 || v[0] == null || v[0].length() > 16) throw invalid(); out.put(k,v[0]);
        }); return out;
    }
    private static long version(String s) {
        if (s == null || !s.matches("[1-9][0-9]{0,15}")) throw invalid();
        try { long v = Long.parseLong(s); if (v > MAX_VERSION) throw invalid(); return v; } catch (NumberFormatException e) { throw invalid(); }
    }
    private static String uuid(String s) {
        if (s == null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        return UUID.fromString(s).toString();
    }
    private static String text(JsonNode n) { if (n == null || !n.isTextual()) throw invalid(); return n.textValue(); }
    private static String hash(JsonNode n) {
        try { var canonical = INPUT.createObjectNode(); for (String key : new TreeSet<>(FIELDS)) canonical.set(key,n.get(key));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(INPUT.writeValueAsBytes(canonical)));
        } catch (Exception e) { throw new IllegalStateException("Approval request hash unavailable"); }
    }
    private static DomainException invalid() { return new DomainException(400,"VALIDATION_ERROR","报备审批字段、动作、版本或查询边界无效"); }
    private static DomainException tooLarge() { return new DomainException(413,"REQUEST_TOO_LARGE","报备审批请求超出有界技术保护，未截断保存"); }
    private static DomainException expired() { return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效"); }
}
