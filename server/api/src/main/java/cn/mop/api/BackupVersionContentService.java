package cn.mop.api;

import cn.mop.adapters.backup.BackupContentRuntime;
import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.adapters.backup.RedactedBackupDiff;
import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.persistence.BackupGrantFile;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupTaskAuthorization;
import cn.mop.persistence.PostgresBackupVersionRepository;
import cn.mop.persistence.PostgresBackupVersionRepository.Candidate;
import cn.mop.persistence.PostgresBackupVersionRepository.InventoryScope;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.sql.DataSource;

/** Current independent permissions, immutable metadata, object verification and pre-delivery audit for sensitive reads. */
public final class BackupVersionContentService {
    interface Resources extends AutoCloseable {
        byte[] read(Candidate original);
        byte[] decrypt(Access access, Candidate original, byte[] encrypted);
        @Override
        void close();
    }

    interface Boundary {
        Candidate metadata(Access access, InventoryScope scope, UUID version, Operation operation);
        void require(Access access, UUID asset, Operation operation);
        Resources open(boolean decrypt);
        void audit(Access access, UUID asset, UUID version, String action, String trace);
    }

    @FunctionalInterface
    interface Boundaries {
        Boundary create(Access access);
    }

    /** Owns sensitive bytes until the servlet writes or closes them. No content-bearing toString or byte-array accessor. */
    public static final class Content implements AutoCloseable {
        private final byte[] bytes;
        private final String filename;
        private boolean closed;

        Content(byte[] bytes, UUID version, boolean plaintext) {
            this.bytes = bytes;
            this.filename = "backup-" + version + (plaintext ? ".txt" : ".envelope");
        }

        public int size() { return bytes.length; }
        public String filename() { return filename; }

        public void writeTo(OutputStream output) throws IOException {
            if (closed) {
                throw new IllegalStateException("Backup content already disposed");
            }
            output.write(bytes);
        }

        @Override
        public void close() {
            Arrays.fill(bytes, (byte) 0);
            closed = true;
        }

        @Override
        public String toString() { return "BackupContent[REDACTED]"; }
    }

    private final Boundaries boundaries;

    public BackupVersionContentService(DataSource source, PlatformRepository platform, Function<String, String> settings) {
        this.boundaries = access -> postgres(source, platform, settings, access);
    }

    BackupVersionContentService(Boundaries boundaries) {
        this.boundaries = boundaries;
    }

    public Content download(Access access, InventoryScope scope, UUID version, boolean plaintext,
            String trace, BooleanSupplier cancelled) {
        matching(access, scope);
        checkCancelled(cancelled);
        Boundary boundary = boundaries.create(access);
        Candidate metadata = metadata(boundary, access, scope, version, Operation.VERSION_DOWNLOAD);
        if (plaintext) {
            boundary.require(access, scope.assetId(), Operation.VERSION_DECRYPT);
        }
        byte[] content = null;
        try (Resources resources = boundary.open(plaintext)) {
            content = read(access, metadata, plaintext, boundary, resources, cancelled);
            boundary.require(access, scope.assetId(), Operation.VERSION_DOWNLOAD);
            if (plaintext) {
                boundary.require(access, scope.assetId(), Operation.VERSION_DECRYPT);
            }
            checkCancelled(cancelled);
            boundary.audit(access, scope.assetId(), version, plaintext ? "PLAINTEXT_READ_AUTHORIZED" : "ENVELOPE_READ_AUTHORIZED", trace);
            checkCancelled(cancelled);
            Content output = new Content(content, version, plaintext);
            content = null;
            return output;
        } finally {
            wipe(content);
        }
    }

    public RedactedBackupDiff.Summary compare(Access access, InventoryScope scope, UUID before, UUID after,
            String trace, BooleanSupplier cancelled) {
        matching(access, scope);
        checkCancelled(cancelled);
        Boundary boundary = boundaries.create(access);
        Candidate left = metadata(boundary, access, scope, before, Operation.VERSION_DIFF);
        Candidate right = metadata(boundary, access, scope, after, Operation.VERSION_DIFF);
        boundary.require(access, scope.assetId(), Operation.VERSION_DECRYPT);
        byte[] leftBytes = null, rightBytes = null;
        try (Resources resources = boundary.open(true)) {
            leftBytes = read(access, left, true, boundary, resources, cancelled);
            rightBytes = read(access, right, true, boundary, resources, cancelled);
            if (leftBytes.length > RedactedBackupDiff.MAX_BYTES || rightBytes.length > RedactedBackupDiff.MAX_BYTES) {
                throw new DomainException(413, "BACKUP_DIFF_LIMIT", "配置差异超过当前有界比较上限，未截断比较");
            }
            RedactedBackupDiff.Summary result;
            try (var leftInput = new RedactedBackupDiff.VersionInput(scope.tenantId(), scope.assetId(), before, leftBytes);
                    var rightInput = new RedactedBackupDiff.VersionInput(scope.tenantId(), scope.assetId(), after, rightBytes)) {
                result = new RedactedBackupDiff().compare(scope.tenantId(), scope.assetId(), leftInput, rightInput);
            } catch (DomainException unsupported) {
                if (unsupported.status == 400) {
                    throw new DomainException(422, "BACKUP_DIFF_TEXT_UNSUPPORTED", "版本内容不符合当前UTF-8文本比较边界，未判定为无变化");
                }
                throw unsupported;
            }
            boundary.require(access, scope.assetId(), Operation.VERSION_DIFF);
            boundary.require(access, scope.assetId(), Operation.VERSION_DECRYPT);
            checkCancelled(cancelled);
            boundary.audit(access, scope.assetId(), after, "REDACTED_DIFF_AUTHORIZED", trace);
            return result;
        } finally {
            wipe(leftBytes);
            wipe(rightBytes);
        }
    }

