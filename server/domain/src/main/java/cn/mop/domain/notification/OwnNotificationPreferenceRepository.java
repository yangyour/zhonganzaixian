package cn.mop.domain.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Current owner requests only; every write must be in the protected idempotent transaction. */
public interface OwnNotificationPreferenceRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Snapshot(String id,String tenantId,long version,OwnNotificationPreferenceDraft draft,
                    String recordedBy,long recordedPermissionVersion,Instant createdAt,Instant updatedAt) {}
    record Revision(String preferenceId,String tenantId,long version,String action,OwnNotificationPreferenceDraft draft,
                    String recordedBy,long recordedPermissionVersion,Instant recordedAt) {}
    void authorizeCurrentAccess(Access access,String preferenceId);
    Optional<Snapshot> current(Access access);
    Snapshot get(Access access,String preferenceId);
    List<Revision> history(Access access,String preferenceId,int limit,Long beforeVersion);
    Snapshot create(Access access,OwnNotificationPreferenceDraft draft);
    Snapshot update(Access access,String preferenceId,long expectedVersion,OwnNotificationPreferenceDraft draft);
    List<String> readinessProblems();
}
