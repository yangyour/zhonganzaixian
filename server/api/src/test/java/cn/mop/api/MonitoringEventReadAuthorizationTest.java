package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import java.util.stream.Stream;
import static cn.mop.api.MonitoringEventCenterContractSamples.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Synthetic read-race regression; never a database or collector integration claim. */
class MonitoringEventReadAuthorizationTest {
 final PlatformRepository platform=mock(PlatformRepository.class);
 final MonitoringEventCenterRepository repository=mock(MonitoringEventCenterRepository.class);
 final MonitoringEventCenterController controller=new MonitoringEventCenterController(platform,repository);
 final MockHttpServletRequest request=new MockHttpServletRequest();
 MonitoringEventReadAuthorizationTest(){request.setUserPrincipal(()->ACTOR);request.getSession().setAttribute("mop.tenant_id",TENANT);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",3,true)));}
 static Stream<String> scenarios(){return Stream.of("workspace","detail","funnel").flatMap(endpoint->Stream.of("removed","inactive","version","role","capability","session","tenant","actor").map(change->endpoint+":"+change));}
 void source(Runnable change){when(repository.workspace(any(),anyString(),anyInt())).thenAnswer(call->{change.run();return workspace("24h");});when(repository.eventDetail(any(),anyString())).thenAnswer(call->{change.run();return detail(false);});when(repository.noiseFunnel(any(),anyString())).thenAnswer(call->{change.run();return funnel("24h");});}
 Object read(String endpoint){return switch(endpoint){case "workspace"->controller.workspace(request);case "detail"->controller.eventDetail(EVENT,request);case "funnel"->controller.noiseFunnel(request);default->throw new AssertionError();};}
 @ParameterizedTest @MethodSource("scenarios") void rejectsReadTimeRevocation(String scenario){var parts=scenario.split(":");source(()->{switch(parts[1]){case "removed"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());case "inactive"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",3,false)));case "version"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",4,true)));case "role"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",3,true)));case "capability"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",3,true,List.of("tickets:read"))));case "session"->request.getSession().invalidate();case "tenant"->request.getSession().setAttribute("mop.tenant_id",ASSET);case "actor"->request.setUserPrincipal(()->ADMIN);}});var failure=assertThrows(DomainException.class,()->read(parts[0]));assertEquals(401,failure.status);assertEquals("SESSION_EXPIRED",failure.code);}
 @Test void stableReadsKeepDtoAndRecheckExactlyOnce(){for(var endpoint:List.of("workspace","detail","funnel")){clearInvocations(platform);source(()->{});assertNotNull(read(endpoint));verify(platform,times(2)).membership(ACTOR,TENANT);verifyNoMoreInteractions(platform);}}
 @Test void inconsistentInitialIdentityCannotRead(){for(var member:List.of(new Membership(ADMIN,TENANT,"msp",3,true),new Membership(ACTOR,ASSET,"msp",3,true),new Membership(ACTOR,TENANT,"msp",9007199254740992L,true))){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(member));assertEquals(401,assertThrows(DomainException.class,()->controller.workspace(request)).status);}verifyNoInteractions(repository);}
}
