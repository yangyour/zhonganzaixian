package cn.mop.api;
import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CmdbTypeCatalogControllerTest {
 static final String USER="64000000-0000-4000-8000-000000000001",TENANT="64000000-0000-4000-8000-000000000002";
 PlatformRepository platform;CmdbTypeCatalogController controller;
 @BeforeEach void setup(){platform=mock(PlatformRepository.class);controller=new CmdbTypeCatalogController(platform,new ObjectMapper());when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"padmin",7,true)));}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/cmdb/type-catalog");r.setUserPrincipal(()->USER);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
 Map<?,?> read(MockHttpServletRequest r){return (Map<?,?>)((Map<?,?>)controller.get(r,new MockHttpServletResponse())).get("data");}
 @Test void exactSourceMetadataIsReadOnly(){var data=read(request());assertEquals(9,((List<?>)data.get("primary_types")).size());assertEquals(16,((List<?>)data.get("subtypes")).size());assertEquals(95,((List<?>)data.get("attributes")).size());assertEquals(4,((List<?>)data.get("state_dimensions")).size());assertEquals(false,data.get("runtime_publication"));assertEquals(false,data.get("asset_mutations"));assertEquals(false,data.get("production_approved"));}
 @Test void noStateChangingRepositoryCall(){read(request());verify(platform).membership(USER,TENANT);verifyNoMoreInteractions(platform);}
 @Test void missingSessionOrPrincipalFails(){var r=request();r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->read(r)).status);assertEquals(401,assertThrows(DomainException.class,()->read(new MockHttpServletRequest())).status);}
 @Test void staleInactiveAndCrossScopeMembershipFail(){for(Membership m:List.of(new Membership(USER,TENANT,"padmin",0,true),new Membership(USER,TENANT,"padmin",7,false),new Membership(USER,"other","padmin",7,true),new Membership("other",TENANT,"padmin",7,true))){when(platform.membership(USER,TENANT)).thenReturn(Optional.of(m));assertEquals(401,assertThrows(DomainException.class,()->read(request())).status);}}
 @Test void currentCapabilityRequired(){when(platform.membership(USER,TENANT)).thenReturn(Optional.of(new Membership(USER,TENANT,"unknown",7,true)));assertEquals(403,assertThrows(DomainException.class,()->read(request())).status);}
 @Test void freshMembershipCheckedOnEveryRead(){read(request());when(platform.membership(USER,TENANT)).thenReturn(Optional.empty());assertEquals(401,assertThrows(DomainException.class,()->read(request())).status);verify(platform,times(2)).membership(USER,TENANT);}
 @Test void headersCannotOverrideSessionTenant(){var r=request();r.addHeader("X-Tenant-ID","other");assertEquals(TENANT,read(r).get("tenant_id"));}
 @Test void queryOverridesNeverChangeCatalog(){for(String key:List.of("tenant_id","approved","type","activated")){var r=request();r.addParameter(key,"forged");assertEquals(400,assertThrows(DomainException.class,()->read(r)).status);}}
 @Test void responseContainsNoRawSourceOrCustomerValues(){var d=read(request());assertFalse(d.containsKey("source_sections"));assertFalse(d.containsKey("contacts"));for(Object a:(List<?>)d.get("attributes")){assertFalse(((Map<?,?>)a).containsKey("value"));assertFalse(((Map<?,?>)a).containsKey("credential_ref"));}}
 @Test void noStoreAndNoReferrerHeaders(){var r=new MockHttpServletResponse();controller.get(request(),r);assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("no-referrer",r.getHeader("Referrer-Policy"));}
 @Test void committedWireFixtureMatchesActualController()throws Exception{
  var actual=new ObjectMapper().valueToTree(controller.get(request(),new MockHttpServletResponse()));
  var path=java.nio.file.Path.of("../../fixtures/cmdb-type-catalog-controller.json");
  if(Boolean.getBoolean("mop.exportCmdbFixture")){java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.writeString(path,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(actual)+"\n");}
  assertEquals(actual,new ObjectMapper().readTree(java.nio.file.Files.readString(path)));
 }
}
