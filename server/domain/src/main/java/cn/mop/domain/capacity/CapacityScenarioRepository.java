package cn.mop.domain.capacity;
import java.time.Instant;
import java.util.List;
/** Immutable, current-tenant, padmin-only draft storage. References remain unverified. */
public interface CapacityScenarioRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Stored(String id,String tenantId,String actorId,Instant recordedAt,long version,CapacityScenarioDraft draft,CapacityCalculation.Snapshot snapshot,String calculationSha256,String snapshotSha256) {}
    /** Must execute inside the platform idempotent transaction, before returning any cached create receipt. */
    void authorizeCreate(Access access);
    /** Creation-only expectedVersion=0; stored immutable version is1. There is no mutable collection overwrite. */
    Stored create(Access access,long expectedVersion,CapacityScenarioDraft draft);
    Stored get(Access access,String id);
    List<Stored> list(Access access,String labelQuery,int limit,Instant beforeRecordedAt,String beforeId);
    List<String> readinessProblems();
}
