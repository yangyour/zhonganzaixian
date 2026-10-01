package cn.mop.adapters;

import cn.mop.domain.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Mature MinIO SDK, fixed project namespace, conditional writes, no credential-bearing error output. */
public final class MinioPhotoStore implements PhotoObjectStore {
    private final MinioClient client;
    private final String bucket;
    public MinioPhotoStore(String endpoint,String bucket,String access,String secret){
        if(!"http://127.0.0.1:19000".equals(endpoint)||!"mop-test-photos".equals(bucket)||access==null||access.isBlank()||secret==null||secret.isBlank())throw new IllegalStateException("FAIL_CLOSED: project photo storage requires private loopback MinIO settings");
        this.bucket=bucket;this.client=MinioClient.builder().endpoint(endpoint).credentials(access,secret).httpClient(new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()).build();
    }
    public boolean ready(){try{return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());}catch(Exception error){return false;}}
    private static void key(String key){if(!key.matches("photos/[0-9a-f-]{36}/[0-9a-f-]{36}"))throw new IllegalArgumentException("Invalid project photo object key");}
    public void storeImmutable(String key,byte[] content,String media,String sha){
        key(key);
        try{
            try{client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());verify(key,content.length,sha);return;}
            catch(ErrorResponseException absent){if(!Set.of("NoSuchKey","NoSuchObject","NotFound").contains(absent.errorResponse().code()))throw absent;}
            try{client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(content),content.length,-1).contentType(media).userMetadata(Map.of("sha256",sha)).extraHeaders(Map.of("If-None-Match","*")).build());}
            catch(ErrorResponseException race){if(!"PreconditionFailed".equals(race.errorResponse().code()))throw race;}
            verify(key,content.length,sha);
        }catch(DomainException safe){throw safe;}catch(Exception error){throw unavailable();}
    }
    private void verify(String key,long size,String sha){read(key,size,sha);}
    public byte[] read(String key,long expectedSize,String sha){
        key(key);if(expectedSize<1||expectedSize>5000000)throw new IllegalArgumentException("Invalid photo size");
        try{
            StatObjectResponse stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            if(stat.size()!=expectedSize)throw integrity();
            try(var content=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){
                byte[] bytes=content.readNBytes(5000001);if(bytes.length!=expectedSize||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha))throw integrity();return bytes;
            }
        }catch(DomainException safe){throw safe;}catch(Exception error){throw unavailable();}
    }
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境照片存储不可用，未取得存储成功证据");}
    private static DomainException integrity(){return new DomainException(409,"FILE_INTEGRITY_CONFLICT","已有照片对象与受理摘要不符，禁止覆盖");}
}
