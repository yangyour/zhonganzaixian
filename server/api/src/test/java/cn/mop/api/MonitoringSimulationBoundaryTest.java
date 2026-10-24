package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringSimulationBoundaryTest {
 @Test void simulatedFailureRejectedBeforeIdempotencyOrBusinessRepository(){
  String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002";var platform=mock(PlatformRepository.class);var repository=mock(MonitoringEventCenterRepository.class);var controller=new MonitoringEventCenterController(platform,repository);
  var r=new MockHttpServletRequest();r.setUserPrincipal(()->actor);r.getSession().setAttribute("mop.tenant_id",tenant);r.setContentType("application/json");r.setContent(("{\"asset_id\":\"79000000-0000-4000-8000-000000000003\",\"rule_code\":\"UNIT\",\"rule_name\":\"unit\",\"severity\":\"HIGH\",\"source_phase\":\"PROBLEM\",\"source_event_id\":\"unit-event\",\"occurred_at\":null,\"simulate_autoticket_failure\":true}").getBytes(StandardCharsets.UTF_8));when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",1,true)));
  var failure=assertThrows(DomainException.class,()->controller.ingest(r));assertEquals(422,failure.status);assertEquals("MONITORING_SIMULATION_FORBIDDEN",failure.code);verifyNoInteractions(repository);verify(platform,never()).idempotentAuthorized(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any(),any());
 }
}
