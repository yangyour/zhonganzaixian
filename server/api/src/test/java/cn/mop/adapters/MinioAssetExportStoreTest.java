package cn.mop.adapters;
import cn.mop.domain.DomainException;
import io.minio.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinioAssetExportStoreTest {
    @Test void exportRequiresIndependentCredentialsEvenWhenGenericAndPhotoAreAvailable(){
        Map<String,String> env=new HashMap<>(Map.of("OBJECT_STORAGE_ENABLED","true","OBJECT_STORAGE_PROVIDER","minio","OBJECT_STORAGE_ENDPOINT","http://127.0.0.1:21900","OBJECT_STORAGE_BUCKET","generic-bucket","OBJECT_STORAGE_ACCESS_KEY","synthetic-access","OBJECT_STORAGE_SECRET_KEY","synthetic-secret"));
        assertThrows(IllegalArgumentException.class,()->MinioStorageSettings.from(env::get,MinioStorageSettings.Scope.EXPORTS));
        env.put("OBJECT_STORAGE_EXPORT_BUCKET","export-bucket");env.put("OBJECT_STORAGE_EXPORT_ACCESS_KEY","export-access");env.put("OBJECT_STORAGE_EXPORT_SECRET_KEY","export-secret");var settings=MinioStorageSettings.from(env::get,MinioStorageSettings.Scope.EXPORTS);assertEquals("export-bucket",settings.bucket());assertEquals("export-access",settings.accessKey());assertFalse(settings.toString().contains("export-secret"));
        env.put("OBJECT_STORAGE_EXPORT_SECRET_KEY","");assertThrows(IllegalArgumentException.class,()->MinioStorageSettings.from(env::get,MinioStorageSettings.Scope.EXPORTS));
    }
    @Test void rejectsCrossCapabilityPrefixAndInvalidDescriptorsBeforeNetwork(){var client=mock(MinioClient.class);var store=new MinioAssetExportStore(client,"export-bucket");for(String key:List.of("photos/a/b","asset-exports/../x","asset-exports/"+UUID.randomUUID()+"/x"))assertThrows(IllegalArgumentException.class,()->store.read(key,1,"a".repeat(64)));String key="asset-exports/"+UUID.randomUUID()+"/"+UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->store.read(key,0,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->store.read(key,16*1024*1024+1L,"a".repeat(64)));assertEquals(409,assertThrows(DomainException.class,()->store.storeImmutable(key,new byte[]{1},"a".repeat(64))).status);verifyNoInteractions(client);}
    @Test void wrongScopeCannotBeUsed(){var settings=new MinioStorageSettings("http://127.0.0.1:21900","photo-bucket","synthetic-access","synthetic-secret",null,MinioStorageSettings.Scope.PHOTOS);assertThrows(IllegalArgumentException.class,()->new MinioAssetExportStore(settings));}
    @Test void unavailableStoreDoesNotReportReady()throws Exception{var client=mock(MinioClient.class);when(client.bucketExists(any())).thenThrow(new RuntimeException("synthetic secret must not escape"));assertFalse(new MinioAssetExportStore(client,"export-bucket").ready());}
}
