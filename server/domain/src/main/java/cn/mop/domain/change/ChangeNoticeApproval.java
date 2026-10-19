package cn.mop.domain.change;

import cn.mop.domain.DomainException;
import cn.mop.domain.TicketCustomerActionRules;
import cn.mop.domain.time.BusinessDates;
import java.time.Instant;
import java.util.UUID;

/** Immutable revision review only. Approval is not runtime activation or permission to execute. */
public final class ChangeNoticeApproval {
    private ChangeNoticeApproval() {}
    private static final long MAX_VERSION = 9_007_199_254_740_990L;
    public enum State { DRAFT, IN_REVIEW, APPROVED, REJECTED, WITHDRAWN }
    public enum Action { SUBMIT, WITHDRAW, APPROVE, REJECT }

    /** Load fresh membership and configured Q09 authority in the same authorized transaction. */
    public record Actor(UUID tenantId, UUID userId, long permissionVersion, boolean active,
                        UUID reviewPolicyReleaseId, boolean mayReview) {
        public Actor {
            if (tenantId == null || userId == null || permissionVersion < 1
                    || mayReview && reviewPolicyReleaseId == null) throw invalid();
        }
    }
    public record Snapshot(UUID noticeId, long draftRevision, ChangeNoticeDraft draft,
                           String fingerprint, long version, State state, Instant createdAt,
                           Instant submittedAt, UUID reviewerId, Instant decidedAt,
                           UUID reviewPolicyReleaseId, String decisionNote, Instant updatedAt) {
        public Snapshot {
            if (noticeId == null || draftRevision < 1 || draft == null
                    || !draft.fingerprint().equals(fingerprint) || version < 1 || version > MAX_VERSION
                    || state == null) throw invalid();
            time(createdAt); time(updatedAt);
            if (updatedAt.isBefore(createdAt)) throw invalid();
            if (submittedAt != null) { time(submittedAt); if (submittedAt.isBefore(createdAt) || submittedAt.isAfter(updatedAt)) throw invalid(); }
            boolean reviewed = state == State.APPROVED || state == State.REJECTED;
            if (state == State.DRAFT && submittedAt != null
                    || (state == State.IN_REVIEW || reviewed) && submittedAt == null) throw invalid();
            if (reviewed) {
                time(decidedAt);
                if (reviewerId == null || reviewerId.equals(draft.applicantId()) || reviewPolicyReleaseId == null
                        || decidedAt.isBefore(submittedAt) || decidedAt.isAfter(updatedAt)) throw invalid();
                if (state == State.REJECTED || decisionNote != null) note(decisionNote);
            } else if (reviewerId != null || decidedAt != null || reviewPolicyReleaseId != null || decisionNote != null) throw invalid();
        }
        public boolean effective() { return false; }
        public boolean deviceExecutionAllowed() { return false; }
        public boolean alertSuppressionAllowed() { return false; }
    }
    public record Event(UUID noticeId, long draftRevision, String fingerprint, long version,
                        State from, State to, Action action, UUID actorId, long permissionVersion,
                        UUID reviewPolicyReleaseId, String note, Instant occurredAt) {}
    public record Transition(Snapshot snapshot, Event event) {}

    public static Snapshot create(UUID noticeId, long draftRevision, ChangeNoticeDraft draft, Instant now) {
        if (draft == null) throw invalid();
        return new Snapshot(noticeId,draftRevision,draft,draft.fingerprint(),1,State.DRAFT,now,null,null,null,null,null,now);
    }
    public static Transition apply(Snapshot before, long expectedVersion, Action action,
                                   Actor actor, String reason, Instant now) {
        if (before == null || actor == null || action == null || expectedVersion < 1) throw invalid();
        if (!actor.active()) throw new DomainException(401,"SESSION_EXPIRED","当前成员身份已失效");
        if (!before.draft().tenantId().equals(actor.tenantId()))
            throw new DomainException(404,"OBJECT_NOT_FOUND","变更报备不存在或不可见");
        boolean review = action == Action.APPROVE || action == Action.REJECT;
        boolean applicant = before.draft().applicantId().equals(actor.userId());
        if (review) {
            if (applicant) throw forbidden();
            if (actor.reviewPolicyReleaseId() == null)
                throw new DomainException(422,"CONFIGURATION_PENDING","审批岗位尚未配置生效");
            if (!actor.mayReview()) throw forbidden();
        } else if (!applicant) throw forbidden();
        if (before.version() != expectedVersion)
            throw new DomainException(409,"VERSION_CONFLICT","报备审批版本已变化，请刷新后重试");
        time(now);
        if (now.isBefore(before.updatedAt()))
            throw new DomainException(503,"CLOCK_UNAVAILABLE","服务器时间早于已保存审批时间");
        if (before.version() == MAX_VERSION) throw stateError();
        State after = switch (action) {
            case SUBMIT -> { require(before.state() == State.DRAFT); yield State.IN_REVIEW; }
            case WITHDRAW -> { require(before.state() == State.DRAFT || before.state() == State.IN_REVIEW); yield State.WITHDRAWN; }
            case APPROVE -> { require(before.state() == State.IN_REVIEW); yield State.APPROVED; }
            case REJECT -> { require(before.state() == State.IN_REVIEW); yield State.REJECTED; }
        };
        // Preserve original Unicode text in the audit record, without silently normalizing it.
        if (action == Action.REJECT || reason != null) note(reason);
        Instant submitted = action == Action.SUBMIT ? now : before.submittedAt();
        var next = new Snapshot(before.noticeId(),before.draftRevision(),before.draft(),before.fingerprint(),
                before.version()+1,after,before.createdAt(),submitted,review?actor.userId():null,
                review?now:null,review?actor.reviewPolicyReleaseId():null,review?reason:null,now);
        var event = new Event(before.noticeId(),before.draftRevision(),before.fingerprint(),next.version(),
                before.state(),after,action,actor.userId(),actor.permissionVersion(),
                review?actor.reviewPolicyReleaseId():null,reason,now);
        return new Transition(next,event);
    }
    private static void note(String note) {
        TicketCustomerActionRules.requireUnresolvedReason(note);
        if (note.codePointCount(0,note.length()) > 2000) throw invalid();
    }
    private static void time(Instant instant) {
        if (instant == null || instant.getNano()%1_000_000 != 0
                || instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || instant.isAfter(Instant.parse("9999-12-31T15:59:59.999Z"))) throw invalid();
        BusinessDates.localDate(instant);
    }
    private static void require(boolean allowed) { if (!allowed) throw stateError(); }
    private static DomainException stateError() { return new DomainException(422,"STATE_NOT_ALLOWED","当前报备审批状态不允许此动作"); }
    private static DomainException forbidden() { return new DomainException(403,"ACTION_FORBIDDEN","当前身份不能执行此报备审批动作"); }
    private static DomainException invalid() { return new DomainException(400,"VALIDATION_ERROR","报备审批需有效修订、身份、时间和说明"); }
}
