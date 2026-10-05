package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardAvailabilityReader;
import cn.mop.persistence.PostgresDashboardAvailabilityReader;
import cn.mop.persistence.PostgresPlatformRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration
@Profile("remote_test")
public class DashboardConfiguration {
    @Bean DashboardAvailabilityReader dashboardAvailabilityReader(DataSource testDataSource, PlatformRepository platform) {
        if (!(platform instanceof PostgresPlatformRepository postgres)) {
            throw new IllegalStateException("Dashboard authorization requires real PostgreSQL");
        }
        return new PostgresDashboardAvailabilityReader(testDataSource, postgres);
    }
}
