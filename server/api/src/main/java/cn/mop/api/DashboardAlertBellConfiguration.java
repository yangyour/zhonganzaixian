package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardAlertBellReader;
import cn.mop.persistence.PostgresDashboardAlertBellReader;
import cn.mop.persistence.PostgresPlatformRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicit persistent reader for the header bell; the search endpoint does not supply its count. */
@Configuration
@Profile("remote_test")
public class DashboardAlertBellConfiguration {
    @Bean
    DashboardAlertBellReader dashboardAlertBellReader(DataSource testDataSource, PlatformRepository platform) {
        if (!(platform instanceof PostgresPlatformRepository postgres)) {
            throw new IllegalStateException("Alert bell requires the designated PostgreSQL repository");
        }
        return new PostgresDashboardAlertBellReader(testDataSource, postgres);
    }
}
