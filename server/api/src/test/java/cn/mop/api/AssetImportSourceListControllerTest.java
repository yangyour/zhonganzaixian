package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AssetImportSourceListControllerTest {
 static final String USER="64000000-0000-4000-8000-000000000001",TENANT="64000000-0000-4000-8000-000000000002",ID="64000000-0000-4000-8000-000000000003";
 PlatformRepository platform;AssetImportSourceRepository sources;AssetImportController controller;
 @BeforeEach void setup(){platform=mock(PlatformRepository.class);sources=mock(AssetImportSourceRepository.class);controller=new AssetImportController(platform,sources,mock(AssetImportObjectStore.class),mock(AssetImportPolicy.class),new ObjectMapper());when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"msp",7,true)));when(sources.listSources(any(),anyInt(),nullable(String.class))).thenReturn(Map.of("items",List.of(),"can_import",false));}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/assets/import-sources");r.setUserPrincipal(()->USER);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
 Object read(MockHttpServletRequest r){return controller.list(r,new MockHttpServletResponse());}
 @Test void defaultOwnerReadIsBoundedAndDoesNotAuditRawContent(){read(request());verify(sources).listSources(argThat(a->a.actorId().equals(USER)&&a.tenantId().equals(TENANT)&&a.permissionVersion()==7),eq(20),isNull());verify(platform).membership(USER,TENANT);verifyNoMoreInteractions(platform,sources);}
 @Test void acceptsExplicitPageBoundary(){var r=request();r.addParameter("limit","50");r.addParameter("cursor",ID);read(r);verify(sources).listSources(any(),eq(50),eq(ID));}
 @Test void malformedLimitsNeverReachStorage(){for(String v:List.of("0","51","01","1.0","-1","10000"," 1","")){var r=request();r.addParameter("limit",v);assertEquals(400,assertThrows(DomainException.class,()->read(r)).status);}verifyNoInteractions(sources);}
 @Test void unknownAndDuplicateQueryCannotOverrideScope(){for(String key:List.of("tenant_id","created_by","limit")){var r=request();if(key.equals("limit"))r.addParameter(key,"1","2");else r.addParameter(key,ID);assertEquals(400,assertThrows(DomainException.class,()->read(r)).status);}verifyNoInteractions(sources);}
 @Test void invalidCursorCannotReachStorage(){for(String v:List.of("", "../other", "fake",ID.toUpperCase()+"x")){var r=request();r.addParameter("cursor",v);assertEquals(400,assertThrows(DomainException.class,()->read(r)).status);}verifyNoInteractions(sources);}
 @Test void anonymousAndViewerRejected(){var r=request();r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->read(r)).status);when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"viewer",7,true)));assertEquals(403,assertThrows(DomainException.class,()->read(request())).status);verifyNoInteractions(sources);}
 @Test void unavailableDoesNotBecomeEmptySuccess(){when(sources.listSources(any(),anyInt(),nullable(String.class))).thenThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","unavailable"));assertEquals(503,assertThrows(DomainException.class,()->read(request())).status);}
 @Test void noStoreAndReferrer(){var out=new MockHttpServletResponse();controller.list(request(),out);assertEquals("no-store",out.getHeader("Cache-Control"));assertEquals("no-referrer",out.getHeader("Referrer-Policy"));}
}
