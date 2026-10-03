package cn.mop.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Contract-fixed D07 guards only; no state transition, HTTP endpoint or persistence.
 * The caller must obtain fresh membership and complete customer relationships from the
 * authorized tenant transaction, not from request fields. It must additionally enforce
 * configured workflow state, CSRF, idempotency, CAS, audit and immutable SLA snapshots.
 * These guards do not approve Q07/Q09/Q27 or verify the truth of caller-supplied facts.
 */
public final class TicketCustomerActionRules {
    public static final int MIN_SOLUTION_CODE_POINTS = 20;
    public static final Duration REOPEN_WINDOW = Duration.ofHours(72);
    private TicketCustomerActionRules() {}

    /** A complete empty relationship set differs from an unavailable lookup. */
    public record Relationships(String tenantId, String reportedBy,
                                Set<String> activeDecisionContacts, boolean complete) {
        public Relationships {
            if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("Object tenant is required");
            if (activeDecisionContacts == null) {
                if (complete) throw new IllegalArgumentException("Complete relationships require an explicit contact set");
            } else {
                activeDecisionContacts = Set.copyOf(activeDecisionContacts);
            }
        }
    }

    public static void requireCustomer(Membership membership, Relationships relationships) {
        if (membership == null || !membership.active() || membership.permissionVersion() < 1
                || membership.userId() == null || membership.userId().isBlank()
                || membership.tenantId() == null || membership.tenantId().isBlank())
            throw new DomainException(401, "SESSION_EXPIRED", "当前成员身份已失效");
        if (relationships == null || !relationships.tenantId().equals(membership.tenantId()))
            throw new DomainException(404, "OBJECT_NOT_FOUND", "对象不存在或无权限");
        if (!Set.of("viewer", "tadmin").contains(membership.role() == null ? "" : membership.role()))
            throw new DomainException(403, "ACTION_FORBIDDEN", "仅符合工单关系的客户主体可确认或重开");
        if (!relationships.complete())
            throw new DomainException(503, "CUSTOMER_RELATIONSHIP_UNAVAILABLE", "当前客户关系尚未完整核验");
        if (!membership.userId().equals(relationships.reportedBy()) && !relationships.activeDecisionContacts().contains(membership.userId()))
            throw new DomainException(403, "ACTION_FORBIDDEN", "仅报障人或已核验的启用决策人可执行此动作");
    }

    /** Count Unicode code points after boundary whitespace removal, without rewriting original text. */
    public static void requireSolution(String solution) {
        requireText(solution);
        String counted = stripBoundaryWhitespace(solution);
        if (counted.codePointCount(0, counted.length()) < MIN_SOLUTION_CODE_POINTS)
            throw new DomainException(400, "VALIDATION_ERROR", "解决说明至少20个Unicode字符");
    }

    public static void requireUnresolvedReason(String reason) { requireText(reason); }
    public static void requireReopenReason(String reason) { requireText(reason); }

    /** The closure instant must be authoritative; the caller supplies a clock for deterministic checks. */
    public static void requireReopenWindow(Instant closedAt, Instant now) {
        if (closedAt == null || now == null)
            throw new DomainException(422, "STATE_NOT_ALLOWED", "关闭时间尚未确认，不能判定重开窗口");
        Duration elapsed = Duration.between(closedAt, now);
        if (elapsed.isNegative() || elapsed.compareTo(REOPEN_WINDOW) > 0)
            throw new DomainException(422, "STATE_NOT_ALLOWED", "不在关闭后的72小时重开窗口内");
    }

    private static void requireText(String value) {
        if (value == null || value.isEmpty() || value.codePoints().allMatch(TicketCustomerActionRules::isWhitespace))
            throw new DomainException(400, "VALIDATION_ERROR", "说明不能为空");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if(Character.isISOControl(current)&&current!='\n'&&current!='\r'&&current!='\t')
                throw new DomainException(400, "VALIDATION_ERROR", "说明包含非文本控制字符");
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index)))
                    throw new DomainException(400, "VALIDATION_ERROR", "说明包含无效Unicode字符");
            } else if (Character.isLowSurrogate(current)) {
                throw new DomainException(400, "VALIDATION_ERROR", "说明包含无效Unicode字符");
            }
        }
    }

    private static boolean isWhitespace(int point) { return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xfeff; }
    private static String stripBoundaryWhitespace(String value) {
        int start = 0, end = value.length();
        while (start < end && isWhitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && isWhitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
}
