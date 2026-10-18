package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.sla.ServiceTierRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

/** Tenant service tier snapshot and append-only change history (D16 / V35). */
@Configuration @Profile("remote_test")
public class ServiceTierConfiguration {
    @Bean
    ServiceTierRepository serviceTierRepository(DataSource source, PlatformRepository platform) {
        if (!(platform instanceof PostgresPlatformRepository p))
            throw new IllegalStateException("Service tier repository requires designated PostgreSQL");
        return new PostgresServiceTierRepository(source, p);
    }
}
