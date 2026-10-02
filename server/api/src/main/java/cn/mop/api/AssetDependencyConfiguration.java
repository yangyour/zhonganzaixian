package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.assetdependency.AssetDependencyRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import cn.mop.persistence.PostgresAssetDependencyRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration @Profile("remote_test")
public class AssetDependencyConfiguration {
    @Bean AssetDependencyRepository assetDependencyRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Dependency drafts require the real PostgreSQL platform repository");
        return new PostgresAssetDependencyRepository(testDataSource,postgres);
    }
}
