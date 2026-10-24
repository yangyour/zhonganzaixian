package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringEnrollment.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringEnrollmentControllerTest {
 final String tenant="79000000-0000-4000-8000-000000000002",actor="79000000-0000-4000-8000-000000000001",id="79000000-0000-4000-8000-000000000005";
 final PlatformRepository platform=mock(PlatformRepository.class);final MonitoringEnrollmentRepository tasks=mock(MonitoringEnrollmentRepository.class);
 final MonitoringOnboardingPlanner planner=new MonitoringOnboardingPlanner("{\"version\":1,\"collectors\":[]}");final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
 final MonitoringEnrollmentProbe probe=new MonitoringEnrollmentProbe(false,"[]","[]",t->{throw new AssertionError("No transport");});
 MonitoringEnrollmentController controller(boolean enabled){return new MonitoringEnrollmentController(platform,tasks,planner,probe,json,new MockEnvironment().withProperty("MOP_MONITORING_ENROLLMENT_ENABLED",Boolean.toString(enabled)));}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->actor);r.getSession().setAttribute("mop.tenant_id",tenant);when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",7,true)));return r;}
 State state(String target,String catalog){return MonitoringEnrollment.create(new Document(UUID.fromString(id),UUID.fromString(target),UUID.fromString(actor),Instant.parse("2026-10-23T00:00:00Z"),catalog,"a".repeat(64),MonitoringEnrollmentProbeTest.config(),List.of(new AssetSnapshot(UUID.fromString(actor),1,"192.0.2.1"))));}
 @Test void disabledFeatureDoesNotReadNewTables(){assertEquals(503,assertThrows(DomainException.class,()->controller(false).get(id,request())).status);verifyNoInteractions(tasks);}
 @Test void crossTenantResultNeverReturned(){var r=request();when(tasks.get(any(),eq(id))).thenReturn(state(actor,planner.catalogSha256()));assertThrows(DomainException.class,()->controller(true).get(id,r));}
 @Test void currentMembershipRecheckedAfterReading(){var r=request();when(tasks.get(any(),eq(id))).thenAnswer(c->{when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",8,true)));return state(tenant,planner.catalogSha256());});assertEquals(401,assertThrows(DomainException.class,()->controller(true).get(id,r)).status);verify(platform,times(2)).membership(actor,tenant);}
 @Test void historicalCatalogCanBeInspectedButProbePermissionIsFalse(){var r=request();when(tasks.get(any(),eq(id))).thenReturn(state(tenant,"b".repeat(64)));var response=(Map<?,?>)controller(true).get(id,r);var data=(Map<?,?>)response.get("data");assertEquals(false,data.get("catalog_current"));assertEquals(false,((Map<?,?>)((List<?>)data.get("targets")).get(0)).get("test_authorized"));}
 @Test void clientsCannotProvideProbeVerdictsOrUrls()throws Exception{var r=request();assertThrows(DomainException.class,()->controller(true).test(id,json.readTree("{\"target_id\":\""+id+"\",\"configuration_sha256\":\""+"a".repeat(64)+"\",\"expected_version\":3,\"status\":\"SAMPLE_VERIFIED_ONCE\"}"),r));verifyNoInteractions(tasks);}
 @Test void approveRequiresExplicitVersionAndSingleCommandKey()throws Exception{var r=request();r.addHeader("Idempotency-Key","one");r.addHeader("Idempotency-Key","two");assertThrows(DomainException.class,()->controller(true).change(id,json.readTree("{\"action\":\"APPROVE\",\"expected_version\":1,\"reason\":null}"),r));verifyNoInteractions(tasks);}
}
