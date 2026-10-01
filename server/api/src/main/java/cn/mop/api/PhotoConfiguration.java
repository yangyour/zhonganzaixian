package cn.mop.api;
import cn.mop.adapters.MinioPhotoStore;
import cn.mop.domain.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class PhotoConfiguration {
    @Bean PhotoPolicy photoPolicy(Environment environment){return new PhotoPolicy(environment.getProperty("MOP_PHOTO_ALLOWED_MEDIA_TYPES"));}
    @Bean PhotoObjectStore photoObjectStore(Environment environment){
        String endpoint=environment.getProperty("MOP_S3_ENDPOINT"),bucket=environment.getProperty("MOP_S3_BUCKET"),access=environment.getProperty("MOP_S3_ACCESS_KEY"),secret=environment.getProperty("MOP_S3_SECRET_KEY");
        if(endpoint==null||bucket==null||access==null||secret==null)return new PhotoObjectStore(){public boolean ready(){return false;}private DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","照片存储私有配置缺失");}public void storeImmutable(String key,byte[] bytes,String media,String hash){throw unavailable();}public byte[] read(String key,long size,String hash){throw unavailable();}};
        return new MinioPhotoStore(endpoint,bucket,access,secret);
    }
}
