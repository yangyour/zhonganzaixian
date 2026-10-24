package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.EventNoiseTrendReader;
import cn.mop.domain.monitoring.EventNoiseTrendReader.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EventNoiseTrendControllerTest {
 final String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002";final Instant observed=Instant.parse("2026-10-23T20:00:00Z"),from=observed.minusSeconds(3600);
 final PlatformRepository platform=mock(PlatformRepository.class);final EventNoiseTrendReader reader=mock(EventNoiseTrendReader.class);final EventNoiseTrendController controller=new EventNoiseTrendController(platform,reader);final MockHttpServletRequest request=new MockHttpServletRequest();
 EventNoiseTrendControllerTest(){request.setUserPrincipal(()->actor);request.getSession().setAttribute("mop.tenant_id",tenant);when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",1,true)));}
 Snapshot snapshot(){return EventNoiseTrendReader.assemble(new Access(UUID.fromString(tenant),UUID.fromString(actor),1),Window.HOURS_24,observed,List.of());}
 RecordPage page(){return new RecordPage(snapshot().access(),from,observed,observed,20,0,List.of(),null);}
 void recordQuery(){request.addParameter("from",from.toString());request.addParameter("to",observed.toString());}
 static Stream<String> changes(){return Stream.of("trend","records").flatMap(route->Stream.of("inactive","removed","version","capability","role","tenant","actor","session").map(change->route+":"+change));}
 @ParameterizedTest @MethodSource("changes") void rejectsEveryReadTimeScopeChange(String scenario){String[] values=scenario.split(":");Runnable change=()->{switch(values[1]){case "inactive"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",1,false)));case "removed"->when(platform.membership(actor,tenant)).thenReturn(Optional.empty());case "version"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",2,true)));case "capability"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",1,true,List.of("dashboard:read","tickets:read"))));case "role"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"msp",1,true)));case "tenant"->request.getSession().setAttribute("mop.tenant_id",actor);case "actor"->request.setUserPrincipal(()->tenant);case "session"->request.getSession().invalidate();}};when(reader.read(any(),any())).thenAnswer(c->{change.run();return snapshot();});when(reader.records(any(),any(),any(),isNull(),eq(20))).thenAnswer(c->{change.run();return page();});if(values[0].equals("records"))recordQuery();assertEquals(401,assertThrows(DomainException.class,()->{if(values[0].equals("trend"))controller.read(request);else controller.records(request);}).status);}
 @Test void normalReadsKeepPresetAndRawBoundsWithoutWrites(){when(reader.read(any(),any())).thenReturn(snapshot());assertNotNull(controller.read(request));verify(reader).read(snapshot().access(),Window.HOURS_24);recordQuery();when(reader.records(any(),any(),any(),isNull(),eq(20))).thenReturn(page());assertNotNull(controller.records(request));verify(reader).records(snapshot().access(),from,observed,null,20);}
 @ParameterizedTest @ValueSource(strings={"tenant_id","actor_id","from","final_notification_count"}) void trendRejectsOverrides(String key){request.addParameter(key,"untrusted");assertEquals(400,assertThrows(DomainException.class,()->controller.read(request)).status);verifyNoInteractions(reader);}
 @Test void recordsRejectIncompleteCursorAndOversizedSpanBeforeReader(){recordQuery();request.addParameter("before",from.toString());assertEquals(400,assertThrows(DomainException.class,()->controller.records(request)).status);request.removeParameter("before");request.setParameter("from",observed.minusSeconds(86401).toString());assertEquals(400,assertThrows(DomainException.class,()->controller.records(request)).status);verifyNoInteractions(reader);}
 @Test void customerRoleCannotReadEitherRoute(){when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"tadmin",1,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.read(request)).status);recordQuery();assertEquals(403,assertThrows(DomainException.class,()->controller.records(request)).status);verifyNoInteractions(reader);}
 @Test void rawFirstPageCannotClaimEmptyWithNonzeroTotal(){recordQuery();when(reader.records(any(),any(),any(),isNull(),eq(20))).thenReturn(new RecordPage(snapshot().access(),from,observed,observed,20,1,List.of(),null));assertEquals(503,assertThrows(DomainException.class,()->controller.records(request)).status);}
}
