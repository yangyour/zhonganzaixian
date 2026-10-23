package cn.mop.api;

import cn.mop.domain.assetmetadata.AssetLedgerFactsRepository;
import cn.mop.persistence.PostgresAssetLedgerFactsRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

/** Reuses the designated database and existing frozen metadata tables. */
@Configuration
@Profile("remote_test")
public class AssetLedgerFactsConfiguration {
    @Bean
    AssetLedgerFactsRepository assetLedgerFactsRepository(DataSource source) {
        return new PostgresAssetLedgerFactsRepository(source);
    }
}
