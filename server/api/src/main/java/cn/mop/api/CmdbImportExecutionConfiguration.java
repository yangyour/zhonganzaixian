package cn.mop.api;

import cn.mop.adapters.cmdb.CmdbImportPlanner;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.assetimport.CmdbImportExecutionRepository;
import cn.mop.domain.assetimport.source.AssetImportSourceRepository;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository;
import cn.mop.persistence.PostgresAssetImportRepository;
import cn.mop.persistence.PostgresCmdbImportRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import java.util.LinkedHashMap;

/** Real designated database only; no migration, default policy, fixture or collection side effect. */
@Configuration
@Profile("remote_test")
public class CmdbImportExecutionConfiguration {
    @Bean
    CmdbImportExecutionRepository cmdbImportExecutionRepository(PlatformRepository platform,
            AssetImportSourceRepository sources, ManualAssetMetadataRepository metadata,
            RuntimeConfigurationCatalog catalog, ObjectMapper mapper) {
        if (!(platform instanceof PostgresPlatformRepository postgres)
                || !(sources instanceof PostgresAssetImportRepository durableSources)) {
            throw new IllegalStateException("CMDB import requires designated PostgreSQL transactions");
        }
        var hashes = new LinkedHashMap<String, String>();
        for (String key : PostgresCmdbImportRepository.CONFIGURATION_KEYS) {
            hashes.put(key, catalog.hash(key));
        }
        return new PostgresCmdbImportRepository(postgres, durableSources, metadata,
                new CmdbImportPlanner(mapper), mapper, hashes);
    }
}
