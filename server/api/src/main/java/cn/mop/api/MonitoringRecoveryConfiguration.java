package cn.mop.api;

import cn.mop.persistence.PostgresMonitoringRecoveryRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Explicit observation commands reuse the configured source; startup never samples devices or migrates SQL. */
@Configuration
@Profile("remote_test")
class MonitoringRecoveryConfiguration {
    @Bean
    MonitoringRecoverySource monitoringRecoverySource(NetworkMonitoringService monitoring, Environment environment) {
        return new MonitoringRecoverySource(monitoring, environment.getProperty("MOP_NETWORK_MONITORING_ENDPOINT"));
    }

    @Bean
    PostgresMonitoringRecoveryRepository monitoringRecoveryRepository(DataSource source, PostgresPlatformRepository platform,
            MonitoringRecoverySource reader, RuntimeConfigurationCatalog catalog) {
        return new PostgresMonitoringRecoveryRepository(source, platform, reader,
            catalog.hash(PostgresMonitoringRecoveryRepository.POLICY_KEY));
    }
}
