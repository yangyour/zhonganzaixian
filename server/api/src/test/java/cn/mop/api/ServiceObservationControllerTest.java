package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.dashboard.ServiceObservationReader;
import cn.mop.domain.dashboard.ServiceObservationReader.*;
import cn.mop.domain.dashboard.ServiceObservationIntervals.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ServiceObservationControllerTest {
    static final String ACTOR="77000000-0000-4000-8000-000000000001",TENANT="77000000-0000-4000-8000-000000000002";
    PlatformRepository platform;ServiceObservationReader reader;ServiceObservationController controller;Membership member;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);reader=mock(ServiceObservationReader.class);controller=new ServiceObservationController(platform,reader,new ObjectMapper().findAndRegisterModules());member=new Membership(ACTOR,TENANT,"padmin",7,true);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(member));when(reader.read(any())).thenAnswer(call->snapshot(call.getArgument(0)));}
    static Snapshot snapshot(Access a){var asset=UUID.fromString("77000000-0000-4000-8000-000000000003");return ServiceObservationReader.evaluate(a,Instant.parse("2026-10-24T13:00:00Z"),"a".repeat(64),List.of(new Definition(asset,"服务",List.of(asset),FailureMode.ANY_ASSET_UNAVAILABLE,MaintenanceMode.ANY_ASSET_APPROVED,null)),Map.of(asset,new AssetEvidence(new ServiceObservationReader.Asset(asset,"设备",1),new Evidence(List.of(),List.of(),List.of(),List.of()),Set.of(),Set.of())));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
    void status(int status,org.junit.jupiter.api.function.Executable work){assertEquals(status,assertThrows(DomainException.class,work).status);}
    @Test void readIsScopedNoStoreAndNoWrites(){var response=new MockHttpServletResponse();var result=(Map<?,?>)controller.read(request(),response);var data=(Map<?,?>)result.get("data");assertEquals(TENANT,data.get("tenant_id"));assertEquals(false,data.get("writes_performed"));assertEquals(false,data.get("positive_health_coverage_loaded"));assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("2026-10-24T13:00:00Z",data.get("observed_at"));verify(platform,times(2)).membership(ACTOR,TENANT);verifyNoMoreInteractions(platform);}
    @Test void queryCannotChooseTenantOrObservationTime(){var r=request();r.addParameter("tenant_id",TENANT);status(400,()->controller.read(r,new MockHttpServletResponse()));verifyNoInteractions(reader);}
    @Test void customerRoleCannotReadInternalProjection(){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));status(403,()->controller.read(request(),new MockHttpServletResponse()));verifyNoInteractions(reader);}
    @Test void missingPrincipalFailsBeforeRead(){status(401,()->controller.read(new MockHttpServletRequest(),new MockHttpServletResponse()));verifyNoInteractions(reader);}
    @Test void nullOrForeignProjectionFails(){doReturn(null).when(reader).read(any());status(503,()->controller.read(request(),new MockHttpServletResponse()));doReturn(snapshot(new Access(UUID.randomUUID(),UUID.fromString(ACTOR),7))).when(reader).read(any());status(503,()->controller.read(request(),new MockHttpServletResponse()));}
    @Test void membershipRevokedDuringReadIsNotReturned(){doAnswer(call->{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());return snapshot(call.getArgument(0));}).when(reader).read(any());status(401,()->controller.read(request(),new MockHttpServletResponse()));}
    @Test void tenantSwitchDuringReadIsNotReturned(){var request=request();doAnswer(call->{request.getSession().setAttribute("mop.tenant_id",UUID.randomUUID().toString());return snapshot(call.getArgument(0));}).when(reader).read(any());status(401,()->controller.read(request,new MockHttpServletResponse()));}
    @Test void permissionVersionChangeDuringReadIsNotReturned(){doAnswer(call->{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",8,true)));return snapshot(call.getArgument(0));}).when(reader).read(any());status(401,()->controller.read(request(),new MockHttpServletResponse()));}
}
