package cn.mop.adapters.oss;

import cn.mop.domain.DomainException;
import cn.mop.domain.PhotoObjectStore;
import cn.mop.domain.assetimport.source.AssetImportObjectStore;
import cn.mop.domain.assetimport.source.ImportFormat;
import cn.mop.domain.assetimport.source.ImportLimits;
import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native OSS V4 transport. No bucket mutation, delete, ACL expansion or public/presigned download API. */
public final class OssProjectStore implements AutoCloseable {
    private static final System.Logger LOG=System.getLogger(OssProjectStore.class.getName());
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final AtomicBoolean closed=new AtomicBoolean();
    private final OSS client;
    private final OssStorageSettings settings;

    public OssProjectStore(OssStorageSettings settings) { this(build(settings),settings); }
    OssProjectStore(OSS client,OssStorageSettings settings) {
        this.client=Objects.requireNonNull(client);this.settings=Objects.requireNonNull(settings);
    }
    static ClientBuilderConfiguration configuration() {
        var config=new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);config.setVerifySSLEnable(true);config.setRedirectEnable(false);
        config.setSupportCname(false);config.setConnectionTimeout(5000);config.setSocketTimeout(15000);
        config.setConnectionRequestTimeout(5000);config.setRequestTimeoutEnabled(true);config.setRequestTimeout(60000);
        config.setMaxConnections(8);config.setMaxErrorRetry(0);config.setCrcCheckEnabled(true);config.setTracerEnabled(false);
        return config;
    }
    private static OSS build(OssStorageSettings s) {
        Objects.requireNonNull(s);
        var credentials=s.securityToken()==null
                ? CredentialsProviderFactory.newDefaultCredentialProvider(s.accessKey(),s.secretKey())
                : CredentialsProviderFactory.newDefaultCredentialProvider(s.accessKey(),s.secretKey(),s.securityToken());
        return OSSClientBuilder.create().endpoint(s.endpoint()).region(s.region()).credentialsProvider(credentials)
                .clientConfiguration(configuration()).build();
    }
    /** Read-only capability prerequisites; real anonymous-access/IAM tests are separate evidence. */
    public boolean ready() { try { requireBucketSafety();return true; } catch(Exception e) { safeLog(e);return false; } }
    private void requireBucketSafety() {
        if(closed.get())throw unavailable();
        BucketVersioningConfiguration versioning=client.getBucketVersioning(settings.bucket());
        if(versioning==null)throw unavailable();
        // The official service returns absent Status for a bucket never versioned; SDK parses it as null.
        String status=versioning.getStatus();
        if(status!=null&&!BucketVersioningConfiguration.OFF.equals(status))throw unavailable();
        AccessControlList acl=client.getBucketAcl(settings.bucket());
        if(acl==null||acl.getCannedACL()!=CannedAccessControlList.Private)throw unavailable();
        // Enabled AND Suspended versioning ignore x-oss-forbid-overwrite. Never disable bucket settings here.
    }
    public PhotoObjectStore photos() { return new PhotoView(); }
    public final class PhotoView implements PhotoObjectStore,AutoCloseable {
            public boolean ready(){return OssProjectStore.this.ready();}
            public void storeImmutable(String key,byte[] bytes,String media,String hash) {
                if(media==null||!Set.of("image/jpeg","image/png").contains(media))throw new IllegalArgumentException("Invalid accepted photo media");
                write("photos",key,bytes,media,hash,5_000_000,Map.of());
            }
            public byte[] read(String key,long size,String hash){return readVerified("photos",key,size,hash,5_000_000);}
            public void close(){OssProjectStore.this.close();}
    }
    public AssetImportObjectStore imports() { return new ImportView(); }
    public final class ImportView implements AssetImportObjectStore,AutoCloseable {
            public boolean ready(){return OssProjectStore.this.ready();}
            public void storeImmutable(String key,byte[] bytes,ImportFormat format,String hash) {
                Objects.requireNonNull(format,"declared import format");
                write("asset-import-sources",key,bytes,"application/octet-stream",hash,ImportLimits.MAX_SOURCE_BYTES,Map.of("declared-format",format.name()));
            }
            public byte[] read(String key,long size,String hash){return readVerified("asset-import-sources",key,size,hash,ImportLimits.MAX_SOURCE_BYTES);}
            public void close(){OssProjectStore.this.close();}
    }
    private String objectKey(String namespace,String key) {
        if(key==null||!key.matches(namespace+"/"+UUID+"/"+UUID))throw new IllegalArgumentException("Invalid project object key");
        return settings.prefix()+key;
    }
    private static void expected(long size,String hash,long limit) {
        if(size<1||size>limit||hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid accepted object metadata");
    }
    private void write(String namespace,String key,byte[] content,String media,String hash,long limit,Map<String,String> extra) {
        String physical=objectKey(namespace,key);Objects.requireNonNull(content,"accepted bytes");expected(content.length,hash,limit);
        byte[] original=content.clone();if(!sha(original).equals(hash))throw integrity();
        try {
            requireBucketSafety();
            try { client.getObjectMetadata(settings.bucket(),physical);readVerified(namespace,key,original.length,hash,limit);return; }
            catch(OSSException absent) { if(!"NoSuchKey".equals(absent.getErrorCode()))throw absent; }
            ObjectMetadata metadata=new ObjectMetadata();metadata.setContentLength(original.length);metadata.setContentType(media);
            metadata.setContentMD5(Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(original)));
            metadata.setObjectAcl(CannedAccessControlList.Private);metadata.setHeader("x-oss-forbid-overwrite","true");
            var userMetadata=new HashMap<>(extra);userMetadata.put("sha256",hash);metadata.setUserMetadata(userMetadata);
            // All accepted payloads (<=20MiB) use one simple conditional PUT, never multipart completion.
            try { client.putObject(new PutObjectRequest(settings.bucket(),physical,new ByteArrayInputStream(original),metadata)); }
            catch(OSSException conflict) { if(!"FileAlreadyExists".equals(conflict.getErrorCode()))throw conflict; }
            readVerified(namespace,key,original.length,hash,limit);
        } catch(DomainException safe) { throw safe; }
        catch(Exception failure) { safeLog(failure);throw unavailable(); }
    }
    private byte[] readVerified(String namespace,String key,long size,String hash,long limit) {
        String physical=objectKey(namespace,key);expected(size,hash,limit);
        if(closed.get())throw unavailable();
        try {
            ObjectMetadata metadata=client.getObjectMetadata(settings.bucket(),physical);
            if(metadata==null||metadata.getContentLength()!=size)throw integrity();
            try(OSSObject object=client.getObject(settings.bucket(),physical)) {
                byte[] bytes=object.getObjectContent().readNBytes((int)size+1);
                if(bytes.length!=size||!sha(bytes).equals(hash))throw integrity();
                return bytes;
            }
        } catch(DomainException safe) { throw safe; }
        catch(Exception failure) { safeLog(failure);throw unavailable(); }
    }
    private static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception impossible){throw new IllegalStateException("SHA256 unavailable");}
    }
    private static void safeLog(Exception e){LOG.log(System.Logger.Level.WARNING,"OSS storage failure exception_type={0}",e.getClass().getName());}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","OSS存储或不可覆盖前提未核验，未取得存储成功证据");}
    private static DomainException integrity(){return new DomainException(409,"FILE_INTEGRITY_CONFLICT","对象大小或摘要不符，禁止覆盖");}
    @Override public void close(){if(closed.compareAndSet(false,true))client.shutdown();}
}
