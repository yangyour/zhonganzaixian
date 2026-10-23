package cn.mop.worker.backup;

import cn.mop.persistence.BackupGrantFile;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupTaskDiscovery.AssetScope;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Explicit process enablement and namespace allowlist. Private fields never appear in diagnostics. */
public record BackupWorkerSettings(String jdbcUrl, String password, Path grantsFile, Path proofDirectory,
        Path collectorPublicKey, String collectorBinding, BackupTaskSchedule schedule) {
    static final Set<Operation> REQUIRED_OPERATIONS = Set.of(Operation.DISCOVER, Operation.ARCHIVE_READ,
            Operation.ARCHIVE_START, Operation.ARCHIVE_RECONCILE, Operation.VERSION_READ,
            Operation.VERSION_REGISTER, Operation.CRYPTO_VERIFY);

    public static Set<Operation> requiredOperations(BackupTaskScheduler.Mode mode){
        return mode==BackupTaskScheduler.Mode.RECONCILE_ONLY?Set.of(Operation.DISCOVER,Operation.ARCHIVE_READ,Operation.ARCHIVE_RECONCILE,Operation.CRYPTO_VERIFY):REQUIRED_OPERATIONS;
    }
    public static BackupWorkerSettings from(Function<String, String> settings) {return from(settings,BackupTaskScheduler.Mode.POSTPROCESS);}
    public static BackupWorkerSettings from(Function<String, String> settings,BackupTaskScheduler.Mode mode) {
        java.util.Objects.requireNonNull(mode);
        String restricted=settings.apply("MOP_BACKUP_RECONCILE_ONLY_ENABLED");
        if(mode==BackupTaskScheduler.Mode.RECONCILE_ONLY?!"true".equals(restricted):restricted!=null&&!"false".equals(restricted))throw invalid();
        if (!"remote_test".equals(settings.apply("SPRING_PROFILES_ACTIVE"))
                || !"true".equals(settings.apply("MOP_BACKUP_WORKER_ENABLED"))
                || !"mop_test_runtime".equals(settings.apply("SPRING_DATASOURCE_USERNAME"))) {
            throw invalid();
        }
        String url = required(settings, "SPRING_DATASOURCE_URL");
        var address = java.util.regex.Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test").matcher(url);
        if (!address.matches() || Integer.parseInt(address.group(1)) < 1 || Integer.parseInt(address.group(1)) > 65535) {
            throw invalid();
        }
        String binding = required(settings, "MOP_BACKUP_TASK_BINDING");
        if (!binding.matches("[A-Za-z0-9._-]{1,128}")) {
            throw invalid();
        }
        UUID actor = uuid(required(settings, "MOP_BACKUP_WORKER_ACTOR"));
        Set<UUID> tenants = Arrays.stream(required(settings, "MOP_BACKUP_WORKER_TENANTS").split(",", -1))
                .map(BackupWorkerSettings::uuid).collect(Collectors.toUnmodifiableSet());
        Path grants = absolute(settings, "MOP_BACKUP_ACCESS_GRANTS_FILE");
        var targets = new BackupGrantFile(grants).get().stream()
                .filter(grant -> grant.bindingReference().equals("backup-task-binding:" + binding)
                        && grant.actorId().equals(actor) && tenants.contains(grant.tenantId())
                        && Instant.now().isBefore(grant.expiresAt()) && grant.operations().containsAll(requiredOperations(mode)))
                .map(grant -> new BackupTaskSchedule.Target(new Access(grant.tenantId(), actor, grant.permissionVersion()),
                        new AssetScope(grant.tenantId(), grant.assetId())))
                .toList();
        String collector = required(settings, "MOP_BACKUP_COLLECTOR_BINDING");
        if (!collector.matches("backup-collector:[A-Za-z0-9._-]{1,128}")) {
            throw invalid();
        }
        return new BackupWorkerSettings(url, required(settings, "SPRING_DATASOURCE_PASSWORD"), grants,
                absolute(settings, "MOP_BACKUP_COLLECTION_PROOFS_DIRECTORY"), absolute(settings, "MOP_BACKUP_COLLECTOR_PUBLIC_KEY"),
                collector, new BackupTaskSchedule(true, binding, targets, number(settings, "MOP_BACKUP_WORKER_PAGE_SIZE"),
                        number(settings, "MOP_BACKUP_WORKER_POLL_MILLIS")));
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) {
                throw invalid();
            }
            return id;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static Path absolute(Function<String, String> settings, String key) {
        Path path = Path.of(required(settings, key));
        if (!path.isAbsolute()) {
            throw invalid();
        }
        return path.normalize();
    }

    private static int number(Function<String, String> settings, String key) {
        try {
            return Integer.parseInt(required(settings, key));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static String required(Function<String, String> settings, String key) {
        String value = settings.apply(key);
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        return value;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("CONFIG_PENDING: backup worker requires explicit restricted identity, targets and dependencies; values withheld");
    }

    @Override
    public String toString() {
        return "BackupWorkerSettings[private values withheld]";
    }
}
