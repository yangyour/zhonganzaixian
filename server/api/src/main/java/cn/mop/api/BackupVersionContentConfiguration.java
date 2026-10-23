package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("remote_test")
public class BackupVersionContentConfiguration {
    /** Missing private configuration remains an explicit service-unavailable response on the sensitive operation only. */
    @Bean
    @ConditionalOnMissingBean(BackupVersionContentService.class)
    BackupVersionContentService backupVersionContentService(DataSource source, PlatformRepository platform, Environment environment) {
        if (!(platform instanceof cn.mop.persistence.PostgresPlatformRepository)) {
            throw new IllegalStateException("Backup content access requires durable PostgreSQL authorization and audit");
        }
        return new BackupVersionContentService(source, platform, environment::getProperty);
    }
}
