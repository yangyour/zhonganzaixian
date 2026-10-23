package cn.mop.worker.backup;

import cn.mop.adapters.backup.BackupContentRuntime;
import cn.mop.adapters.backup.BackupEnvelopeArchive;
import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.persistence.BackupGrantFile;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupTaskAuthorization;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit standalone postprocessing mode. Never starts capture, migrates schema or falls back to another worker. */
public final class BackupWorkerMain {
    private BackupWorkerMain() {}

    public static void run(boolean checkOnly) {
        var stopped = new AtomicBoolean();
        Thread owner = Thread.currentThread();
        Thread hook = new Thread(() -> {
            stopped.set(true);
            owner.interrupt();
            try {
                owner.join(30000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "backup-postprocessing-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            var settings = BackupWorkerSettings.from(System::getenv);
            var proof = new SignedBackupCollectionProofs(settings.proofDirectory(), settings.collectorPublicKey(),
                    settings.collectorBinding(), Clock.systemUTC());
            var config = new HikariConfig();
            config.setJdbcUrl(settings.jdbcUrl());
            config.setUsername("mop_test_runtime");
            config.setPassword(settings.password());
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(5000);
            config.setValidationTimeout(3000);
            config.setInitializationFailTimeout(5000);
            config.addDataSourceProperty("connectTimeout", "5");
            config.addDataSourceProperty("socketTimeout", "20");
            try (var source = new HikariDataSource(config)) {
                var authorization = new PostgresBackupTaskAuthorization("backup-task-binding:" + settings.schedule().binding(),
                        new BackupGrantFile(settings.grantsFile()), Clock.systemUTC());
                for (var target : settings.schedule().targets()) {
                    for (Operation operation : BackupWorkerSettings.REQUIRED_OPERATIONS) {
                        authorization.requireCurrent(source, target.access(), target.scope().assetId(), operation);
                    }
                }
                try (var connection = source.getConnection(); var query = connection.prepareStatement("""
                        SELECT to_regclass('public.mop_backup_archive_submission') IS NOT NULL
                           AND to_regclass('public.mop_backup_archive_event') IS NOT NULL
                           AND to_regclass('public.mop_backup_version_registration') IS NOT NULL
                        """)) {
                    query.setQueryTimeout(5);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !rows.getBoolean(1)) {
                            throw new IllegalStateException("CONFIG_PENDING: backup journal schema is unavailable");
                        }
                    }
                }
                try (var runtime = BackupContentRuntime.open(System::getenv, true, (access, scope, operation) -> {
                    if (operation != BackupEnvelopeEncryption.Operation.DECRYPT) {
                        return false;
                    }
                    authorization.requireCurrent(source, new Access(access.tenantId(), access.actorId(), access.permissionVersion()),
                            scope.assetId(), Operation.CRYPTO_VERIFY);
                    return true;
                })) {
                    if (!runtime.ready()) {
                        throw new IllegalStateException("CONFIG_PENDING: actual backup storage/KMS readiness failed");
                    }
                    var archive = new BackupEnvelopeArchive(runtime.crypto(), runtime.objects(), (access, scope, operation) -> {
                        authorization.requireCurrent(source, new Access(access.tenantId(), access.actorId(), access.permissionVersion()),
                                scope.assetId(), operation == BackupEnvelopeArchive.Operation.STORE
                                        ? Operation.ARCHIVE_START : Operation.ARCHIVE_RECONCILE);
                        return true;
                    });
                    var scheduler = BackupTaskScheduler.assemble(settings.schedule(), source, authorization, proof,
                            proof.envelopeVerifier(runtime.crypto()), proof.archiveVerifier(archive), proof.objectVerifier(archive),
                            proof.completionVerifier(), runtime.crypto(), archive);
                    if (checkOnly) {
                        System.out.println("BACKUP_WORKER_DEPENDENCIES_READY capture=false deletion=false processing_started=false");
                        return;
                    }
                    scheduler.run(stopped::get, cycle -> System.out.println("BACKUP_POSTPROCESSING " + cycle));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            throw new IllegalStateException("CONFIG_PENDING: backup worker dependencies or authorization unavailable; private details withheld");
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The JVM now owns shutdown; no other worker/service is stopped.
            }
        }
    }
}
