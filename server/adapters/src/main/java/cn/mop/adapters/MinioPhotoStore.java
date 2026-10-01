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
    private static final System.Logger log=System.getLogger(MinioPhotoStore.class.getName());
    private static final int MAX_SIZE=5000000;
    private final MinioClient client;
    private final String bucket;
    public MinioPhotoStore(String endpoint,String bucket,String access,String secret){
        this(projectClient(endpoint,bucket,access,secret),bucket);
    }
    MinioPhotoStore(MinioClient client,String bucket){if(client==null||!"mop-test-photos".equals(bucket))throw new IllegalArgumentException("Invalid project photo client");this.bucket=bucket;this.client=client;}
    private static MinioClient projectClient(String endpoint,String bucket,String access,String secret){
        if(!"http://127.0.0.1:19000".equals(endpoint)||!"mop-test-photos".equals(bucket)||access==null||access.isBlank()||secret==null||secret.isBlank())throw new IllegalStateException("FAIL_CLOSED: project photo storage requires private loopback MinIO settings");
        return MinioClient.builder().endpoint(endpoint).credentials(access,secret).httpClient(new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()).build();
    }
    @Override public boolean ready(){try{return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());}catch(Exception error){safeLog(error);return false;}}
    private static void key(String key){String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";if(key==null||!key.matches("photos/"+uuid+"/"+uuid))throw new IllegalArgumentException("Invalid project photo object key");}
    private static void digest(String sha){if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid photo digest");}
    private static String digest(byte[] bytes)throws java.security.NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Override public void storeImmutable(String key,byte[] content,String media,String sha){
        key(key);digest(sha);
        if(content==null||content.length<1||content.length>MAX_SIZE||media==null||!Set.of("image/jpeg","image/png").contains(media))throw new IllegalArgumentException("Invalid accepted photo metadata");
        byte[] original=content.clone();
        try{
            if(!digest(original).equals(sha))throw new IllegalArgumentException("Photo bytes do not match accepted digest");
            try{client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());verify(key,original.length,sha);return;}
            catch(ErrorResponseException absent){if(!Set.of("NoSuchKey","NoSuchObject","NotFound").contains(absent.errorResponse().code()))throw absent;}
            // The 5,000,000-byte cap is below S3's 5 MiB multipart threshold: this is one
            // conditional PUT. Existing objects are read and verified, never overwritten.
            try{client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(original),original.length,-1).contentType(media).userMetadata(Map.of("sha256",sha)).extraHeaders(Map.of("If-None-Match","*")).build());}
            catch(ErrorResponseException race){if(!"PreconditionFailed".equals(race.errorResponse().code()))throw race;}
            verify(key,original.length,sha);
        }catch(DomainException|IllegalArgumentException safe){throw safe;}catch(Exception error){safeLog(error);throw unavailable();}
    }
    private void verify(String key,long size,String sha){read(key,size,sha);}
    @Override public byte[] read(String key,long expectedSize,String sha){
        key(key);digest(sha);if(expectedSize<1||expectedSize>MAX_SIZE)throw new IllegalArgumentException("Invalid photo size");
        try{
            StatObjectResponse stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            if(stat.size()!=expectedSize)throw integrity();
            try(var content=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){
                byte[] bytes=content.readNBytes((int)expectedSize+1);if(bytes.length!=expectedSize||!digest(bytes).equals(sha))throw integrity();return bytes;
            }
        }catch(DomainException safe){throw safe;}catch(Exception error){safeLog(error);throw unavailable();}
    }
    private static void safeLog(Exception error){log.log(System.Logger.Level.WARNING,"Photo storage failure exception_type={0}",error.getClass().getName());}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境照片存储不可用，未取得存储成功证据");}
    private static DomainException integrity(){return new DomainException(409,"FILE_INTEGRITY_CONFLICT","已有照片对象与受理摘要不符，禁止覆盖");}
}
