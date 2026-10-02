package cn.mop.importworker;

import cn.mop.adapters.MinioAssetImportStore;
import cn.mop.adapters.oss.OssProjectStore;
import cn.mop.domain.assetimport.source.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Imported explicitly by the import application; never scans the photo application. */
@Configuration(proxyBeanMethods=false)
class AssetImportWorkerConfiguration {
    @Bean AssetImportWorkerRuntimeSettings assetImportWorkerSettings(Environment environment){
        String[] profiles=environment.getActiveProfiles().length==0?environment.getDefaultProfiles():environment.getActiveProfiles();
        return AssetImportWorkerRuntimeSettings.from(profiles,environment::getProperty);
    }
    @Bean(destroyMethod="close") HikariDataSource assetImportDataSource(AssetImportWorkerRuntimeSettings settings){
        HikariConfig configuration=new HikariConfig();configuration.setJdbcUrl(settings.jdbcUrl());configuration.setUsername(settings.databaseUser());configuration.setPassword(settings.databasePassword());
        configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(0);configuration.setConnectionTimeout(5000);configuration.setValidationTimeout(3000);configuration.setInitializationFailTimeout(5000);configuration.setPoolName("mop-asset-import-worker");
        configuration.addDataSourceProperty("connectTimeout","5");configuration.addDataSourceProperty("socketTimeout","20");configuration.addDataSourceProperty("tcpKeepAlive","true");
        try{return new HikariDataSource(configuration);}catch(RuntimeException unavailable){throw new IllegalStateException("FAIL_CLOSED: designated restricted import PostgreSQL connection is unavailable");}
    }
    @Bean ObjectMapper assetImportObjectMapper(){return new ObjectMapper();}
    @Bean AssetImportSourceRepository assetImportRepository(HikariDataSource assetImportDataSource,ObjectMapper assetImportObjectMapper){return new PostgresAssetImportRepository(assetImportDataSource,assetImportObjectMapper,new PostgresPlatformRepository(assetImportDataSource,assetImportObjectMapper));}
    @Bean AssetImportObjectStore assetImportStore(AssetImportWorkerRuntimeSettings settings){if(settings.ossSettings()!=null){try{return new OssProjectStore(settings.ossSettings()).imports();}catch(RuntimeException invalid){throw new IllegalStateException("FAIL_CLOSED: OSS import client could not be initialized; private details withheld");}}return new MinioAssetImportStore(settings.storageEndpoint(),settings.storageBucket(),settings.storageAccessKey(),settings.storageSecretKey());}
    @Bean(destroyMethod="close") AssetImportParserProcess assetImportParser(AssetImportWorkerRuntimeSettings settings){return new AssetImportParserProcess(settings.parserJar());}
    @Bean AssetImportWorkerRuntime assetImportWorker(AssetImportSourceRepository assetImportRepository,AssetImportObjectStore assetImportStore,AssetImportParserProcess assetImportParser){return new AssetImportWorkerRuntime(assetImportRepository,assetImportStore,assetImportParser);}
}
