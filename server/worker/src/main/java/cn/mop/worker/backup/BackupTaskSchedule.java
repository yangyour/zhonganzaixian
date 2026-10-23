package cn.mop.worker.backup;

import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupTaskDiscovery.AssetScope;
import java.util.HashSet;
import java.util.List;

/** Explicit technical polling bounds. No device schedule, retention value or business retry rule is inferred. */
public record BackupTaskSchedule(boolean enabled, String binding, List<Target> targets, int pageSize, int pollMillis) {
    public record Target(Access access, AssetScope scope) {
        public Target {
            if (access == null || scope == null || !access.tenantId().equals(scope.tenantId())) {
                throw invalid();
            }
        }
    }

    public BackupTaskSchedule {
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (enabled && (binding == null || !binding.matches("[A-Za-z0-9._-]{1,128}")
                || targets.isEmpty() || targets.size() > 100 || pageSize < 1 || pageSize > 50
                || pollMillis < 100 || pollMillis > 60000)) {
            throw invalid();
        }
        var scopes = new HashSet<AssetScope>();
        for (Target target : targets) {
            if (!scopes.add(target.scope())) {
                throw invalid();
            }
        }
    }

    public static BackupTaskSchedule disabled() {
        return new BackupTaskSchedule(false, null, List.of(), 0, 0);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONFIG_PENDING: backup postprocessing requires explicit binding, targets and bounded polling");
    }
}
