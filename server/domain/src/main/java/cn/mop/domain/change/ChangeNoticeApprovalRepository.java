package cn.mop.domain.change;

import java.util.List;

/** Durable review of an exact immutable draft revision. Never runtime activation. */
public interface ChangeNoticeApprovalRepository {
    String REVIEW_POLICY_KEY = "change_notice.approver_policy";
    record Access(String actorId, String tenantId, long permissionVersion, String traceId) {}
    record View(ChangeNoticeApproval.Snapshot snapshot, List<ChangeNoticeApproval.Action> allowedActions) {
        public View { java.util.Objects.requireNonNull(snapshot); allowedActions=List.copyOf(allowedActions); }
    }
    /** Read-only current availability and exact snapshot from one authorized transaction; never a grant to write. */
    View view(Access access, String noticeId, long draftRevision);
    /** Must be called by the platform's idempotent authorization callback, including receipt replay. */
    void authorizeCurrentAccess(Access access, String noticeId, long draftRevision, ChangeNoticeApproval.Action action);
    ChangeNoticeApproval.Snapshot get(Access access, String noticeId, long draftRevision);
    List<ChangeNoticeApproval.Event> history(Access access, String noticeId, long draftRevision, int limit, Long beforeVersion);
    /** Requires an existing platform idempotent command transaction; caller cannot supply actor/policy facts. */
    ChangeNoticeApproval.Snapshot transition(Access access, String noticeId, long draftRevision,
            long expectedVersion, ChangeNoticeApproval.Action action, String note);
    List<String> readinessProblems();
}
