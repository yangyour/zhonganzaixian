package cn.mop.worker.backup;

import cn.mop.adapters.backup.BackupEnvelopeArchive;
import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.domain.DomainException;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupArchiveRepository.Phase;
import cn.mop.persistence.PostgresBackupTaskAuthorization;
import cn.mop.persistence.PostgresBackupTaskDiscovery;
import cn.mop.persistence.PostgresBackupTaskDiscovery.AssetScope;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Cursor;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Page;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Task;
import cn.mop.persistence.PostgresBackupVersionRepository;
import cn.mop.worker.backup.BackupTaskCommands.RegistrationProofs;
import cn.mop.worker.backup.BackupTaskSchedule.Target;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Explicitly assembled bounded polling over the durable V48/V49 journals.
 * Cursors only provide fairness; command identity and recovery never depend on process memory.
 * No Spring registration, device dispatch, re-encryption, lease release or deletion occurs here.
 */
public final class BackupTaskScheduler {
    /** Read existing trusted evidence identifiers for this exact source; never manufacture successful evidence. */
    public interface ProofSource {
        String archiveReference(Task original);
        RegistrationProofs registrationReferences(Task original);
    }

    public record Cycle(boolean enabled, int discovered, int archiveRecorded, int registrationsRecorded,
            int reconciliationRequired, int blocked, int unavailable, int discoveryFailures) {
        /** Describes this bounded cycle only, never storage/KMS readiness or current backup health. */
        public boolean noProcessingErrors() {
            return enabled && reconciliationRequired == 0 && blocked == 0 && unavailable == 0 && discoveryFailures == 0;
        }
    }

    @FunctionalInterface
    interface Discovery {
        Page read(Access access, AssetScope scope, Cursor after, int limit);
    }

    @FunctionalInterface
    interface ArchiveConsumer {
        BackupArchiveDispatch.Result once(BackupArchiveDispatch.Command command);
    }

    @FunctionalInterface
    interface RegistrationConsumer {
        BackupVersionRegistrationDispatch.Result once(BackupVersionRegistrationDispatch.Command command);
    }

    private final BackupTaskSchedule schedule;
    private final Discovery discovery;
    private final ProofSource proofs;
    private final ArchiveConsumer archives;
    private final RegistrationConsumer registrations;
    private final Map<AssetScope, Cursor> cursors = new HashMap<>();

    /**
     * All proof/crypto/storage adapters are mandatory and independently enforce their own authority.
     * This assembly installs current grant+identity checks in both repositories and consumer boundaries.
     */
    public static BackupTaskScheduler assemble(BackupTaskSchedule schedule, DataSource source,
            PostgresBackupTaskAuthorization authorization, ProofSource proofs,
            PostgresBackupArchiveRepository.EnvelopeVerifier envelopeVerifier,
            PostgresBackupArchiveRepository.EvidenceVerifier archiveEvidenceVerifier,
            PostgresBackupVersionRepository.ObjectVerifier objectVerifier,
            PostgresBackupVersionRepository.CompletionPrerequisiteVerifier completionVerifier,
            BackupEnvelopeEncryption crypto, BackupEnvelopeArchive storage) {
        Objects.requireNonNull(schedule);
        Objects.requireNonNull(source);
        Objects.requireNonNull(authorization);
        if (schedule.enabled() && !authorization.bindingReference().equals("backup-task-binding:" + schedule.binding())) {
            throw new IllegalArgumentException("CONFIG_PENDING: scheduler and current authorization binding must match");
        }
        var archiveRepository = new PostgresBackupArchiveRepository(source, authorization.archiveAuthorizer(),
                envelopeVerifier, archiveEvidenceVerifier);
        var versionRepository = new PostgresBackupVersionRepository(source, authorization.versionAuthorizer(),
                objectVerifier, completionVerifier);
        String binding = schedule.binding();
        var archive = new BackupArchiveDispatch(new BackupArchiveDispatch.Binding(schedule.enabled(),
                binding == null ? null : "backup-archive-dispatch:" + binding), (b, access, scope, step) -> {
                    Operation operation = switch (step) {
                        case READ, RETURN_RESULT -> Operation.ARCHIVE_READ;
                        case START_STORE, STORE_ORIGINAL -> Operation.ARCHIVE_START;
                        case RECONCILE_ORIGINAL -> Operation.ARCHIVE_RECONCILE;
                    };
                    boundary(source, authorization, access, new AssetScope(scope.tenantId(), scope.assetId()), operation);
                }, archiveRepository, crypto, storage);
        var registration = new BackupVersionRegistrationDispatch(new BackupVersionRegistrationDispatch.Binding(schedule.enabled(),
                binding == null ? null : "backup-version-dispatch:" + binding), (b, access, scope, step) -> {
                    Operation operation = step == BackupVersionRegistrationDispatch.Step.REGISTER_ORIGINAL
                            ? Operation.VERSION_REGISTER : Operation.VERSION_READ;
                    boundary(source, authorization, new Access(access.tenantId(), access.actorId(), access.permissionVersion()),
                            new AssetScope(scope.tenantId(), scope.assetId()), operation);
                }, versionRepository);
        var discovery = new PostgresBackupTaskDiscovery(source, authorization);
        return new BackupTaskScheduler(schedule, discovery::discover, proofs, archive::once, registration::once);
    }

