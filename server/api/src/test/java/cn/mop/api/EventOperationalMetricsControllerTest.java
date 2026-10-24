package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.operations.*;
import cn.mop.domain.operations.EventOperationalMetricsReader.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authorization/projection doubles only; no live PostgreSQL claim. */
class EventOperationalMetricsControllerTest {
 static final String ACTOR="79000000-0000-4000-8000-000000000001",TENANT="79000000-0000-4000-8000-000000000002";
 final PlatformRepository platform=mock(PlatformRepository.class);
 final EventOperationalMetricsReader reader=mock(EventOperationalMetricsReader.class);
 final EventOperationalMetricsController controller=new EventOperationalMetricsController(platform,reader);
 final MockHttpServletRequest request=new MockHttpServletRequest();
 EventOperationalMetricsControllerTest(){request.setUserPrincipal(()->ACTOR);request.getSession().setAttribute("mop.tenant_id",TENANT);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",1,true)));}
 Snapshot snapshot(String tenant){var to=Instant.parse("2026-10-23T20:00:00Z");var from=to.minusSeconds(86400);var counts=new EnumMap<Severity,Long>(Severity.class);for(var s:Severity.values())counts.put(s,0L);return new Snapshot(UUID.fromString(tenant),UUID.fromString(ACTOR),1,to,Window.HOURS_24,View.RECENT_PROBLEMS,from,counts,0,EventOperationalMetrics.calculate(UUID.fromString(tenant),from,to,0,List.of()),List.of(),null);}
 @Test void defaultReadUsesAuthenticatedScope(){when(reader.read(any(),any(),any(),isNull())).thenReturn(snapshot(TENANT));assertNotNull(controller.read(request));verify(reader).read(new Access(ACTOR,TENANT,1),Window.HOURS_24,View.RECENT_PROBLEMS,null);verify(platform,times(2)).membership(ACTOR,TENANT);}
 @ParameterizedTest @ValueSource(strings={"inactive","removed","version","capability","role","tenant","session"})
 void rejectsPermissionChangesBeforeReturn(String kind){when(reader.read(any(),any(),any(),isNull())).thenAnswer(call->{switch(kind){case "inactive"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",1,false)));case "removed"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());case "version"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",2,true)));case "capability"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",1,true,List.of("dashboard:read"))));case "role"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",1,true)));case "tenant"->request.getSession().setAttribute("mop.tenant_id",ACTOR);case "session"->request.getSession().invalidate();}return snapshot(TENANT);});var error=assertThrows(DomainException.class,()->controller.read(request));assertEquals(401,error.status);assertEquals("SESSION_EXPIRED",error.code);}
 @ParameterizedTest @ValueSource(strings={"tenant_id","actor_id","observed_at","before","before_id","unknown"})
 void rejectsScopeOverridesAndIncompleteCursors(String field){request.addParameter(field,"arbitrary");assertEquals(400,assertThrows(DomainException.class,()->controller.read(request)).status);verifyNoInteractions(reader);}
 @Test void rejectsDuplicateWindow(){request.addParameter("window","HOURS_24","DAYS_7");assertEquals(400,assertThrows(DomainException.class,()->controller.read(request)).status);verifyNoInteractions(reader);}
 @Test void rejectsForeignProjection(){when(reader.read(any(),any(),any(),isNull())).thenReturn(snapshot(ACTOR));assertEquals(503,assertThrows(DomainException.class,()->controller.read(request)).status);}
 @Test void missingSessionDoesNotRead(){request.getSession().invalidate();assertEquals(401,assertThrows(DomainException.class,()->controller.read(request)).status);verifyNoInteractions(reader);}
 @Test void zeroSamplesDoNotClaimMeasuredLatencyOrVerifiedHeartbeat(){var dto=EventOperationalMetricsController.dto(snapshot(TENANT));var m=(Map<?,?>)dto.get("mtta");assertNull(m.get("mean_millis"));assertNull(m.get("target_breached"));assertEquals("NO_ACKNOWLEDGED_SAMPLES",m.get("status"));assertEquals(false,dto.get("collection_freshness_verified"));assertEquals(false,dto.get("clock_accuracy_verified"));assertEquals(false,dto.get("frozen_report"));}
}
