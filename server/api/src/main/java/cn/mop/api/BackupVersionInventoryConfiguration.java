package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.persistence.BackupGrantFile;
import cn.mop.persistence.PostgresBackupTaskAuthorization;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Opt-in metadata provider; no draft capability, role fallback, storage access or write registration. */
@Configuration
@Profile("remote_test")
public class BackupVersionInventoryConfiguration {
    @Bean
    @ConditionalOnMissingBean(BackupVersionInventoryQuery.class)
    BackupVersionInventoryQuery backupVersionInventoryQuery(DataSource testDataSource, Environment environment) {
        return configured(testDataSource, environment::getProperty);
    }

    static BackupVersionInventoryQuery configured(DataSource source, Function<String, String> setting) {
        return (access, scope, before, limit) -> {
            String path = setting.apply("MOP_BACKUP_ACCESS_GRANTS_FILE");
            String binding = setting.apply("MOP_BACKUP_VERSION_INVENTORY_BINDING");
            if (path == null || path.isBlank() || binding == null || binding.isBlank()) {
                throw notConfigured();
            }
            BackupGrantFile grants;
            PostgresBackupTaskAuthorization authorization;
            try {
                grants = new BackupGrantFile(Path.of(path));
                grants.get(); // Detect an absent/invalid provider before a database query or empty result.
                authorization = new PostgresBackupTaskAuthorization(binding, grants, Clock.systemUTC());
            } catch (DomainException failure) {
                if ("BACKUP_ACCESS_NOT_CONFIGURED".equals(failure.code)) {
                    throw notConfigured();
                }
                throw unavailable();
            } catch (RuntimeException failure) {
                throw unavailable();
            }
            return BackupVersionInventoryQuery.postgres(source, authorization.versionAuthorizer(),
                    authorization.inventoryAuthorizer()).inventory(access, scope, before, limit);
        };
    }

    private static DomainException notConfigured() {
        return new DomainException(503, "BACKUP_VERSION_INVENTORY_NOT_CONFIGURED", "备份版本列表的独立授权配置尚未提供");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "BACKUP_VERSION_INVENTORY_UNAVAILABLE", "备份版本列表的独立授权配置无法核对");
    }
}
