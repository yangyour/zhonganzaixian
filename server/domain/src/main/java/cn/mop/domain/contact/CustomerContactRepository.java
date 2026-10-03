package cn.mop.domain.contact;
import java.time.Instant;
import java.util.List;
/** Tenant-scoped, self-reported contacts. No identity provisioning, permanent delete or dispatch. */
public interface CustomerContactRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    enum View { CURRENT, ARCHIVED, ALL }
    record Entry(String id,String tenantId,String createdBy,long version,Instant createdAt,Instant updatedAt,
                 String recordedBy,Instant recordedAt,String action,boolean archived,CustomerContact facts) {}
    record Revision(long version,Long previousVersion,String recordedBy,Instant recordedAt,String action,boolean archived,CustomerContact facts) {}
    /** Current member/resource checks inside the idempotent command, before returning a stored receipt. */
    void authorizeWrite(Access access,String id);
    Entry create(Access access,long expectedVersion,CustomerContact facts);
    Entry update(Access access,String id,long expectedVersion,CustomerContact facts);
    Entry archive(Access access,String id,long expectedVersion);
    Entry restore(Access access,String id,long expectedVersion,CustomerContact explicitFacts);
    Entry get(Access access,String id);
    List<Entry> list(Access access,String nameQuery,View view,int limit,Instant beforeCreatedAt,String beforeId);
    List<Revision> history(Access access,String id,int limit,Long beforeVersion);
    List<String> readinessProblems();
}