    private static byte[] read(Access access, Candidate original, boolean plaintext, Boundary boundary,
            Resources resources, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        byte[] encrypted = resources.read(original);
        try {
            if (encrypted == null || encrypted.length != original.encryptedBytes()) {
                throw unavailable();
            }
            BackupArchivePayload parsed;
            try {
                parsed = BackupArchivePayload.parse(encrypted, original.encryptedSha256());
            } catch (RuntimeException corrupted) {
                throw unavailable();
            }
            if (!parsed.scope().equals(original.scope()) || !parsed.keyReference().equals(original.keyReference())) {
                throw unavailable();
            }
            checkCancelled(cancelled);
            if (plaintext) {
                boundary.require(access, original.scope().assetId(), Operation.VERSION_DECRYPT);
                byte[] result = resources.decrypt(access, original, encrypted);
                if (result == null || result.length > BackupEnvelopeEncryption.MAX_PLAINTEXT_BYTES) {
                    wipe(result);
                    throw unavailable();
                }
                return result;
            }
            byte[] result = encrypted;
            encrypted = null;
            return result;
        } finally {
            wipe(encrypted);
        }
    }

    private static Boundary postgres(DataSource source, PlatformRepository platform, Function<String, String> settings, Access access) {
        try {
            String file = settings.apply("MOP_BACKUP_ACCESS_GRANTS_FILE");
            String binding = settings.apply("MOP_BACKUP_VERSION_CONTENT_BINDING");
            if (file == null || binding == null) {
                throw unavailable();
            }
            var grants = new BackupGrantFile(Path.of(file));
            grants.get();
            var authority = new PostgresBackupTaskAuthorization(binding, grants, Clock.systemUTC());
            var repository = PostgresBackupVersionRepository.readOnly(source, authority.versionAuthorizer());
            return new Boundary() {
                public Candidate metadata(Access identity, InventoryScope scope, UUID version, Operation operation) {
                    var versionAccess = new PostgresBackupVersionRepository.Access(identity.tenantId(), identity.actorId(), identity.permissionVersion());
                    return repository.findVersion(versionAccess, scope, version,
                            (connection, member, target) -> authority.require(connection, identity, target.tenantId(), target.assetId(), operation))
                            .orElseThrow(() -> new DomainException(404, "BACKUP_VERSION_NOT_FOUND", "当前范围没有已登记备份版本"))
                            .registration().candidate();
                }

                public void require(Access identity, UUID asset, Operation operation) {
                    authority.requireCurrent(source, identity, asset, operation);
                }

                public Resources open(boolean decrypt) {
                    var runtime = BackupContentRuntime.open(settings, decrypt, (identity, scope, operation) -> {
                        if (operation != BackupEnvelopeEncryption.Operation.DECRYPT) {
                            return false;
                        }
                        authority.requireCurrent(source, new Access(identity.tenantId(), identity.actorId(), identity.permissionVersion()),
                                scope.assetId(), Operation.VERSION_DECRYPT);
                        return true;
                    });
                    return new Resources() {
                        public byte[] read(Candidate original) {
                            require(access, original.scope().assetId(), Operation.VERSION_READ);
                            return runtime.objects().read(cryptoScope(original), original.encryptedBytes(), original.encryptedSha256());
                        }

                        public byte[] decrypt(Access identity, Candidate original, byte[] encrypted) {
                            var scope = cryptoScope(original);
                            var crypto = runtime.crypto();
                            return crypto.open(new BackupEnvelopeEncryption.Access(identity.tenantId(), identity.actorId(), identity.permissionVersion()),
                                    scope, crypto.decode(encrypted, original.encryptedSha256(), scope));
                        }

                        public void close() { runtime.close(); }
                    };
                }

                public void audit(Access identity, UUID asset, UUID version, String action, String trace) {
                    platform.audit(identity.actorId().toString(), identity.tenantId().toString(), action,
                            "backup_version", version.toString(), "AUTHORIZED", "CONTENT_NOT_LOGGED", trace);
                }
            };
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static BackupEnvelopeEncryption.Scope cryptoScope(Candidate original) {
        var scope = original.scope();
        return new BackupEnvelopeEncryption.Scope(scope.tenantId(), scope.assetId(), scope.runId(), scope.attemptNumber(), scope.versionId());
    }

    private static Candidate metadata(Boundary boundary, Access access, InventoryScope scope, UUID version, Operation operation) {
        Candidate result = boundary.metadata(access, scope, version, operation);
        if (result == null || result.scope() == null || !scope.tenantId().equals(result.scope().tenantId())
                || !scope.assetId().equals(result.scope().assetId()) || !version.equals(result.scope().versionId())) {
            throw new DomainException(404, "BACKUP_VERSION_NOT_FOUND", "当前范围没有已登记备份版本");
        }
        return result;
    }

    private static void matching(Access access, InventoryScope scope) {
        if (access == null || scope == null || !access.tenantId().equals(scope.tenantId())) {
            throw new DomainException(404, "BACKUP_VERSION_NOT_FOUND", "当前范围没有已登记备份版本");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new DomainException(409, "BACKUP_ACCESS_CANCELLED", "备份读取或比较已取消");
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static DomainException unavailable() {
        return new DomainException(503, "BACKUP_CONTENT_UNAVAILABLE", "备份内容授权、对象或密钥服务尚未核对");
    }
}
