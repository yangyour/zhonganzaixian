package cn.mop.api;
import cn.mop.domain.*;import cn.mop.domain.dashboard.NotificationAcceptance;import cn.mop.domain.dashboard.NotificationAcceptance.*;
import com.fasterxml.jackson.databind.ObjectMapper;import org.junit.jupiter.api.*;import org.springframework.mock.web.*;
import java.time.Instant;import java.util.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class NotificationAcceptanceControllerTest {
 static final String T="10000000-0000-4000-8000-000000000001",A="20000000-0000-4000-8000-000000000001";
 PlatformRepository platform;Reader reader;NotificationAcceptanceController controller;
 @BeforeEach void setup(){platform=mock(PlatformRepository.class);reader=mock(Reader.class);when(platform.membership(A,T)).thenReturn(Optional.of(new Membership(A,T,"padmin",7,true)));when(reader.read(any())).thenAnswer(c->NotificationAcceptance.evaluate(c.getArgument(0),Instant.parse("2026-10-24T00:00:00Z"),List.of()));controller=new NotificationAcceptanceController(platform,reader,new ObjectMapper().findAndRegisterModules());}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->A);r.getSession().setAttribute("mop.tenant_id",T);return r;}
 void status(int code,org.junit.jupiter.api.function.Executable run){assertEquals(code,assertThrows(DomainException.class,run).status);}
 @Test void actualEmptyLedgerStillCannotClaimZeroDeliveries(){var response=new MockHttpServletResponse();var data=(Map<?,?>)((Map<?,?>)controller.read(request(),response)).get("data");assertEquals(0,((Map<?,?>)data.get("counts")).get("accepted"));assertTrue(data.containsKey("verified_delivered"));assertNull(data.get("verified_delivered"));assertNull(data.get("delivery_rate_pct"));assertNull(data.get("delivery_rate_below_95"));assertEquals("no-store",response.getHeader("Cache-Control"));}
 @Test void rejectsQueryAndNoSession(){var r=request();r.addParameter("window","DAYS_7");status(400,()->controller.read(r,new MockHttpServletResponse()));status(401,()->controller.read(new MockHttpServletRequest(),new MockHttpServletResponse()));verifyNoInteractions(reader);}
 @Test void customersCannotReadInternalSubmissions(){when(platform.membership(A,T)).thenReturn(Optional.of(new Membership(A,T,"tadmin",7,true)));status(403,()->controller.read(request(),new MockHttpServletResponse()));verifyNoInteractions(reader);}
 @Test void revokedMembershipBlocksLateResponse(){doAnswer(c->{when(platform.membership(A,T)).thenReturn(Optional.empty());return NotificationAcceptance.evaluate(c.getArgument(0),Instant.parse("2026-10-24T00:00:00Z"),List.of());}).when(reader).read(any());status(401,()->controller.read(request(),new MockHttpServletResponse()));}
 @Test void tenantSwitchBlocksLateResponse(){var request=request();doAnswer(c->{request.getSession().setAttribute("mop.tenant_id",UUID.randomUUID().toString());return NotificationAcceptance.evaluate(c.getArgument(0),Instant.parse("2026-10-24T00:00:00Z"),List.of());}).when(reader).read(any());status(401,()->controller.read(request,new MockHttpServletResponse()));}
 @Test void foreignProjectionFailsClosed(){doReturn(NotificationAcceptance.evaluate(new Access(UUID.randomUUID(),UUID.fromString(A),7),Instant.parse("2026-10-24T00:00:00Z"),List.of())).when(reader).read(any());status(503,()->controller.read(request(),new MockHttpServletResponse()));}
}
