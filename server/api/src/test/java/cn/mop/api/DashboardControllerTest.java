package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.dashboard.*;
import cn.mop.domain.dashboard.DashboardAvailabilityReader.Access;
import cn.mop.domain.dashboard.DashboardAvailabilitySnapshot.TrendWindow;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated boundary tests; no actual PG, OIDC or monitoring source. */
class DashboardControllerTest {
    static final String ACTOR="77000000-0000-4000-8000-000000000001", TENANT="77000000-0000-4000-8000-000000000002", OTHER="77000000-0000-4000-8000-000000000003";
    static final Instant TIME=Instant.parse("2026-10-05T01:00:00.123Z");
    PlatformRepository platform;DashboardAvailabilityReader reader;DashboardController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);reader=mock(DashboardAvailabilityReader.class);controller=new DashboardController(platform,reader);member(ACTOR,TENANT,"viewer",7,true);when(reader.read(any(),any())).thenAnswer(c->snapshot(c.getArgument(0),c.getArgument(1)));}
    void member(String actor,String tenant,String role,long pv,boolean active){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(actor,tenant,role,pv,active)));}
    static DashboardAvailabilitySnapshot snapshot(Access a,TrendWindow w){return new DashboardAvailabilitySnapshot(UUID.fromString(a.tenantId()),UUID.fromString(a.actorId()),a.permissionVersion(),TIME,w);}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/dashboard");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","dashboard-unit");return r;}
    static Map<?,?> data(Object result){return (Map<?,?>)((Map<?,?>)result).get("data");}
    static void status(int code,org.junit.jupiter.api.function.Executable work){assertEquals(code,assertThrows(DomainException.class,work).status);}
    @Test void defaultWindowIsExplicitAndUsesCurrentAuthorization(){var result=data(controller.read(request()));assertEquals("HOURS_24",result.get("window"));assertEquals(TENANT,result.get("tenant_id"));assertEquals(ACTOR,result.get("actor_id"));assertEquals(7L,result.get("permission_version"));verify(reader).read(new Access(ACTOR,TENANT,7),TrendWindow.HOURS_24);}
    @Test void allThreePresetsPassUnchanged(){for(var window:TrendWindow.values()){var r=request();r.addParameter("window",window.name());assertEquals(window.name(),data(controller.read(r)).get("window"));}}
    @Test void allFourKnownCurrentRolesCanRead(){for(String role:List.of("viewer","tadmin","msp","padmin")){member(ACTOR,TENANT,role,8,true);assertEquals(8L,data(controller.read(request())).get("permission_version"));}}
    @Test void unknownOrNullDatabaseRoleIsNotUpgraded(){for(String role:Arrays.asList("superadmin",null)){member(ACTOR,TENANT,role,7,true);status(403,()->controller.read(request()));}verifyNoInteractions(reader);}
    @Test void missingPrincipalOrSessionIsUnauthorized(){var r=request();r.setUserPrincipal(null);status(401,()->controller.read(r));status(401,()->controller.read(new MockHttpServletRequest()));verifyNoInteractions(reader);}
    @Test void malformedSessionScopeIsUnauthorized(){for(Object value:List.of(7,"","AA000000-0000-4000-8000-000000000003","../tenant")){var r=request();r.getSession().setAttribute("mop.tenant_id",value);status(401,()->controller.read(r));}verifyNoInteractions(reader);}
    @Test void mismatchedMembershipCannotCrossActorOrTenant(){member(OTHER,TENANT,"padmin",7,true);status(401,()->controller.read(request()));member(ACTOR,OTHER,"padmin",7,true);status(401,()->controller.read(request()));verifyNoInteractions(reader);}
    @Test void missingInactiveAndInvalidPermissionMembershipFail(){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());status(401,()->controller.read(request()));member(ACTOR,TENANT,"viewer",7,false);status(401,()->controller.read(request()));for(long pv:new long[]{0,-1,9007199254740992L}){member(ACTOR,TENANT,"viewer",pv,true);status(401,()->controller.read(request()));}verifyNoInteractions(reader);}
    @Test void duplicateAndUnknownQueriesCannotChangeTrustedScope(){for(String key:List.of("tenant_id","actor_id","from","to","permission_version","layout","unknown")){var r=request();r.addParameter(key,"forged");status(400,()->controller.read(r));}var r=request();r.addParameter("window","HOURS_24","DAYS_7");status(400,()->controller.read(r));verifyNoInteractions(reader);}
    @Test void malformedAndOversizedQueriesAreBounded(){for(String value:List.of("","hours_24"," HOURS_24","DAYS_1","x".repeat(17))){var r=request();r.addParameter("window",value);status(400,()->controller.read(r));}var r=request();r.setQueryString("x".repeat(2049));status(400,()->controller.read(r));verifyNoInteractions(reader);}
    @Test void outputNeverClaimsMetricsOrClockSync(){var d=data(controller.read(request()));assertEquals(false,d.get("metric_samples_loaded"));assertEquals(false,d.get("clock_accuracy_verified"));assertEquals(false,d.get("writes_performed"));assertEquals(false,d.get("layout_customization_allowed"));assertEquals("NOT_IMPLEMENTED",d.get("formal_metric_status"));assertEquals(6,((List<?>)d.get("kpis")).size());for(Object row:(List<?>)d.get("kpis")){var k=(Map<?,?>)row;for(String key:List.of("value","sample_count","sampled_at","metric_version","threshold_breached","drilldown_path"))assertNull(k.get(key));}for(Object row:(List<?>)d.get("panels"))assertNull(((Map<?,?>)row).get("items"));}
    @Test void nullOrMismatchedReaderProjectionReturns503(){doReturn(null).when(reader).read(any(),any());status(503,()->controller.read(request()));for(Access access:List.of(new Access(OTHER,TENANT,7),new Access(ACTOR,OTHER,7),new Access(ACTOR,TENANT,8))){doReturn(snapshot(access,TrendWindow.HOURS_24)).when(reader).read(any(),any());status(503,()->controller.read(request()));}doReturn(snapshot(new Access(ACTOR,TENANT,7),TrendWindow.DAYS_30)).when(reader).read(any(),any());status(503,()->controller.read(request()));}
    @Test void authoritativeFailureIsNotReplacedByUnknownSuccess(){for(int code:new int[]{401,403,503}){doThrow(new DomainException(code,"TEST_FAILURE","isolated")).when(reader).read(any(),any());status(code,()->controller.read(request()));}}
    @Test void readingNeverUsesWriteOrReceiptMethods(){controller.read(request());verify(platform,times(2)).membership(ACTOR,TENANT);verifyNoMoreInteractions(platform);}
    @Test void configurationRejectsFixtureRepository(){assertThrows(IllegalStateException.class,()->new DashboardConfiguration().dashboardAvailabilityReader(mock(javax.sql.DataSource.class),platform));}
}
