package cn.mop.domain.change;
import java.time.Instant;
import java.util.List;
/** Unreviewed proposals only. Every write runs inside the platform's protected idempotent transaction. */
public interface ChangeNoticeDraftRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId){}
    record Snapshot(String id,String tenantId,long version,ChangeNoticeDraft draft,boolean archived,String recordedBy,Instant createdAt,Instant updatedAt,Instant recordedAt){}
    record Revision(String noticeId,String tenantId,long version,String action,ChangeNoticeDraft draft,boolean archived,String recordedBy,Instant recordedAt){}
    void authorizeCurrentAccess(Access access,String noticeId);
    List<Snapshot> list(Access access,int limit,Instant beforeCreatedAt,String beforeId);
    Snapshot get(Access access,String noticeId);
    List<Revision> history(Access access,String noticeId,int limit,Long beforeVersion);
    Snapshot create(Access access,ChangeNoticeDraft draft);
    Snapshot update(Access access,String noticeId,long expectedVersion,ChangeNoticeDraft draft);
    Snapshot archive(Access access,String noticeId,long expectedVersion);
    Snapshot restore(Access access,String noticeId,long expectedVersion);
    List<String> readinessProblems();
}
