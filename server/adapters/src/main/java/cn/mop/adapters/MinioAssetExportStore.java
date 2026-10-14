package cn.mop.adapters;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.AssetExportObjectStore;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate export credentials/prefix. Conditional single PUT and verified readback, never overwrite. */
public final class MinioAssetExportStore implements AssetExportObjectStore {
    public static final int MAX_BYTES=16*1024*1024;
    private final MinioClient client;
    private final String bucket;
    public MinioAssetExportStore(MinioStorageSettings settings) {
        if(settings==null||settings.scope()!=MinioStorageSettings.Scope.EXPORTS)throw new IllegalArgumentException("Export storage scope required");
        var builder=MinioClient.builder().endpoint(settings.endpoint()).credentials(settings.accessKey(),settings.secretKey())
            .httpClient(new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
                .writeTimeout(15,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build());
        if(settings.region()!=null)builder.region(settings.region());
        client=builder.build();bucket=settings.bucket();
    }
    MinioAssetExportStore(MinioClient client,String bucket){this.client=Objects.requireNonNull(client);this.bucket=Objects.requireNonNull(bucket);}
    @Override public boolean ready(){try{return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());}catch(Exception failure){return false;}}
    private static void validate(String key,long size,String hash){
        String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if(key==null||!key.matches("asset-exports/"+uuid+"/"+uuid)||size<1||size>MAX_BYTES||hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid export object descriptor");
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Override public void storeImmutable(String key,byte[] input,String sha256){
        if(input==null)throw new IllegalArgumentException("Export bytes required");validate(key,input.length,sha256);byte[] bytes=input.clone();
        try{
            if(!hash(bytes).equals(sha256))throw conflict();
            try{client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());read(key,bytes.length,sha256);return;}
            catch(ErrorResponseException absent){if(!Set.of("NoSuchKey","NoSuchObject").contains(absent.errorResponse().code()))throw absent;}
            // A known length and part size >= length prevent multipart uploads from dropping the precondition.
            try{client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(bytes),(long)bytes.length,Math.max(5_242_880L,bytes.length))
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .userMetadata(Map.of("sha256",sha256)).extraHeaders(Map.of("If-None-Match","*","Expect","100-continue")).build());}
            catch(ErrorResponseException race){if(!"PreconditionFailed".equals(race.errorResponse().code()))throw race;}
            read(key,bytes.length,sha256);
        }catch(DomainException|IllegalArgumentException safe){throw safe;}catch(Exception failure){throw unavailable();}
    }
    @Override public byte[] read(String key,long size,String sha256){
        validate(key,size,sha256);
        try{
            var stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());if(stat.size()!=size)throw conflict();
            try(var content=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){
                byte[] bytes=content.readNBytes((int)size+1);if(bytes.length!=size||!hash(bytes).equals(sha256))throw conflict();return bytes;
            }
        }catch(DomainException safe){throw safe;}catch(Exception failure){throw unavailable();}
    }
    private static DomainException conflict(){return new DomainException(409,"EXPORT_FILE_INTEGRITY_CONFLICT","导出文件大小或摘要不符，禁止覆盖原对象");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","导出存储结果未确认；保留原请求，重新核验同一文件");}
}
