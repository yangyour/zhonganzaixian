package cn.mop.adapters;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MinioStorageSettingsTest {
    private Map<String,String> configured(){return new HashMap<>(Map.ofEntries(
            Map.entry("OBJECT_STORAGE_ENABLED","true"),Map.entry("OBJECT_STORAGE_PROVIDER","minio"),
            Map.entry("OBJECT_STORAGE_ENDPOINT","http://127.0.0.1:21900"),
            Map.entry("OBJECT_STORAGE_PHOTO_BUCKET","configured-photo-bucket"),Map.entry("OBJECT_STORAGE_IMPORT_BUCKET","configured-import-bucket"),
            Map.entry("OBJECT_STORAGE_PHOTO_ACCESS_KEY","isolated-photo-access"),Map.entry("OBJECT_STORAGE_PHOTO_SECRET_KEY","isolated-photo-secret"),
            Map.entry("OBJECT_STORAGE_IMPORT_ACCESS_KEY","isolated-import-access"),Map.entry("OBJECT_STORAGE_IMPORT_SECRET_KEY","isolated-import-secret")));}
    private MinioStorageSettings parse(Map<String,String> env,MinioStorageSettings.Scope scope){return MinioStorageSettings.from(env::get,scope);}
    @Test void independentlyConfiguresBucketsAndCredentialsWithTheSameEndpoint(){
        var env=configured();var photo=parse(env,MinioStorageSettings.Scope.PHOTOS);var source=parse(env,MinioStorageSettings.Scope.IMPORTS);
        assertEquals("configured-photo-bucket",photo.bucket());assertEquals("configured-import-bucket",source.bucket());
        assertEquals("isolated-photo-access",photo.accessKey());assertEquals("isolated-import-access",source.accessKey());
        assertEquals("http://127.0.0.1:21900",photo.endpoint());assertNull(photo.region());
        assertFalse(photo.toString().contains("isolated"));assertFalse(photo.toString().contains("configured-photo"));
    }
    @Test void oneWorkerDoesNotNeedTheOtherCapabilitiesCredential(){
        var env=configured();env.keySet().removeIf(k->k.startsWith("OBJECT_STORAGE_IMPORT_"));
        assertNotNull(parse(env,MinioStorageSettings.Scope.PHOTOS));
        assertThrows(IllegalArgumentException.class,()->parse(env,MinioStorageSettings.Scope.IMPORTS));
    }
    @Test void globalAndLegacyInputsHaveExplicitPrecedenceAndBlankNeverFallsBack(){
        var env=configured();env.put("OBJECT_STORAGE_BUCKET","shared-configured-bucket");env.put("MOP_S3_BUCKET","old-bucket");
        assertEquals("configured-photo-bucket",parse(env,MinioStorageSettings.Scope.PHOTOS).bucket());
        env.remove("OBJECT_STORAGE_PHOTO_BUCKET");assertEquals("shared-configured-bucket",parse(env,MinioStorageSettings.Scope.PHOTOS).bucket());
        env.put("OBJECT_STORAGE_PHOTO_BUCKET","");assertThrows(IllegalArgumentException.class,()->parse(env,MinioStorageSettings.Scope.PHOTOS));
    }
    @Test void httpsAndPrivateNetworkEndpointsAreAcceptedWithoutCredentialsInTheUri(){
        for(String endpoint:List.of("https://storage.example.test","http://192.168.1.215:19000","http://10.0.1.2:9000","http://172.31.2.3:9000","http://[::1]:9000")){
            var env=configured();env.put("OBJECT_STORAGE_ENDPOINT",endpoint);assertEquals(endpoint,parse(env,MinioStorageSettings.Scope.PHOTOS).endpoint());
        }
    }
    @Test void invalidEndpointNamespaceOrCredentialCannotSelectAnotherStore(){
        for(String endpoint:List.of("http://public.example.test","http://169.254.169.254","http://172.32.1.2:9000","http://127.0.0.1:0","https://user:isolated-secret@storage.example.test","https://storage.example.test/path","https://storage.example.test?secret=isolated-secret","file:///tmp/storage")){
            var env=configured();env.put("OBJECT_STORAGE_ENDPOINT",endpoint);var error=assertThrows(IllegalArgumentException.class,()->parse(env,MinioStorageSettings.Scope.PHOTOS));assertFalse(error.getMessage().contains("isolated"));assertNull(error.getCause());
        }
        for(var change:Map.of("OBJECT_STORAGE_PHOTO_BUCKET","../another-bucket","OBJECT_STORAGE_PHOTO_SECRET_KEY","","OBJECT_STORAGE_PHOTO_ACCESS_KEY","contains whitespace","OBJECT_STORAGE_REGION","../region").entrySet()){
            var env=configured();env.put(change.getKey(),change.getValue());assertThrows(IllegalArgumentException.class,()->parse(env,MinioStorageSettings.Scope.PHOTOS));
        }
    }
    @Test void anOssOrDisabledSelectionCannotBeParsedAsMinio(){
        var env=configured();env.put("OBJECT_STORAGE_PROVIDER","oss");assertThrows(IllegalArgumentException.class,()->parse(env,MinioStorageSettings.Scope.PHOTOS));
        env.put("OBJECT_STORAGE_PROVIDER","minio");env.put("OBJECT_STORAGE_ENABLED","false");assertThrows(IllegalArgumentException.class,()->parse(env,MinioStorageSettings.Scope.PHOTOS));
    }
}
