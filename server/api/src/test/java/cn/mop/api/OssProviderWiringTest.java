package cn.mop.api;

import cn.mop.adapters.oss.*;
import cn.mop.adapters.MinioPhotoStore;
import cn.mop.adapters.MinioAssetImportStore;
import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.AssetImportSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Provider selection only. Valid SDK clients are closed without making cloud requests. */
class OssProviderWiringTest {
    private MockEnvironment configured(){return new MockEnvironment()
        .withProperty("OBJECT_STORAGE_ENABLED","True").withProperty("OBJECT_STORAGE_PROVIDER","oss")
        .withProperty("OBJECT_STORAGE_ENDPOINT","https://oss-cn-beijing.aliyuncs.com").withProperty("OBJECT_STORAGE_PUBLIC_ENDPOINT","https://oss-cn-beijing.aliyuncs.com")
        .withProperty("OBJECT_STORAGE_REGION","cn-beijing").withProperty("OBJECT_STORAGE_BUCKET","synthetic-test-bucket")
        .withProperty("OBJECT_STORAGE_ACCESS_KEY","synthetic-access").withProperty("OBJECT_STORAGE_SECRET_KEY","synthetic-secret")
        .withProperty("MOP_OBJECT_STORAGE_PREFIX","mop-niko-test/00000000-0000-4000-8000-000000000001/")
        .withProperty("OBJECT_STORAGE_PRESIGN_EXPIRES","3600");}
    @Test void onlyCompleteAbsenceSelectsLegacyMinio(){
        assertEquals(StorageProviderSelection.Mode.LEGACY_MINIO,StorageProviderSelection.from(new MockEnvironment()::getProperty).mode());
        var env=new MockEnvironment().withProperty("MOP_OBJECT_STORAGE_PREFIX","partial");
        assertThrows(IllegalArgumentException.class,()->StorageProviderSelection.from(env::getProperty));assertTrue(StorageProviderSelection.capabilityRequested(env::getProperty));
    }
    @Test void explicitDisableDoesNotFallBackToLegacy(){
        var env=configured().withProperty("OBJECT_STORAGE_ENABLED","false").withProperty("MOP_S3_ENDPOINT","http://127.0.0.1:19000").withProperty("MOP_S3_BUCKET","mop-test-photos").withProperty("MOP_S3_ACCESS_KEY","old").withProperty("MOP_S3_SECRET_KEY","old");
        assertEquals(StorageProviderSelection.Mode.DISABLED,StorageProviderSelection.from(env::getProperty).mode());
        var photo=new PhotoConfiguration().photoObjectStore(env);var source=new AssetImportConfiguration().assetImportObjectStore(env);
        assertFalse(photo instanceof MinioPhotoStore);assertFalse(source instanceof MinioAssetImportStore);assertFalse(photo.ready());assertFalse(source.ready());
    }
    @Test void completeOssSelectsClosableNativeStoresWithoutConnecting()throws Exception{
        var env=configured();assertEquals(StorageProviderSelection.Mode.OSS,StorageProviderSelection.from(env::getProperty).mode());
        var photos=new PhotoConfiguration().photoObjectStore(env);var imports=new AssetImportConfiguration().assetImportObjectStore(env);
        assertInstanceOf(OssProjectStore.PhotoView.class,photos);assertInstanceOf(OssProjectStore.ImportView.class,imports);
        ((AutoCloseable)photos).close();((AutoCloseable)imports).close();
    }
    @Test void invalidProviderAndMissingSecretsRemainUnavailable(){
        for(var env:List.of(configured().withProperty("OBJECT_STORAGE_PROVIDER","unknown"),configured().withProperty("OBJECT_STORAGE_SECRET_KEY",""),new MockEnvironment().withProperty("OBJECT_STORAGE_ENABLED","true"))){
            env.withProperty("MOP_S3_ENDPOINT","http://127.0.0.1:19000").withProperty("MOP_S3_BUCKET","mop-test-photos").withProperty("MOP_S3_ACCESS_KEY","synthetic-old").withProperty("MOP_S3_SECRET_KEY","synthetic-old")
                .withProperty("MOP_IMPORT_S3_ENDPOINT","http://127.0.0.1:19000").withProperty("MOP_IMPORT_S3_BUCKET","mop-test-asset-imports").withProperty("MOP_IMPORT_S3_ACCESS_KEY","synthetic-old").withProperty("MOP_IMPORT_S3_SECRET_KEY","synthetic-old");
            var photos=new PhotoConfiguration().photoObjectStore(env);var imports=new AssetImportConfiguration().assetImportObjectStore(env);
            assertFalse(photos instanceof MinioPhotoStore);assertFalse(imports instanceof MinioAssetImportStore);
            assertFalse(photos.ready());assertFalse(imports.ready());assertEquals(503,assertThrows(DomainException.class,()->photos.read("unused",1,"unused")).status);
        }
    }
    @Test void partialGlobalConfigurationCannotDisappearFromReadiness(){
        var env=new MockEnvironment().withProperty("OBJECT_STORAGE_PROVIDER","oss");var repository=mock(PlatformRepository.class);var sources=mock(AssetImportSourceRepository.class);
        when(repository.photoDependencyProblems()).thenReturn(List.of());when(sources.ready()).thenReturn(true);
        var photos=new PhotoConfiguration();var photo=photos.photoDependencyProbe(env,photos.photoPolicy(env),photos.photoObjectStore(env),repository).status();
        var imports=new AssetImportConfiguration();var source=imports.assetImportDependencyProbe(env,sources,imports.assetImportObjectStore(env)).status();
        assertTrue(photo.enabled());assertFalse(photo.ready());assertTrue(source.enabled());assertFalse(source.ready());assertTrue(source.reasons().contains("ASSET_IMPORT_STORAGE_UNAVAILABLE"));
    }
    @Test void disabledGlobalConfigurationWithoutCapabilitiesStaysDisabled(){
        var env=new MockEnvironment().withProperty("OBJECT_STORAGE_ENABLED","false");var sources=mock(AssetImportSourceRepository.class);var c=new AssetImportConfiguration();var result=c.assetImportDependencyProbe(env,sources,c.assetImportObjectStore(env)).status();
        assertFalse(result.enabled());assertFalse(result.ready());verifyNoInteractions(sources);
    }
}
