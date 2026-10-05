package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.dashboard.DashboardAvailabilityReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual controller methods with isolated collaborators, not an HTTP/PG integration test. */
class EffectiveCapabilityEntryTest {
    private static final String ACTOR="55000000-0000-4000-8000-000000000003",TENANT="55000000-0000-4000-8000-000000000001",ASSET="55000000-0000-4000-8000-000000000004";
    private MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/unit");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
    private PlatformRepository repository(String role,List<String> selected){
        var repository=mock(PlatformRepository.class);
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,8,true,selected)));
        when(repository.user(ACTOR)).thenReturn(Optional.of(new FixtureUser(ACTOR,"测试",role)));
        when(repository.tenant(TENANT)).thenReturn(new Tenant(TENANT,"测试租户"));return repository;
    }
    private PlatformController platform(PlatformRepository repository){var beans=new StaticListableBeanFactory();return new PlatformController(repository,new ObjectMapper(),beans.getBeanProvider(TestOidcConfiguration.OidcDependencyProbe.class),beans.getBeanProvider(PhotoConfiguration.PhotoDependencyProbe.class),beans.getBeanProvider(AssetImportConfiguration.ImportDependencyProbe.class));}
    @Test void removedAssetReadIsRejectedBeforeObjectLookup(){
        var repository=repository("padmin",List.of());
        assertEquals(403,assertThrows(DomainException.class,()->platform(repository).asset(ASSET,request())).status);
        verify(repository,never()).authorizedAsset(anyString(),anyString(),anyLong(),anyString());
    }
    @Test void readOnlyPadminCanReachAuthorizedLookupButCannotCreate(){
        var repository=repository("padmin",List.of("assets:read"));var controller=platform(repository);
        assertEquals(404,assertThrows(DomainException.class,()->controller.asset(ASSET,request())).status);
        verify(repository).authorizedAsset(TENANT,ACTOR,8,ASSET);
        assertEquals(403,assertThrows(DomainException.class,()->controller.createAsset(new ObjectMapper().createObjectNode(),request())).status);
    }
    @Test void removedDashboardPermissionPreventsReaderInvocation(){
        var repository=repository("padmin",List.of("assets:read"));var reader=mock(DashboardAvailabilityReader.class);
        assertEquals(403,assertThrows(DomainException.class,()->new DashboardController(repository,reader).read(request())).status);
        verifyNoInteractions(reader);
    }
    @Test void photoReadPermissionDoesNotRestoreRemovedUploadPermission(){
        var repository=repository("viewer",List.of("tickets:read"));var policy=mock(PhotoPolicy.class);var storage=mock(PhotoObjectStore.class);
        var controller=new FileController(repository,policy,storage,new ObjectMapper());
        assertEquals(403,assertThrows(DomainException.class,()->controller.upload(request())).status);
        verifyNoInteractions(policy,storage);
    }
}
