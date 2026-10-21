package cn.mop.api;

import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import cn.mop.persistence.PostgresMonitoringEventCenterRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;

@Configuration
@Profile("remote_test")
class MonitoringEventCenterConfiguration {
    @Bean
    MonitoringEventCenterRepository monitoringEventCenterRepository(DataSource dataSource, PostgresPlatformRepository platform) {
        return new PostgresMonitoringEventCenterRepository(dataSource, platform);
    }
}
