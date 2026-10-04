package cn.mop.domain.inspection;

import java.time.Instant;
import java.util.List;

/** Tenant-local unpublished template maintenance; no template approval, active scan or report publication. */
public interface InspectionTemplateRepository {
    record Access(String actorId, String tenantId, long permissionVersion, String traceId) {}
    enum View { CURRENT, ARCHIVED, ALL }
    record Entry(String id, String tenantId, String createdBy, long version, Instant createdAt, Instant updatedAt,
                 String recordedBy, Instant recordedAt, String action, boolean archived, InspectionTemplate.Snapshot snapshot) {}
    record Revision(long version, Long previousVersion, String recordedBy, Instant recordedAt, String action,
                    boolean archived, InspectionTemplate.Snapshot snapshot) {}
    /** Must run in the protected command before any original receipt is replayed. */
    void authorizeWrite(Access access, String id);
    Entry create(Access access, long expectedVersion, InspectionTemplate definition);
    Entry update(Access access, String id, long expectedVersion, InspectionTemplate definition);
    Entry archive(Access access, String id, long expectedVersion);
    Entry restore(Access access, String id, long expectedVersion, InspectionTemplate explicitDefinition);
    Entry get(Access access, String id);
    List<Entry> list(Access access, String nameQuery, String deviceType, View view, int limit, Instant beforeCreatedAt, String beforeId);
    List<Revision> history(Access access, String id, int limit, Long beforeVersion);
    List<String> readinessProblems();
}
