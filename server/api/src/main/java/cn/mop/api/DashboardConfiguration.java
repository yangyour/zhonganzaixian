package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardAvailabilityReader;
import cn.mop.persistence.PostgresDashboardAvailabilityReader;
import cn.mop.persistence.PostgresPlatformRepository;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import cn.mop.persistence.PostgresDashboardPendingTicketsReader;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration
@Profile("remote_test")
public class DashboardConfiguration {
    @Bean cn.mop.domain.monitoring.EventNoiseTrendReader eventNoiseTrendReader(DataSource source,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Event noise trend requires designated PostgreSQL");
        return new cn.mop.persistence.PostgresEventNoiseTrendReader(source,postgres);
    }

    @Bean cn.mop.domain.operations.EventOperationalMetricsReader eventOperationalMetricsReader(DataSource source,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Event metrics require designated PostgreSQL");
        return new cn.mop.persistence.PostgresEventOperationalMetricsReader(source,postgres);
    }

    /** M1-6 pagination and full-population SLA use the actual PostgreSQL repositories and feature gates. */
    @Bean
    cn.mop.domain.dashboard.DashboardActionableTicketsReader dashboardActionableTicketsReader(
            DataSource testDataSource, PlatformRepository platform,
            cn.mop.domain.ticket.TicketLifecycleRepository lifecycle, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (!(platform instanceof PostgresPlatformRepository postgres)
                || !(lifecycle instanceof cn.mop.persistence.PostgresTicketLifecycleRepository actualLifecycle)) {
            throw new IllegalStateException("Actionable dashboard requires real configured PostgreSQL repositories");
        }
        return new cn.mop.persistence.PostgresDashboardActionableTicketsReader(testDataSource, postgres, actualLifecycle, mapper);
    }

    /** V2 uses the already configured lifecycle consumer, including its disabled/unknown boundary. */
    @Bean
    cn.mop.domain.dashboard.DashboardPendingTicketSlaReader dashboardPendingTicketSlaReader(
            DataSource testDataSource, PlatformRepository platform,
            cn.mop.domain.ticket.TicketLifecycleRepository lifecycle, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (!(platform instanceof PostgresPlatformRepository postgres)
                || !(lifecycle instanceof cn.mop.persistence.PostgresTicketLifecycleRepository actualLifecycle)) {
            throw new IllegalStateException("Dashboard SLA requires real configured PostgreSQL repositories");
        }
        return new cn.mop.persistence.PostgresDashboardPendingTicketSlaReader(testDataSource, postgres, actualLifecycle, mapper);
    }

    /** Independent fact endpoint preserves the existing dashboard availability contract. */
    @Bean
    DashboardPendingTicketsReader dashboardPendingTicketsReader(DataSource testDataSource, PlatformRepository platform) {
        if (!(platform instanceof PostgresPlatformRepository postgres)) {
            throw new IllegalStateException("Dashboard ticket facts require real PostgreSQL");
        }
        return new PostgresDashboardPendingTicketsReader(testDataSource, postgres);
    }

    @Bean DashboardAvailabilityReader dashboardAvailabilityReader(DataSource testDataSource, PlatformRepository platform) {
        if (!(platform instanceof PostgresPlatformRepository postgres)) {
            throw new IllegalStateException("Dashboard authorization requires real PostgreSQL");
        }
        return new PostgresDashboardAvailabilityReader(testDataSource, postgres);
    }
}
