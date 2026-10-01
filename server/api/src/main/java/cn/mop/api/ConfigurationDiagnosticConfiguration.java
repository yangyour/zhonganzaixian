package cn.mop.api;

import cn.mop.domain.configuration.ConfigurationDiagnosticReader;
import cn.mop.persistence.configuration.PostgresConfigurationDiagnosticReader;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;

/** Designated PostgreSQL only; no fixture, source-body projection or startup migration is registered. */
@Configuration @Profile("remote_test")
public class ConfigurationDiagnosticConfiguration {
    @Bean ConfigurationDiagnosticReader configurationDiagnosticReader(DataSource testDataSource){return new PostgresConfigurationDiagnosticReader(testDataSource);}
}
