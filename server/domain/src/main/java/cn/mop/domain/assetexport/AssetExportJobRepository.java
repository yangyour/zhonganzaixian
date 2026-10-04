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
    /** Read-only stored progress. An encoding descriptor is not an object-store receipt or download grant. */
    record Progress(Job job,int attempts,AssetExportJobState.Encoding prepared,AssetExportJobState.Failure error) {
        public Progress {
            if(job==null||job.state()==null||attempts<0||attempts>AssetExportJobState.MAX_ATTEMPTS
                ||(job.state()==AssetExportJobState.State.FAILED)!=(error!=null)
                ||attempts>job.version()||prepared!=null&&attempts==0
                ||job.state()==AssetExportJobState.State.QUEUED&&(attempts!=0||prepared!=null)
                ||job.state()!=AssetExportJobState.State.QUEUED&&job.state()!=AssetExportJobState.State.FAILED&&attempts==0
                ||job.state()==AssetExportJobState.State.FAILED&&attempts==0&&error!=AssetExportJobState.Failure.AUTHORIZATION_CHANGED
                ||(job.state()==AssetExportJobState.State.RECONCILING_STORAGE||job.state()==AssetExportJobState.State.SUCCEEDED)&&prepared==null
                ||prepared!=null&&(job.createdAt()==null||job.updatedAt()==null||prepared.generatedAt().isBefore(job.createdAt())||prepared.generatedAt().isAfter(job.updatedAt())))
                throw new cn.mop.domain.DomainException(503,"UPSTREAM_UNAVAILABLE","Stored export progress is inconsistent");
        }
        public Boolean workerLiveness(){return null;}
        public boolean downloadAllowed(){return false;}
        public String objectVerification(){return "NOT_CHECKED_BY_PROGRESS_READER";}
    }
    record Claim(String jobId,String worker,int fencingToken,long version,Instant leaseUntil,
                 AssetExportJobState.Command command,boolean storageReconciliationRequired) {}
    record Work(Claim claim,Instant databaseNow,AssetExportPreparedFile prepared) {}
    /** Must run inside the platform's protected idempotent transaction, including before replay. */
    void authorizeAcceptance(Access access);
    Job accept(Access access,AssetExportJobState.Filters filters);
    Job get(Access access,String id);
    Progress progress(Access access,String id);
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
