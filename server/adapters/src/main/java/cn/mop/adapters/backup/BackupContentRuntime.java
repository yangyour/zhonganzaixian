package cn.mop.adapters.backup;

import cn.mop.adapters.oss.OssProjectStore;
import cn.mop.adapters.oss.OssStorageSettings;
import cn.mop.domain.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Function;

/** Explicit OSS plus optional approved KMS assembly. Neither opening nor probing it grants device or download access. */
public final class BackupContentRuntime implements AutoCloseable {
    private final OssProjectStore store;
    private final BackupKmsProvider.Session kms;
    private final BackupEnvelopeEncryption crypto;

    private BackupContentRuntime(OssProjectStore store, BackupKmsProvider.Session kms, BackupEnvelopeEncryption crypto) {
        this.store = store;
        this.kms = kms;
        this.crypto = crypto;
    }

    public static BackupContentRuntime open(Function<String, String> settings, boolean requireKms,
            BackupEnvelopeEncryption.Authorizer authorizer) {
        return open(settings, requireKms, authorizer, ServiceLoader.load(BackupKmsProvider.class));
    }

    static BackupContentRuntime open(Function<String, String> settings, boolean requireKms,
            BackupEnvelopeEncryption.Authorizer authorizer, Iterable<BackupKmsProvider> providers) {
        if (!"true".equals(settings.apply("MOP_BACKUP_CONTENT_ENABLED"))) {
            throw pending("BACKUP_CONTENT_NOT_CONFIGURED");
        }
        BackupKmsProvider factory = null;
        int limit = 0;
        if (requireKms) {
            factory = select(settings.apply("MOP_BACKUP_KMS_PROVIDER"), providers);
            try {
                limit = Integer.parseInt(settings.apply("MOP_BACKUP_PLAINTEXT_LIMIT_BYTES"));
                if (limit < 1 || limit > BackupEnvelopeEncryption.MAX_PLAINTEXT_BYTES) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException failure) {
                throw pending("BACKUP_PLAINTEXT_LIMIT_NOT_CONFIGURED");
            }
        }
        OssProjectStore store = null;
        BackupKmsProvider.Session kms = null;
        try {
            // Existing scoped, private, no-overwrite OSS transport; no replacement bucket or local store.
            store = new OssProjectStore(OssStorageSettings.from(settings));
            if (factory != null) {
                kms = factory.open(settings);
                if (kms == null || !kms.ready()) {
                    throw pending("BACKUP_KMS_UNAVAILABLE");
                }
            }
            var crypto = kms == null ? null : new BackupEnvelopeEncryption(kms, authorizer, limit);
            return new BackupContentRuntime(store, kms, crypto);
        } catch (RuntimeException failure) {
            close(kms);
            close(store);
            if (failure instanceof DomainException safe && "BACKUP_KMS_UNAVAILABLE".equals(safe.code)) {
                throw pending("BACKUP_KMS_UNAVAILABLE");
            }
            throw pending("BACKUP_CONTENT_UNAVAILABLE");
        }
    }

    public BackupEnvelopeArchive.ObjectStore objects() {
        return store.backups();
    }

    public BackupEnvelopeEncryption crypto() {
        if (crypto == null) {
            throw pending("BACKUP_KMS_NOT_CONFIGURED");
        }
        return crypto;
    }

    public boolean ready() {
        try {
            return store.ready() && (kms == null || kms.ready());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static BackupKmsProvider select(String id, Iterable<BackupKmsProvider> providers) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
            throw pending("BACKUP_KMS_NOT_CONFIGURED");
        }
        List<BackupKmsProvider> matches = new ArrayList<>();
        try {
            for (BackupKmsProvider provider : providers) {
                if (id.equals(provider.id())) {
                    matches.add(provider);
                }
            }
        } catch (RuntimeException | java.util.ServiceConfigurationError failure) {
            throw pending("BACKUP_KMS_UNAVAILABLE");
        }
        if (matches.size() != 1) {
            throw pending("BACKUP_KMS_NOT_CONFIGURED");
        }
        return matches.get(0);
    }

    @Override
    public void close() {
        close(kms);
        close(store);
    }

    private static void close(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // Cleanup diagnostics must not expose provider credentials or raw exception text.
            }
        }
    }

    private static DomainException pending(String code) {
        return new DomainException(503, code, "备份存储或受信任密钥服务尚未配置或不可用");
    }
}
