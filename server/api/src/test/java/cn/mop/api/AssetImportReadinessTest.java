package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetImportReadinessTest {
    @Test void absentConfigurationDoesNotProbeOrClaimReady(){var sources=mock(AssetImportSourceRepository.class);var store=mock(AssetImportObjectStore.class);var status=new AssetImportConfiguration().assetImportDependencyProbe(new MockEnvironment(),sources,store).status();assertFalse(status.enabled());assertFalse(status.ready());assertEquals("NOT_PROBED",status.dto().get("worker_activity"));verifyNoInteractions(sources,store);}
    @Test void partialConfigurationAndDependencyExceptionsRemainUnreadyWithoutSecrets(){var sources=mock(AssetImportSourceRepository.class);var store=mock(AssetImportObjectStore.class);when(sources.ready()).thenThrow(new IllegalStateException("private-database-detail"));when(store.ready()).thenThrow(new IllegalStateException("private-storage-detail"));var status=new AssetImportConfiguration().assetImportDependencyProbe(new MockEnvironment().withProperty("MOP_IMPORT_S3_BUCKET","mop-test-asset-imports"),sources,store).status();assertFalse(status.ready());assertEquals(List.of("ASSET_IMPORT_POLICY_NOT_CONFIGURED","ASSET_IMPORT_SCHEMA_OR_RUNTIME_ACL_UNAVAILABLE","ASSET_IMPORT_STORAGE_UNAVAILABLE"),status.reasons());assertFalse(status.dto().toString().contains("private-"));}
    @Test void completeSchemaAndStorageStillNeverClaimWorkerOrBusinessSuccess(){var sources=mock(AssetImportSourceRepository.class);var store=mock(AssetImportObjectStore.class);when(sources.ready()).thenReturn(true);when(store.ready()).thenReturn(true);var status=new AssetImportConfiguration.ImportDependencyProbe(true,true,sources,store).status();assertTrue(status.ready());assertEquals("NOT_PROBED",status.dto().get("worker_activity"));assertEquals("CONFIG_PENDING",status.dto().get("business_status"));assertEquals(false,status.dto().get("can_import"));}
    @Test void mainReadinessUsesImportProbeAndLivenessNeverQueriesIt(){var repository=mock(PlatformRepository.class);when(repository.metadata()).thenReturn(new RepositoryMetadata("remote_test","postgresql",true,"test_oidc"));when(repository.dependencyReady()).thenReturn(true);var identity=mock(TestOidcConfiguration.OidcDependencyProbe.class);when(identity.ready()).thenReturn(true);var sources=mock(AssetImportSourceRepository.class);var store=mock(AssetImportObjectStore.class);var imports=new AssetImportConfiguration.ImportDependencyProbe(true,true,sources,store);var photos=new PhotoConfiguration.PhotoDependencyProbe(false,null,null,null);var controller=new PlatformController(repository,new ObjectMapper(),provider(identity),provider(photos),provider(imports));var request=new MockHttpServletRequest();controller.live(request);verifyNoInteractions(sources,store);DomainException failure=assertThrows(DomainException.class,()->controller.ready(request));assertEquals(503,failure.status);assertEquals("asset_imports",((Map<?,?>)failure.details.get(0)).get("component"));}
    private static <T> ObjectProvider<T> provider(T bean){ObjectProvider<T> result=mock(ObjectProvider.class);when(result.getIfAvailable()).thenReturn(bean);return result;}
}
