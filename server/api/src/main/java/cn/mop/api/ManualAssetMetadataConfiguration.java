package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration @Profile("remote_test")
public class ManualAssetMetadataConfiguration {
    @Bean ManualAssetMetadataRepository manualAssetMetadataRepository(DataSource testDataSource,PlatformRepository platform,ObjectMapper mapper){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Manual asset metadata requires the real PostgreSQL platform repository");
        return new PostgresManualAssetMetadataRepository(testDataSource,postgres,mapper);
    }
}
