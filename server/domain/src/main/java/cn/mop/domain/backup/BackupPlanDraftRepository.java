package cn.mop.domain.backup;
import java.time.Instant;
import java.util.List;
/** Unpublished proposals only. Every write runs inside the platform's protected idempotent transaction. */
public interface BackupPlanDraftRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId){}
    record Snapshot(String id,String tenantId,long version,BackupPlanDraft draft,boolean archived,String recordedBy,Instant createdAt,Instant updatedAt,Instant recordedAt){}
    record Revision(String planId,String tenantId,long version,String action,BackupPlanDraft draft,boolean archived,String recordedBy,Instant recordedAt){}
    void authorizeCurrentAccess(Access access,String planId);
    List<Snapshot> list(Access access,int limit,Instant beforeCreatedAt,String beforeId);
    Snapshot get(Access access,String planId);
    List<Revision> history(Access access,String planId,int limit,Long beforeVersion);
    Snapshot create(Access access,BackupPlanDraft draft);
    Snapshot update(Access access,String planId,long expectedVersion,BackupPlanDraft draft);
    Snapshot archive(Access access,String planId,long expectedVersion);
    Snapshot restore(Access access,String planId,long expectedVersion);
    List<String> readinessProblems();
}
