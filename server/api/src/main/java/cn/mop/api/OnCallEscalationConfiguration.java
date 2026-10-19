package cn.mop.api;

import cn.mop.domain.sla.OnCallEscalationRepository;
import cn.mop.persistence.PostgresOnCallEscalationRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;

@Configuration
@Profile("remote_test")
class OnCallEscalationConfiguration {
    @Bean
    OnCallEscalationRepository onCallEscalationRepository(DataSource dataSource, PostgresPlatformRepository platform) {
        return new PostgresOnCallEscalationRepository(dataSource, platform);
    }
}
