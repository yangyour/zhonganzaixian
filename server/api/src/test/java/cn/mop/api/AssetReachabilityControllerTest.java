package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.dashboard.AssetReachability.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.time.Instant;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class AssetReachabilityControllerTest {
 static final String T="10000000-0000-4000-8000-000000000001",A="20000000-0000-4000-8000-000000000001",D="30000000-0000-4000-8000-000000000001";
 PlatformRepository platform;Reader reader;NetworkMonitoringService network;AssetReachabilityController controller;Membership member;
 Inventory inventory(Access a){return new Inventory(a,Instant.parse("2026-10-24T00:00:00Z"),List.of(new cn.mop.domain.dashboard.AssetReachability.Asset(UUID.fromString(D),"设备","10.0.0.1",1)));}
 @BeforeEach void setup(){platform=mock(PlatformRepository.class);reader=mock(Reader.class);network=mock(NetworkMonitoringService.class);member=new Membership(A,T,"padmin",7,true);when(platform.membership(A,T)).thenReturn(Optional.of(member));when(reader.read(any())).thenAnswer(c->inventory(c.getArgument(0)));controller=new AssetReachabilityController(platform,reader,network,new ObjectMapper().findAndRegisterModules());}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->A);r.getSession().setAttribute("mop.tenant_id",T);return r;}
 void status(int expected,org.junit.jupiter.api.function.Executable call){assertEquals(expected,assertThrows(DomainException.class,call).status);}
 @Test void disabledNetworkStillReturnsRealUnknownInventory(){var response=new MockHttpServletResponse();var result=(Map<?,?>)controller.read(request(),response);var data=(Map<?,?>)result.get("data");assertEquals(1,data.get("unknown"));assertNull(data.get("percentage"));assertEquals(false,data.get("source_configured"));assertEquals("no-store",response.getHeader("Cache-Control"));verify(reader,times(2)).read(any());verify(network,never()).summary(any(),any());}
 @Test void rejectsQueryAndMissingSessionBeforeSource(){var r=request();r.addParameter("tenant_id",T);status(400,()->controller.read(r,new MockHttpServletResponse()));status(401,()->controller.read(new MockHttpServletRequest(),new MockHttpServletResponse()));verifyNoInteractions(reader);}
 @Test void rejectsChangedAssetPopulation(){doReturn(inventory(new Access(UUID.fromString(T),UUID.fromString(A),7)),new Inventory(new Access(UUID.fromString(T),UUID.fromString(A),7),Instant.parse("2026-10-24T00:00:00Z"),List.of())).when(reader).read(any());status(503,()->controller.read(request(),new MockHttpServletResponse()));}
 @Test void revocationDuringReadBlocksResponse(){doAnswer(c->{when(platform.membership(A,T)).thenReturn(Optional.empty());return inventory(c.getArgument(0));}).when(reader).read(any());status(401,()->controller.read(request(),new MockHttpServletResponse()));}
 @Test void foreignScopeIsNeverAccepted(){doReturn(inventory(new Access(UUID.randomUUID(),UUID.fromString(A),7))).when(reader).read(any());status(503,()->controller.read(request(),new MockHttpServletResponse()));}
 @Test void enabledExplicitBindingReadsSampleWithoutWrites(){when(network.enabled()).thenReturn(true);var b=new NetworkMonitoringService.Binding(T,D,"10.0.0.1","test","test","1",Map.of());when(network.bindings(T)).thenReturn(List.of(b));when(network.summary(b,"设备")).thenReturn(Map.of("metrics",Map.of("icmp",Map.of("state","FRESH","value","1","recorded_at","2026-10-24T00:00:00Z"))));var result=(Map<?,?>)((Map<?,?>)controller.read(request(),new MockHttpServletResponse())).get("data");assertEquals(1,result.get("normal"));assertEquals(false,result.get("writes_performed"));}
}
