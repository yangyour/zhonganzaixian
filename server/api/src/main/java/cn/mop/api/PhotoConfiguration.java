package cn.mop.api;
import cn.mop.adapters.MinioPhotoStore;
import cn.mop.adapters.oss.*;
import cn.mop.domain.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;
@Configuration @Profile("remote_test")
public class PhotoConfiguration {
    private static final System.Logger log=System.getLogger(PhotoConfiguration.class.getName());
    @Bean PhotoPolicy photoPolicy(Environment environment){try{return new PhotoPolicy(environment.getProperty("MOP_PHOTO_ALLOWED_MEDIA_TYPES"));}catch(IllegalStateException invalid){log.log(System.Logger.Level.WARNING,"Photo allowlist unavailable exception_type={0}",invalid.getClass().getName());return new PhotoPolicy(null);}}
    @Bean PhotoObjectStore photoObjectStore(Environment environment){
        try {
            var selection=StorageProviderSelection.from(environment::getProperty);
            if(selection.mode()==StorageProviderSelection.Mode.DISABLED)return unavailableStore();
            if(selection.mode()==StorageProviderSelection.Mode.OSS)return new OssProjectStore(selection.ossSettings()).photos();
        } catch(RuntimeException invalid) {log.log(System.Logger.Level.WARNING,"Photo provider configuration unavailable exception_type={0}",invalid.getClass().getName());return unavailableStore();}
        String endpoint=environment.getProperty("MOP_S3_ENDPOINT"),bucket=environment.getProperty("MOP_S3_BUCKET"),access=environment.getProperty("MOP_S3_ACCESS_KEY"),secret=environment.getProperty("MOP_S3_SECRET_KEY");
        if(java.util.stream.Stream.of(endpoint,bucket,access,secret).anyMatch(value->value==null||value.isBlank()))return unavailableStore();
        try{return new MinioPhotoStore(endpoint,bucket,access,secret);}catch(IllegalStateException invalid){log.log(System.Logger.Level.WARNING,"Photo storage configuration unavailable exception_type={0}",invalid.getClass().getName());return unavailableStore();}
    }
    @Bean PhotoDependencyProbe photoDependencyProbe(Environment environment,PhotoPolicy policy,PhotoObjectStore store,PlatformRepository repository){
        boolean enabled=StorageProviderSelection.capabilityRequested(environment::getProperty)||List.of("MOP_PHOTO_ALLOWED_MEDIA_TYPES","MOP_S3_ENDPOINT","MOP_S3_BUCKET","MOP_S3_ACCESS_KEY","MOP_S3_SECRET_KEY").stream()
            .map(environment::getProperty).anyMatch(value->value!=null&&!value.isBlank());
        return new PhotoDependencyProbe(enabled,policy,store,repository);
    }
    public record PhotoReadiness(boolean enabled,boolean policyReady,boolean schemaReady,boolean storageReady,List<String> reasons){
        public PhotoReadiness { reasons=List.copyOf(reasons); }
        public boolean ready(){return enabled&&policyReady&&schemaReady&&storageReady&&reasons.isEmpty();}
        public Map<String,Object> dto(){return ApiResponse.map("enabled",enabled,"ready",ready(),"policy_ready",policyReady,"schema_ready",schemaReady,"storage_ready",storageReady,"policy_source",policyReady?"USER_DEVELOPMENT_DIRECTION":"PENDING","reasons",reasons);}
    }
    /** Configuration presence enables the capability, but does not stand in for a dependency probe. */
    public static final class PhotoDependencyProbe {
        private final boolean enabled;
        private final PhotoPolicy policy;
        private final PhotoObjectStore store;
        private final PlatformRepository repository;
        PhotoDependencyProbe(boolean enabled,PhotoPolicy policy,PhotoObjectStore store,PlatformRepository repository){this.enabled=enabled;this.policy=policy;this.store=store;this.repository=repository;}
        public PhotoReadiness status(){
            if(!enabled)return new PhotoReadiness(false,false,false,false,List.of("PHOTO_NOT_CONFIGURED"));
            List<String> reasons=new ArrayList<>();boolean policyReady=policy.configured();
            if(!policyReady)reasons.add("PHOTO_POLICY_NOT_CONFIGURED");
            List<String> database;
            try{database=repository.photoDependencyProblems();if(database==null)database=List.of("PHOTO_DATABASE_UNAVAILABLE");}
            catch(RuntimeException unavailable){database=List.of("PHOTO_DATABASE_UNAVAILABLE");}
            reasons.addAll(database);boolean storageReady;
            try{storageReady=store.ready();}catch(RuntimeException unavailable){storageReady=false;}
            if(!storageReady)reasons.add("PHOTO_STORAGE_UNAVAILABLE");
            return new PhotoReadiness(true,policyReady,database.isEmpty(),storageReady,reasons);
        }
    }
    private static PhotoObjectStore unavailableStore(){return new PhotoObjectStore(){public boolean ready(){return false;}private DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","照片存储私有配置缺失或未获准");}public void storeImmutable(String key,byte[] bytes,String media,String hash){throw unavailable();}public byte[] read(String key,long size,String hash){throw unavailable();}};}
}
