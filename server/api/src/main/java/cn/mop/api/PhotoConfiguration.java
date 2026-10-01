package cn.mop.api;
import cn.mop.adapters.MinioPhotoStore;
import cn.mop.domain.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class PhotoConfiguration {
    private static final System.Logger log=System.getLogger(PhotoConfiguration.class.getName());
    @Bean PhotoPolicy photoPolicy(Environment environment){try{return new PhotoPolicy(environment.getProperty("MOP_PHOTO_ALLOWED_MEDIA_TYPES"));}catch(IllegalStateException invalid){log.log(System.Logger.Level.WARNING,"Photo allowlist unavailable exception_type={0}",invalid.getClass().getName());return new PhotoPolicy(null);}}
    @Bean PhotoObjectStore photoObjectStore(Environment environment){
        String endpoint=environment.getProperty("MOP_S3_ENDPOINT"),bucket=environment.getProperty("MOP_S3_BUCKET"),access=environment.getProperty("MOP_S3_ACCESS_KEY"),secret=environment.getProperty("MOP_S3_SECRET_KEY");
        if(java.util.stream.Stream.of(endpoint,bucket,access,secret).anyMatch(value->value==null||value.isBlank()))return unavailableStore();
        try{return new MinioPhotoStore(endpoint,bucket,access,secret);}catch(IllegalStateException invalid){log.log(System.Logger.Level.WARNING,"Photo storage configuration unavailable exception_type={0}",invalid.getClass().getName());return unavailableStore();}
    }
    private static PhotoObjectStore unavailableStore(){return new PhotoObjectStore(){public boolean ready(){return false;}private DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","照片存储私有配置缺失或未获准");}public void storeImmutable(String key,byte[] bytes,String media,String hash){throw unavailable();}public byte[] read(String key,long size,String hash){throw unavailable();}};}
}
