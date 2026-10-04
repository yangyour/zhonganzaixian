package cn.mop.domain.assetexport;

import java.time.Instant;
import java.util.List;

/** Durable internal acceptance/readback only; no current store, download or notification success claim. */
public interface AssetExportJobRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Job(String id,String tenantId,String createdBy,long permissionVersion,String acceptedRole,
               String inputSha256,AssetExportJobState.Filters filters,AssetExportJobState.State state,
               long version,Instant createdAt,Instant updatedAt) {}
    /** Must run inside the platform's protected idempotent transaction, including before replay. */
    void authorizeAcceptance(Access access);
    Job accept(Access access,AssetExportJobState.Filters filters);
    Job get(Access access,String id);
    /** Current accepted membership version/role only; old receipts remain in the database. */
    List<Job> list(Access access,int limit,Instant beforeCreatedAt,String beforeId);
    List<String> readinessProblems();
}
