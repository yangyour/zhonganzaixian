package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.search.GlobalSearchRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class GlobalSearchConfiguration {
    @Bean GlobalSearchRepository globalSearchRepository(DataSource testDataSource,PlatformRepository platform){if(!(platform instanceof PostgresPlatformRepository))throw new IllegalStateException("Grouped search requires the real PostgreSQL platform repository");return new PostgresGlobalSearchRepository(testDataSource);}
}
