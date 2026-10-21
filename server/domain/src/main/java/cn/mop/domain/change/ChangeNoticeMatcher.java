package cn.mop.domain.change;

import cn.mop.domain.DomainException;
import cn.mop.domain.time.BusinessDates;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * D10 / M6-7 matching arithmetic. Facts must be loaded by an authorized tenant transaction.
 * This class neither verifies database approval nor authorizes suppression, device execution,
 * or automatic ticket creation. The caller must persist the result with its frozen sources.
 */
public final class ChangeNoticeMatcher {
    private ChangeNoticeMatcher() {}
    public enum Status { MATCHED, NO_MATCH, CONFIG_PENDING, EVIDENCE_INCOMPLETE }
    public enum LateApproval { REJECT, ALLOW }
    public enum Reason { POLICY_UNAVAILABLE, NOTICE_SET_INCOMPLETE, WINDOW_END_UNKNOWN }

    /** No default tolerance, endpoint inclusion, or emergency/late approval rule. */
    public record Policy(UUID tenantId, UUID releaseId, long version, String definitionHash,
                         Duration before, Duration after, boolean includeStart, boolean includeEnd,
                         LateApproval lateApproval) {
        public Policy {
            if (tenantId == null || releaseId == null || version < 1 || definitionHash == null
                    || !definitionHash.matches("[0-9a-f]{64}") || lateApproval == null) throw invalid();
            duration(before); duration(after);
        }
    }

    /** Immutable approval binds one exact revision, not the mutable draft head. */
    public record ApprovedNotice(UUID id, long revision, ChangeNoticeDraft draft,
                                 String approvedFingerprint, UUID approverId, Instant approvedAt) {
        public ApprovedNotice {
            if (id == null || revision < 1 || draft == null || approverId == null
                    || approverId.equals(draft.applicantId())
                    || !draft.fingerprint().equals(approvedFingerprint)) throw invalid();
            time(approvedAt);
        }
    }
    public record Detection(UUID tenantId, UUID assetId, UUID backupVersionId, Instant detectedAt) {
        public Detection {
            if (tenantId == null || assetId == null || backupVersionId == null) throw invalid();
            time(detectedAt);
        }
        /** Tenant + device + immutable backup version; never just a timestamp. */
        public String deduplicationKey() {
            return "configuration-change:" + tenantId + ":" + assetId + ":" + backupVersionId;
        }
    }
    public record Match(UUID noticeId, long revision, String approvedFingerprint) {}
    public record Result(Status status, List<Match> matches, List<Reason> reasons,
                         UUID policyReleaseId, String deduplicationKey) {
        public Result { matches = List.copyOf(matches); reasons = List.copyOf(reasons); }
    }

    /**
     * complete means all relevant effective approvals were read for this tenant/detection,
     * including the configured tolerance. A partial page cannot establish absence.
     */
    public static Result evaluate(UUID authorizedTenant, Detection detection, Policy policy,
                                  List<ApprovedNotice> notices, boolean complete) {
        if (authorizedTenant == null || detection == null || notices == null) throw invalid();
        tenant(authorizedTenant, detection.tenantId());
        if (policy != null) tenant(authorizedTenant, policy.tenantId());
        var seen = new HashSet<UUID>();
        for (var notice : notices) {
            if (notice == null) throw invalid();
            tenant(authorizedTenant, notice.draft().tenantId());
            // Multiple revisions of one notice are ambiguous: caller must select effective revision.
            if (!seen.add(notice.id())) throw invalid();
        }
        if (policy == null) return result(Status.CONFIG_PENDING, List.of(), List.of(Reason.POLICY_UNAVAILABLE), null, detection);
        var matches = new ArrayList<Match>();
        var reasons = new LinkedHashSet<Reason>();
        if (!complete) reasons.add(Reason.NOTICE_SET_INCOMPLETE);
        for (var notice : notices) {
            var draft = notice.draft();
            if (!draft.assetIds().contains(detection.assetId())) continue;
            if (policy.lateApproval() == LateApproval.REJECT && notice.approvedAt().isAfter(detection.detectedAt())) continue;
            Instant start = shifted(draft.plannedStartAt(), policy.before().negated());
            int startOrder = detection.detectedAt().compareTo(start);
            if (startOrder < 0 || startOrder == 0 && !policy.includeStart()) continue;
            if (draft.plannedEndAt() == null) { reasons.add(Reason.WINDOW_END_UNKNOWN); continue; }
            Instant end = shifted(draft.plannedEndAt(), policy.after());
            int endOrder = detection.detectedAt().compareTo(end);
            if (endOrder < 0 || endOrder == 0 && policy.includeEnd()) {
                matches.add(new Match(notice.id(), notice.revision(), notice.approvedFingerprint()));
            }
        }
        // Canonical ordering makes the same evidence independent of query row order.
        matches.sort(Comparator.comparing(m -> m.noticeId().toString()));
        Status status = !matches.isEmpty() ? Status.MATCHED : reasons.isEmpty() ? Status.NO_MATCH : Status.EVIDENCE_INCOMPLETE;
        return result(status, matches, List.copyOf(reasons), policy, detection);
    }
    private static Result result(Status status, List<Match> matches, List<Reason> reasons, Policy policy, Detection detection) {
        return new Result(status, matches, reasons, policy == null ? null : policy.releaseId(), detection.deduplicationKey());
    }
    private static Instant shifted(Instant instant, Duration offset) {
        try { Instant value = instant.plus(offset); time(value); return value; }
        catch (DateTimeException | ArithmeticException e) { throw invalid(); }
    }
    private static void duration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.getNano() % 1_000_000 != 0) throw invalid();
        try { duration.toMillis(); } catch (ArithmeticException e) { throw invalid(); }
    }
    private static void time(Instant value) {
        if (value == null || value.getNano() % 1_000_000 != 0
                || value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T15:59:59.999Z"))) throw invalid();
        BusinessDates.localDate(value);
    }
    private static void tenant(UUID expected, UUID actual) {
        if (!expected.equals(actual)) throw new DomainException(404, "OBJECT_NOT_FOUND", "变更报备不存在或不可见");
    }
    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "报备匹配需要明确且一致的检测、审批修订与已发布窗口规则");
    }
}
