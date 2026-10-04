package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.assetexport.AssetExportJobRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class AssetExportJobConfiguration {
    @Bean AssetExportJobRepository assetExportJobRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Export jobs require real PostgreSQL transactions");
        return new PostgresAssetExportRepository(testDataSource,postgres);
    }
}
