package cn.mop.persistence;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Explicit deployment grant; role or draft access alone never enables backup processing. */
public record BackupTaskGrant(
        String bindingReference,
        UUID tenantId,
        UUID assetId,
        UUID actorId,
        long permissionVersion,
        Instant expiresAt,
        Set<Operation> operations) {

    public enum Operation {
        DISCOVER, ARCHIVE_READ, ARCHIVE_START, ARCHIVE_RECONCILE,
        VERSION_INVENTORY, VERSION_READ, VERSION_REGISTER
    }

    public BackupTaskGrant {
        if (bindingReference == null || !bindingReference.matches("backup-task-binding:[A-Za-z0-9._-]{1,128}")
                || tenantId == null || assetId == null || actorId == null
                || permissionVersion < 1 || permissionVersion > 9007199254740991L
                || expiresAt == null || operations == null || operations.isEmpty()
                || operations.stream().anyMatch(java.util.Objects::isNull)) {
            throw new DomainException(400, "INVALID_BACKUP_TASK_GRANT", "备份任务必须使用明确的设备、身份、有效期和操作授权");
        }
        operations = Set.copyOf(operations);
    }
}
