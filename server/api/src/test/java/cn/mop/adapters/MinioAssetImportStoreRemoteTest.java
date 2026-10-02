package cn.mop.adapters;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.ImportFormat;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in designated import MinIO tests. Only MOP-FAKE project objects are created and retained.
 * No database, IAM provisioning, application HTTP, malware scan or business precheck is claimed. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_IMPORT_REMOTE_TESTS",matches="true")
class MinioAssetImportStoreRemoteTest {
    private static String setting(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing designated import storage setting: "+name);return value;}
    private static MinioAssetImportStore store(){return new MinioAssetImportStore(setting("MOP_IMPORT_S3_ENDPOINT"),setting("MOP_IMPORT_S3_BUCKET"),setting("MOP_IMPORT_S3_ACCESS_KEY"),setting("MOP_IMPORT_S3_SECRET_KEY"));}
    private static String key(){return "asset-import-sources/00000000-0000-4000-8000-000000000099/"+UUID.randomUUID();}
    private static byte[] source(){return "设备名称,备注\nMOP-FAKE-router,MOP-FAKE import object boundary verification\n".getBytes(StandardCharsets.UTF_8);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void designatedStorageReturnsTheExactOriginalAndIdempotentRepeatDoesNotReplace()throws Exception{
        var store=store();assertTrue(store.ready(),"Designated project import storage must be available");String key=key();byte[] bytes=source();String hash=sha(bytes);store.storeImmutable(key,bytes,ImportFormat.CSV,hash);assertArrayEquals(bytes,store.read(key,bytes.length,hash));store.storeImmutable(key,bytes,ImportFormat.CSV,hash);assertArrayEquals(bytes,store.read(key,bytes.length,hash));
    }
    @Test void differentSizeOrBytesFailIntegrityAndLeaveOriginalUnchanged()throws Exception{
        var store=store();assertTrue(store.ready(),"Designated project import storage must be available");String key=key();byte[] bytes=source(),other=bytes.clone();other[other.length-2]='X';String hash=sha(bytes);store.storeImmutable(key,bytes,ImportFormat.CSV,hash);assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(key,other,ImportFormat.CSV,sha(other))).code);assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.read(key,bytes.length+1,hash)).code);assertArrayEquals(bytes,store.read(key,bytes.length,hash));
    }
    @Test void sixMiBSinglePutIsActuallyConditionalOnTheDesignatedServer()throws Exception{
        var store=store();assertTrue(store.ready(),"Designated project import storage must be available");String key=key();byte[] bytes=new byte[6*1024*1024];byte[] marker="MOP-FAKE import source conditional original\n".getBytes(StandardCharsets.UTF_8);for(int offset=0;offset<bytes.length;offset+=marker.length)System.arraycopy(marker,0,bytes,offset,Math.min(marker.length,bytes.length-offset));String hash=sha(bytes);store.storeImmutable(key,bytes,ImportFormat.CSV,hash);
        byte[] other=bytes.clone();other[other.length-2]='X';MinioClient client=MinioClient.builder().endpoint(setting("MOP_IMPORT_S3_ENDPOINT")).credentials(setting("MOP_IMPORT_S3_ACCESS_KEY"),setting("MOP_IMPORT_S3_SECRET_KEY")).build();boolean denied=false;
        try{client.putObject(PutObjectArgs.builder().bucket(setting("MOP_IMPORT_S3_BUCKET")).object(key).stream(new ByteArrayInputStream(other),(long)other.length,(long)other.length).contentType("application/octet-stream").extraHeaders(Map.of("If-None-Match","*","Expect","100-continue")).build());}
        catch(ErrorResponseException error){denied="PreconditionFailed".equals(error.errorResponse().code())&&error.response().code()==412;}
        catch(Exception failure){throw new IllegalStateException("Designated conditional write verification failed exception_type="+failure.getClass().getName());}
        assertTrue(denied,"Designated server must reject replacement via If-None-Match");assertArrayEquals(bytes,store.read(key,bytes.length,hash));
    }
}