    BackupTaskScheduler(BackupTaskSchedule schedule, Discovery discovery, ProofSource proofs,
            ArchiveConsumer archives, RegistrationConsumer registrations) {
        this.schedule = Objects.requireNonNull(schedule);
        this.discovery = Objects.requireNonNull(discovery);
        this.proofs = Objects.requireNonNull(proofs);
        this.archives = Objects.requireNonNull(archives);
        this.registrations = Objects.requireNonNull(registrations);
    }

    /** One page per configured asset. Failed tasks do not monopolize the front page or another asset. */
    public synchronized Cycle tick() {
        if (!schedule.enabled()) {
            return new Cycle(false, 0, 0, 0, 0, 0, 0, 0);
        }
        int discovered = 0, archived = 0, registered = 0, reconciliation = 0, blocked = 0, unavailable = 0, failures = 0;
        for (Target target : schedule.targets()) {
            Page page;
            try {
                page = discovery.read(target.access(), target.scope(), cursors.get(target.scope()), schedule.pageSize());
                validatePage(target, page);
            } catch (RuntimeException failure) {
                failures++;
                continue;
            }
            for (Task task : page.items()) {
                discovered++;
                boolean invoked = false;
                try {
                    if (task.phase() == Phase.VERIFIED) {
                        var command = BackupTaskCommands.registration(schedule.binding(), target.access(), task,
                                proofs.registrationReferences(task));
                        invoked = true;
                        var result = registrations.once(command);
                        if (result != null && result.registrationAndRunCompletionRecorded()
                                && result.completedRunVersion() != null
                                && result.completedRunVersion() == task.stagedRunVersion() + 1) {
                            registered++;
                        } else {
                            reconciliation++;
                        }
                    } else {
                        // Validate ownership/manual-retry boundary before resolving any external evidence.
                        BackupTaskCommands.requireOriginalArchive(schedule.binding(), target.access(), task);
                        var command = BackupTaskCommands.archive(schedule.binding(), target.access(), task,
                                proofs.archiveReference(task));
                        invoked = true;
                        var result = archives.once(command);
                        if (result != null && result.status() == BackupArchiveDispatch.Status.ARCHIVE_VERIFICATION_RECORDED
                                && result.archiveEventVersion() != null && result.archiveEventVersion() > 0) {
                            archived++;
                        } else {
                            reconciliation++;
                        }
                    }
                } catch (RuntimeException failure) {
                    if (invoked) {
                        // A consumer may have committed before return authorization or its reply failed.
                        reconciliation++;
                    } else if (failure instanceof DomainException denied
                            && (denied.status == 403 || denied.status == 404 || denied.status == 409)) {
                        blocked++;
                    } else {
                        unavailable++;
                    }
                }
            }
            if (page.nextAfter() == null) {
                cursors.remove(target.scope());
            } else {
                cursors.put(target.scope(), page.nextAfter());
            }
        }
        return new Cycle(true, discovered, archived, registered, reconciliation, blocked, unavailable, failures);
    }

    /** Explicit caller-owned lifecycle. The supplied stop signal is checked between bounded cycles. */
    public void run(BooleanSupplier stopRequested, Consumer<Cycle> observer) throws InterruptedException {
        Objects.requireNonNull(stopRequested);
        Objects.requireNonNull(observer);
        if (!schedule.enabled()) {
            observer.accept(tick());
            return;
        }
        while (!stopRequested.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            observer.accept(tick());
            if (!stopRequested.getAsBoolean()) {
                Thread.sleep(schedule.pollMillis());
            }
        }
    }

    private void validatePage(Target target, Page page) {
        if (page == null || page.items().size() > schedule.pageSize()
                || page.nextAfter() != null && !page.nextAfter().scope().equals(target.scope())) {
            throw new IllegalStateException("Backup discovery returned an invalid bounded page");
        }
        for (Task task : page.items()) {
            if (!task.scope().tenantId().equals(target.scope().tenantId())
                    || !task.scope().assetId().equals(target.scope().assetId())) {
                throw new IllegalStateException("Backup discovery returned an invalid task scope");
            }
        }
    }

    private static void boundary(DataSource source, PostgresBackupTaskAuthorization authorization,
            Access access, AssetScope scope, Operation operation) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout='5s'");
                statement.execute("SET LOCAL statement_timeout='10s'");
            }
            try {
                authorization.require(connection, access, scope.tenantId(), scope.assetId(), operation);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Failure remains denied; no consumer is dispatched from a failed check.
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new DomainException(503, "BACKUP_TASK_AUTHORIZATION_UNAVAILABLE", "当前备份任务授权无法核对");
        }
    }
}
