package cn.mop.adapters;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.ImportFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MinIO test, using separately tagged project buckets and restricted accounts. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_CONFIGURED_MINIO_TESTS",matches="true")
class ConfiguredMinioRemoteTest {
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void configuredPhotoEndpointAndBucketRetainExactBytesAndRejectReplacement()throws Exception{
        var settings=MinioStorageSettings.from(System::getenv,MinioStorageSettings.Scope.PHOTOS);
        assertNotEquals("mop-test-photos",settings.bucket(),"Explicit alternate test bucket required");
        var store=new MinioPhotoStore(settings);assertTrue(store.ready());
        String key="photos/00000000-0000-4000-8000-000000000099/"+UUID.randomUUID();
        byte[] data="MOP explicit synthetic configurable storage photo boundary".getBytes(StandardCharsets.UTF_8),other=data.clone();other[0]='X';
        store.storeImmutable(key,data,"image/png",hash(data));assertArrayEquals(data,store.read(key,data.length,hash(data)));
        store.storeImmutable(key,data,"image/png",hash(data));
        assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(key,other,"image/png",hash(other))).code);
        assertArrayEquals(data,store.read(key,data.length,hash(data)));
    }
    @Test void configuredImportEndpointAndBucketRetainOriginalAndRejectReplacement()throws Exception{
        var settings=MinioStorageSettings.from(System::getenv,MinioStorageSettings.Scope.IMPORTS);
        assertNotEquals("mop-test-asset-imports",settings.bucket(),"Explicit alternate test bucket required");
        var store=new MinioAssetImportStore(settings);assertTrue(store.ready());
        String key="asset-import-sources/00000000-0000-4000-8000-000000000099/"+UUID.randomUUID();
        byte[] data="name,description\nMOP-FAKE,configured bucket verification\n".getBytes(StandardCharsets.UTF_8),other=data.clone();other[0]='X';
        store.storeImmutable(key,data,ImportFormat.CSV,hash(data));assertArrayEquals(data,store.read(key,data.length,hash(data)));
        store.storeImmutable(key,data,ImportFormat.CSV,hash(data));
        assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(key,other,ImportFormat.CSV,hash(other))).code);
        assertArrayEquals(data,store.read(key,data.length,hash(data)));
    }
}
