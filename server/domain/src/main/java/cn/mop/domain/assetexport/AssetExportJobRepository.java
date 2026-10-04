package cn.mop.domain.assetexport;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Durable internal acceptance/readback only; no current store, download or notification success claim. */
public interface AssetExportJobRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Job(String id,String tenantId,String createdBy,long permissionVersion,String acceptedRole,
               String inputSha256,AssetExportJobState.Filters filters,AssetExportJobState.State state,
               long version,Instant createdAt,Instant updatedAt) {}
    record Claim(String jobId,String worker,int fencingToken,long version,Instant leaseUntil,
                 AssetExportJobState.Command command,boolean storageReconciliationRequired) {}
    record Work(Claim claim,Instant databaseNow,AssetExportPreparedFile prepared) {}
    /** Must run inside the platform's protected idempotent transaction, including before replay. */
    void authorizeAcceptance(Access access);
    Job accept(Access access,AssetExportJobState.Filters filters);
    Job get(Access access,String id);
    /** Current accepted membership version/role only; old receipts remain in the database. */
    List<Job> list(Access access,int limit,Instant beforeCreatedAt,String beforeId);
    /** Internal worker only. A claim is not current permission to upload or a successful export. */
    Optional<Claim> claim(String jobId,String worker,Duration lease);
    Optional<Claim> claimNext(String worker,Duration lease);
    /** Trusted internal access only; rechecks current scope/lease and never grants public download access. */
    Work loadWork(Claim claim);
    /** Persist immutable bytes with audit, or recover an exact previously committed preparation. */
    Work prepare(Claim claim,AssetExportPreparedFile file);
    List<String> readinessProblems();
}
