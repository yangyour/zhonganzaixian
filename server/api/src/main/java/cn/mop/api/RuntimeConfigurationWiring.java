package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class RuntimeConfigurationWiring {
    @Bean RuntimeConfigurationCatalog runtimeConfigurationCatalog(ObjectMapper mapper){return new RuntimeConfigurationCatalog(mapper);}
    @Bean MonitoringRefreshPolicy monitoringRefreshPolicy(RuntimeConfigurationRepository repository,RuntimeConfigurationCatalog catalog){return new MonitoringRefreshPolicy(repository,catalog);}
    @Bean RuntimeConfigurationRepository runtimeConfigurationRepository(DataSource source,PlatformRepository platform,ObjectMapper mapper){if(!(platform instanceof PostgresPlatformRepository p))throw new IllegalStateException("Runtime configuration requires designated PostgreSQL");return new PostgresRuntimeConfigurationRepository(source,p,mapper);}
}
