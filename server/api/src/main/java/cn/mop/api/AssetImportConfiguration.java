package cn.mop.api;

import cn.mop.adapters.MinioAssetImportStore;
import cn.mop.adapters.oss.*;
import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.util.*;

/** Restricted designated PostgreSQL/MinIO only; no startup DDL, parser invocation or fixture adapter. */
@Configuration @Profile("remote_test")
public class AssetImportConfiguration {
    private static final System.Logger LOG=System.getLogger(AssetImportConfiguration.class.getName());
    @Bean AssetImportPolicy assetImportPolicy(Environment environment){return new AssetImportPolicy(environment.getProperty("MOP_IMPORT_ALLOWED_FORMATS"));}
    @Bean AssetImportSourceRepository assetImportSourceRepository(DataSource testDataSource,ObjectMapper mapper,PlatformRepository platform){if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("FAIL_CLOSED: designated PostgreSQL import transactions required");return new PostgresAssetImportRepository(testDataSource,mapper,postgres);}
    @Bean AssetImportObjectStore assetImportObjectStore(Environment environment){
        try {
            var selection=StorageProviderSelection.from(environment::getProperty);
            if(selection.mode()==StorageProviderSelection.Mode.DISABLED)return unavailable();
            if(selection.mode()==StorageProviderSelection.Mode.OSS)return new OssProjectStore(selection.ossSettings()).imports();
        } catch(RuntimeException invalid) {LOG.log(System.Logger.Level.WARNING,"Import provider configuration unavailable exception_type={0}",invalid.getClass().getName());return unavailable();}
        String endpoint=environment.getProperty("MOP_IMPORT_S3_ENDPOINT"),bucket=environment.getProperty("MOP_IMPORT_S3_BUCKET"),access=environment.getProperty("MOP_IMPORT_S3_ACCESS_KEY"),secret=environment.getProperty("MOP_IMPORT_S3_SECRET_KEY");
        if(java.util.stream.Stream.of(endpoint,bucket,access,secret).anyMatch(value->value==null||value.isBlank()))return unavailable();
        try{return new MinioAssetImportStore(endpoint,bucket,access,secret);}catch(RuntimeException invalid){LOG.log(System.Logger.Level.WARNING,"Import storage configuration unavailable exception_type={0}",invalid.getClass().getName());return unavailable();}
    }
    @Bean ImportDependencyProbe assetImportDependencyProbe(Environment environment,AssetImportSourceRepository sources,AssetImportObjectStore store){
        boolean enabled=StorageProviderSelection.capabilityRequested(environment::getProperty)||List.of("MOP_IMPORT_ALLOWED_FORMATS","MOP_IMPORT_S3_ENDPOINT","MOP_IMPORT_S3_BUCKET","MOP_IMPORT_S3_ACCESS_KEY","MOP_IMPORT_S3_SECRET_KEY").stream().map(environment::getProperty).anyMatch(value->value!=null&&!value.isBlank());
        return new ImportDependencyProbe(enabled,"CSV,XLSX".equals(environment.getProperty("MOP_IMPORT_ALLOWED_FORMATS")),sources,store);
    }
    public record ImportReadiness(boolean enabled,boolean policyReady,boolean schemaReady,boolean storageReady,List<String> reasons){
        public ImportReadiness{reasons=List.copyOf(reasons);}
        public boolean ready(){return enabled&&policyReady&&schemaReady&&storageReady&&reasons.isEmpty();}
        public Map<String,Object> dto(){return ApiResponse.map("enabled",enabled,"ready",ready(),"policy_ready",policyReady,"schema_ready",schemaReady,"storage_ready",storageReady,"worker_activity","NOT_PROBED","business_status","CONFIG_PENDING","can_import",false,"reasons",reasons);}
    }
    public static final class ImportDependencyProbe {
        private final boolean enabled,policyReady;private final AssetImportSourceRepository sources;private final AssetImportObjectStore store;
        ImportDependencyProbe(boolean enabled,boolean policyReady,AssetImportSourceRepository sources,AssetImportObjectStore store){this.enabled=enabled;this.policyReady=policyReady;this.sources=sources;this.store=store;}
        public ImportReadiness status(){if(!enabled)return new ImportReadiness(false,false,false,false,List.of("ASSET_IMPORT_NOT_CONFIGURED"));List<String> reasons=new ArrayList<>();if(!policyReady)reasons.add("ASSET_IMPORT_POLICY_NOT_CONFIGURED");boolean schema,storage;try{schema=sources.ready();}catch(RuntimeException unavailable){schema=false;}try{storage=store.ready();}catch(RuntimeException unavailable){storage=false;}if(!schema)reasons.add("ASSET_IMPORT_SCHEMA_OR_RUNTIME_ACL_UNAVAILABLE");if(!storage)reasons.add("ASSET_IMPORT_STORAGE_UNAVAILABLE");return new ImportReadiness(true,policyReady,schema,storage,reasons);}
    }
    private static AssetImportObjectStore unavailable(){return new AssetImportObjectStore(){public boolean ready(){return false;}public void storeImmutable(String key,byte[] original,ImportFormat format,String sha){throw failure();}public byte[] read(String key,long size,String sha){throw failure();}private DomainException failure(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","指定导入源存储未配置或不可用");}};}
}
