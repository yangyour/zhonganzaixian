package cn.mop.adapters.oss;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.ImportFormat;
import cn.mop.domain.assetimport.source.ImportLimits;
import com.aliyun.oss.*;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SDK interaction/bytes-only tests. They do not contact OSS or prove cloud IAM/versioning. */
class OssProjectStoreTest {
    private static final String BUCKET="mop-synthetic-bucket";
    private static final String PREFIX="mop-niko-test/00000000-0000-4000-8000-000000000001/";
    private static final String KEY="photos/00000000-0000-4000-8000-000000000002/00000000-0000-4000-8000-000000000003";
    private static final byte[] BYTES="synthetic-photo-only".getBytes(StandardCharsets.UTF_8);
    private static OssStorageSettings settings(){return new OssStorageSettings("https://oss-cn-beijing.aliyuncs.com","https://oss-cn-beijing.aliyuncs.com","cn-beijing",BUCKET,"synthetic-id","synthetic-secret",null,PREFIX,3600);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static OSSException error(String code){return new OSSException("SYNTHETIC_PROVIDER_PRIVATE_DETAIL",code,"r","h",null,null,null);}
    private static ObjectMetadata metadata(long size){var m=new ObjectMetadata();m.setContentLength(size);return m;}
    private static OSS safeClient(){
        OSS client=mock(OSS.class);when(client.getBucketVersioning(BUCKET)).thenReturn(new BucketVersioningConfiguration());
        var acl=new AccessControlList();acl.setCannedACL(CannedAccessControlList.Private);when(client.getBucketAcl(BUCKET)).thenReturn(acl);return client;
    }
    private static void reads(OSS client,String key,byte[] content){
        when(client.getObject(BUCKET,PREFIX+key)).thenAnswer(call->{var object=new OSSObject();object.setObjectContent(new ByteArrayInputStream(content));return object;});
    }
    @Test void exactEnvironmentSettingsAreRequiredAndCredentialsRedacted(){
        var env=new HashMap<String,String>();env.put("OBJECT_STORAGE_ENABLED","True");env.put("OBJECT_STORAGE_PROVIDER","oss");
        env.put("OBJECT_STORAGE_ENDPOINT",settings().endpoint());env.put("OBJECT_STORAGE_PUBLIC_ENDPOINT",settings().publicEndpoint());env.put("OBJECT_STORAGE_REGION","cn-beijing");env.put("OBJECT_STORAGE_BUCKET",BUCKET);env.put("OBJECT_STORAGE_ACCESS_KEY","synthetic-id");env.put("OBJECT_STORAGE_SECRET_KEY","synthetic-secret");env.put("MOP_OBJECT_STORAGE_PREFIX",PREFIX);env.put("OBJECT_STORAGE_PRESIGN_EXPIRES","3600");
        var parsed=OssStorageSettings.from(env::get);assertEquals(3600,parsed.presignExpiresSeconds());assertFalse(parsed.toString().contains("synthetic-secret"));assertFalse(parsed.toString().contains("synthetic-id"));
        for(String name:List.of("OBJECT_STORAGE_ACCESS_KEY","OBJECT_STORAGE_SECRET_KEY","OBJECT_STORAGE_SECURITY_TOKEN")){
            var invalid=new HashMap<>(env);invalid.put(name,"synthetic\nvalue");assertThrows(IllegalArgumentException.class,()->OssStorageSettings.from(invalid::get));
        }
        for(String key:List.copyOf(env.keySet())){var missing=new HashMap<>(env);missing.remove(key);assertThrows(IllegalArgumentException.class,()->OssStorageSettings.from(missing::get),key);}
        for(var change:Map.of("OBJECT_STORAGE_ENABLED","false","OBJECT_STORAGE_PROVIDER","other","OBJECT_STORAGE_ENDPOINT","http://oss-cn-beijing.aliyuncs.com","OBJECT_STORAGE_PUBLIC_ENDPOINT","https://untrusted.invalid","OBJECT_STORAGE_REGION","cn-other","OBJECT_STORAGE_BUCKET","BAD","MOP_OBJECT_STORAGE_PREFIX","../","OBJECT_STORAGE_PRESIGN_EXPIRES","3601").entrySet()){
            var invalid=new HashMap<>(env);invalid.put(change.getKey(),change.getValue());assertThrows(IllegalArgumentException.class,()->OssStorageSettings.from(invalid::get));
        }
    }
    @Test void transportKeepsTlsV4NoRedirectAndBoundedRequests(){
        var config=OssProjectStore.configuration();assertEquals(SignVersion.V4,config.getSignatureVersion());assertTrue(config.isVerifySSLEnable());assertFalse(config.isRedirectEnable());assertTrue(config.isCrcCheckEnabled());assertEquals(0,config.getMaxErrorRetry());assertEquals(60000,config.getRequestTimeout());assertTrue(config.isRequestTimeoutEnabled());
    }
    @Test void neverVersionedPrivateBucketCanBeReadOnlyReady(){
        OSS client=safeClient();var store=new OssProjectStore(client,settings());assertTrue(store.ready());
        when(client.getBucketVersioning(BUCKET)).thenReturn(new BucketVersioningConfiguration(null));assertTrue(store.ready());
        verify(client,never()).putObject(any(PutObjectRequest.class));
    }
    @Test void enabledSuspendedUnknownOrMissingVersioningNeverAllowsWrite()throws Exception{
        for(String status:List.of("Enabled","Suspended","unknown","")){
            OSS client=safeClient();when(client.getBucketVersioning(BUCKET)).thenReturn(new BucketVersioningConfiguration(status));var store=new OssProjectStore(client,settings());
            assertFalse(store.ready());assertEquals(503,assertThrows(DomainException.class,()->store.photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES))).status);verify(client,never()).putObject(any(PutObjectRequest.class));
        }
        OSS client=safeClient();when(client.getBucketVersioning(BUCKET)).thenReturn(null);assertFalse(new OssProjectStore(client,settings()).ready());
    }
    @Test void publicOrUnknownBucketAclIsNotReady(){
        for(CannedAccessControlList aclValue:List.of(CannedAccessControlList.PublicRead,CannedAccessControlList.PublicReadWrite)){
            OSS client=safeClient();var acl=new AccessControlList();acl.setCannedACL(aclValue);when(client.getBucketAcl(BUCKET)).thenReturn(acl);assertFalse(new OssProjectStore(client,settings()).ready());
        }
        OSS client=safeClient();when(client.getBucketAcl(BUCKET)).thenReturn(null);assertFalse(new OssProjectStore(client,settings()).ready());
    }
    @Test void metadataProbeFailureIsUnknownNotHealthy(){
        OSS client=safeClient();when(client.getBucketVersioning(BUCKET)).thenThrow(error("AccessDenied"));assertFalse(new OssProjectStore(client,settings()).ready());
    }
    @Test void onePhotoPutIsPrivateConditionalPrefixedAndReadBackVerified()throws Exception{
        OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenThrow(error("NoSuchKey")).thenReturn(metadata(BYTES.length));reads(client,KEY,BYTES);
        new OssProjectStore(client,settings()).photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES));
        var put=ArgumentCaptor.forClass(PutObjectRequest.class);verify(client,times(1)).putObject(put.capture());var request=put.getValue();assertEquals(BUCKET,request.getBucketName());assertEquals(PREFIX+KEY,request.getKey());assertArrayEquals(BYTES,request.getInputStream().readAllBytes());
        var m=request.getMetadata();assertEquals("true",m.getRawMetadata().get("x-oss-forbid-overwrite"));assertEquals("private",m.getRawMetadata().get("x-oss-object-acl"));assertEquals(BYTES.length,m.getContentLength());assertEquals("image/png",m.getContentType());assertEquals(sha(BYTES),m.getUserMetadata().get("sha256"));assertNotNull(m.getContentMD5());verify(client).getObject(BUCKET,PREFIX+KEY);
    }
    @Test void acceptedInputBytesAreDetachedFromCallerMutation()throws Exception{
        OSS client=safeClient();byte[] caller=BYTES.clone();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenThrow(error("NoSuchKey")).thenReturn(metadata(BYTES.length));reads(client,KEY,BYTES);
        when(client.putObject(any(PutObjectRequest.class))).thenAnswer(call->{caller[0]^=1;assertArrayEquals(BYTES,((PutObjectRequest)call.getArgument(0)).getInputStream().readAllBytes());return new PutObjectResult();});
        new OssProjectStore(client,settings()).photos().storeImmutable(KEY,caller,"image/jpeg",sha(BYTES));
    }
    @Test void existingMatchingObjectIsNotUploadedAgain()throws Exception{
        OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenReturn(metadata(BYTES.length));reads(client,KEY,BYTES);
        new OssProjectStore(client,settings()).photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES));verify(client,never()).putObject(any(PutObjectRequest.class));
    }
    @Test void existingDifferentBytesCannotBeOverwritten()throws Exception{
        OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenReturn(metadata(BYTES.length));byte[] other=BYTES.clone();other[0]^=1;reads(client,KEY,other);
        assertEquals(409,assertThrows(DomainException.class,()->new OssProjectStore(client,settings()).photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES))).status);verify(client,never()).putObject(any(PutObjectRequest.class));
    }
    @Test void conditionalRaceRequiresVerificationOfWinningObject()throws Exception{
        OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenThrow(error("NoSuchKey")).thenReturn(metadata(BYTES.length));when(client.putObject(any(PutObjectRequest.class))).thenThrow(error("FileAlreadyExists"));reads(client,KEY,BYTES);
        assertDoesNotThrow(()->new OssProjectStore(client,settings()).photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES)));verify(client).getObject(BUCKET,PREFIX+KEY);
    }
    @Test void accessDeniedIsNeverTreatedAsMissingObject()throws Exception{
        OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenThrow(error("AccessDenied"));var failure=assertThrows(DomainException.class,()->new OssProjectStore(client,settings()).photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES)));
        assertEquals(503,failure.status);assertFalse(failure.getMessage().contains("SYNTHETIC_PROVIDER_PRIVATE_DETAIL"));verify(client,never()).putObject(any(PutObjectRequest.class));
    }
    @Test void uncertainPutIsNotSuccess()throws Exception{
        OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenThrow(error("NoSuchKey"));when(client.putObject(any(PutObjectRequest.class))).thenThrow(new ClientException("SYNTHETIC_TIMEOUT"));
        assertEquals(503,assertThrows(DomainException.class,()->new OssProjectStore(client,settings()).photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES))).status);verify(client,never()).getObject(BUCKET,PREFIX+KEY);
    }
    @Test void changedLengthOrTruncatedOrOversizedReadFailsIntegrity()throws Exception{
        for(byte[] actual:List.of(Arrays.copyOf(BYTES,BYTES.length-1),Arrays.copyOf(BYTES,BYTES.length+1))){
            OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenReturn(metadata(BYTES.length));reads(client,KEY,actual);assertEquals(409,assertThrows(DomainException.class,()->new OssProjectStore(client,settings()).photos().read(KEY,BYTES.length,sha(BYTES))).status);
        }
        OSS client=safeClient();when(client.getObjectMetadata(BUCKET,PREFIX+KEY)).thenReturn(metadata(BYTES.length+1));assertEquals(409,assertThrows(DomainException.class,()->new OssProjectStore(client,settings()).photos().read(KEY,BYTES.length,sha(BYTES))).status);verify(client,never()).getObject(BUCKET,PREFIX+KEY);
    }
    @Test void fullTwentyMiBImportStillUsesOneConditionalPut()throws Exception{
        OSS client=safeClient();String key=KEY.replace("photos/","asset-import-sources/");byte[] content=new byte[(int)ImportLimits.MAX_SOURCE_BYTES];content[content.length-1]=1;
        when(client.getObjectMetadata(BUCKET,PREFIX+key)).thenThrow(error("NoSuchKey")).thenReturn(metadata(content.length));reads(client,key,content);
        new OssProjectStore(client,settings()).imports().storeImmutable(key,content,ImportFormat.XLSX,sha(content));var put=ArgumentCaptor.forClass(PutObjectRequest.class);verify(client,times(1)).putObject(put.capture());assertEquals(content.length,put.getValue().getMetadata().getContentLength());assertEquals("XLSX",put.getValue().getMetadata().getUserMetadata().get("declared-format"));
    }
    @Test void invalidKeysHashesSizesAndMediaFailBeforeAnyCloudCall()throws Exception{
        OSS client=mock(OSS.class);var store=new OssProjectStore(client,settings());
        for(String key:List.of("../"+KEY,KEY.replace("photos/","asset-import-sources/"),KEY+"/extra"))assertThrows(IllegalArgumentException.class,()->store.photos().read(key,1,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->store.photos().read(KEY,5_000_001,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->store.photos().read(KEY,1,"invalid"));
        assertThrows(IllegalArgumentException.class,()->store.photos().storeImmutable(KEY,BYTES,"image/svg+xml",sha(BYTES)));assertEquals(409,assertThrows(DomainException.class,()->store.photos().storeImmutable(KEY,BYTES,"image/png","a".repeat(64))).status);verifyNoInteractions(client);
    }
    @Test void closingStoreReleasesSdkClient(){OSS client=mock(OSS.class);new OssProjectStore(client,settings()).close();verify(client).shutdown();}
    @Test void managedViewsReleaseSharedClientExactlyOnce()throws Exception{
        OSS client=mock(OSS.class);var store=new OssProjectStore(client,settings());
        ((AutoCloseable)store.photos()).close();((AutoCloseable)store.imports()).close();store.close();verify(client,times(1)).shutdown();
    }

    @Test void bucketVersioningIsRecheckedAfterEarlierReady()throws Exception{
        OSS client=safeClient();var store=new OssProjectStore(client,settings());assertTrue(store.ready());
        when(client.getBucketVersioning(BUCKET)).thenReturn(new BucketVersioningConfiguration("Enabled"));
        assertEquals(503,assertThrows(DomainException.class,()->store.photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES))).status);verify(client,never()).putObject(any(PutObjectRequest.class));
    }
    @Test void closedStoresNeverStartNewSdkCalls()throws Exception{
        OSS client=mock(OSS.class);var store=new OssProjectStore(client,settings());store.close();clearInvocations(client);
        assertFalse(store.ready());assertEquals(503,assertThrows(DomainException.class,()->store.photos().read(KEY,BYTES.length,sha(BYTES))).status);
        assertEquals(503,assertThrows(DomainException.class,()->store.photos().storeImmutable(KEY,BYTES,"image/png",sha(BYTES))).status);verifyNoInteractions(client);
    }

}
