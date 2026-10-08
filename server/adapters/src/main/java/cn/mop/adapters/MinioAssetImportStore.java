package cn.mop.adapters;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Independent import namespace and credential injection; no photo client, delete or presigned capability. */
public final class MinioAssetImportStore implements AssetImportObjectStore {
    public static final String BUCKET="mop-test-asset-imports";
    private static final System.Logger LOG=System.getLogger(MinioAssetImportStore.class.getName());
    private final MinioClient client;
    private final String bucket;
    public MinioAssetImportStore(String endpoint,String bucket,String access,String secret){
        if(!"http://127.0.0.1:19000".equals(endpoint)||!BUCKET.equals(bucket)||access==null||access.isBlank()||secret==null||secret.isBlank())throw new IllegalStateException("FAIL_CLOSED: private project import storage configuration required");
        client=MinioClient.builder().endpoint(endpoint).credentials(access,secret).httpClient(new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()).build();
        this.bucket=BUCKET;
    }
    public MinioAssetImportStore(MinioStorageSettings settings){
        if(settings==null||settings.scope()!=MinioStorageSettings.Scope.IMPORTS)throw new IllegalArgumentException("Invalid import storage scope");
        var builder=MinioClient.builder().endpoint(settings.endpoint()).credentials(settings.accessKey(),settings.secretKey())
                .httpClient(new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build());
        if(settings.region()!=null)builder.region(settings.region());
        this.client=builder.build();this.bucket=settings.bucket();
    }
    MinioAssetImportStore(MinioClient client){this.client=Objects.requireNonNull(client);this.bucket=BUCKET;}
    @Override public boolean ready(){try{return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());}catch(Exception failure){safeLog(failure);return false;}}
    private static void key(String key){String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";if(key==null||!key.matches("asset-import-sources/"+uuid+"/"+uuid))throw new IllegalArgumentException("Invalid project import object key");}
    private static void digest(String hash){if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid import digest");}
    private static String digest(byte[] bytes)throws java.security.NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Override public void storeImmutable(String key,byte[] original,ImportFormat declaredFormat,String hash){
        key(key);digest(hash);if(original==null||original.length<1||original.length>ImportLimits.MAX_SOURCE_BYTES||declaredFormat==null)throw new IllegalArgumentException("Invalid declared import source");
        byte[] bytes=original.clone();
        try{
            if(!digest(bytes).equals(hash))throw integrity();
            try{client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());read(key,bytes.length,hash);return;}
            catch(ErrorResponseException absent){if(!Set.of("NoSuchKey","NoSuchObject","NotFound").contains(absent.errorResponse().code()))throw absent;}
            // Known <=20MiB length and partSize >= length force one conditional PUT, including >5MiB inputs.
            long partSize=Math.max(5_242_880L,(long)bytes.length);
            try{client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(bytes),(long)bytes.length,partSize)
                .contentType("application/octet-stream").userMetadata(Map.of("sha256",hash,"declared-format",declaredFormat.name()))
                .extraHeaders(Map.of("If-None-Match","*","Expect","100-continue")).build());}
            catch(ErrorResponseException race){if(!"PreconditionFailed".equals(race.errorResponse().code()))throw race;}
            read(key,bytes.length,hash);
        }catch(DomainException|IllegalArgumentException safe){throw safe;}catch(Exception failure){safeLog(failure);throw unavailable();}
    }
    @Override public byte[] read(String key,long size,String hash){
        key(key);digest(hash);if(size<1||size>ImportLimits.MAX_SOURCE_BYTES)throw new IllegalArgumentException("Invalid import source size");
        try{
            StatObjectResponse stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());if(stat.size()!=size)throw integrity();
            try(var content=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){
                byte[] bytes=content.readNBytes((int)size+1);if(bytes.length!=size||!digest(bytes).equals(hash))throw integrity();return bytes;
            }
        }catch(DomainException safe){throw safe;}catch(Exception failure){safeLog(failure);throw unavailable();}
    }
    private static void safeLog(Exception failure){LOG.log(System.Logger.Level.WARNING,"Asset import storage failure exception_type={0}",failure.getClass().getName());}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","指定导入源存储不可用，未取得原始源存储成功证据");}
    private static DomainException integrity(){return new DomainException(409,"FILE_INTEGRITY_CONFLICT","原始导入源大小或摘要不符，禁止覆盖");}
}
