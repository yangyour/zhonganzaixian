package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.reporting.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ReportFollowupControllerTest {
 final String tenant=ReportFollowupWorklistTest.TENANT.toString(),actor=ReportFollowupWorklistTest.id(2).toString();
 final PlatformRepository platform=mock(PlatformRepository.class);final ReportPublicationRepository repo=mock(ReportPublicationRepository.class);
 final ReportPublicationController controller=new ReportPublicationController(platform,repo,new ObjectMapper().findAndRegisterModules());
 MockHttpServletRequest request(String role){var r=new MockHttpServletRequest("GET","/api/v1/report-publications/followups");r.setUserPrincipal(()->actor);r.getSession().setAttribute("mop.tenant_id",tenant);r.setParameter("from",ReportFollowupWorklistTest.FROM.toString());r.setParameter("until",ReportFollowupWorklistTest.UNTIL.toString());when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,role,7,true)));return r;}
 void answer(){when(repo.followups(any(),any())).thenAnswer(c->ReportFollowupWorklist.page(ReportFollowupWorklistTest.TENANT,ReportFollowupWorklistTest.NOW,c.getArgument(1),0,List.of()));}
 @Test void rejectsCustomerAndIncompleteGrantsBeforeRead(){assertEquals(403,assertThrows(DomainException.class,()->controller.followups(request("viewer"))).status);verifyNoInteractions(repo);}
 @Test void defaultDueQueryReturnsBoundNoWriteFacts(){var r=request("padmin");answer();var out=(Map<?,?>)controller.followups(r);var data=(Map<?,?>)out.get("data");assertEquals(false,data.get("external_dispatch_enabled"));assertEquals(false,data.get("writes_performed"));assertEquals("COMPLETE_BOUNDED_CAPTURE_WINDOW",data.get("population_scope"));verify(platform,times(2)).membership(actor,tenant);}
 @Test void malformedDuplicateAndScopeOverridesReject(){for(var params:List.of(Map.of("from",new String[]{"bad"}),Map.of("filter",new String[]{"ALL","UNREAD"}),Map.of("tenant_id",new String[]{tenant}),Map.of("before",new String[]{ReportFollowupWorklistTest.NOW.toString()}),Map.of("filter",new String[]{"bad"}),Map.of("from",new String[]{"2026-10-01T00:00:00+00:00"}))){var r=request("padmin");params.forEach(r::setParameter);assertEquals(400,assertThrows(DomainException.class,()->controller.followups(r)).status);}verifyNoInteractions(repo);}
 @ParameterizedTest @ValueSource(strings={"inactive","removed","version","role","capabilities","session","tenant","actor"})
 void rechecksEveryIdentityBoundaryBeforeReturning(String change){var r=request("padmin");when(repo.followups(any(),any())).thenAnswer(c->{switch(change){
 case "inactive"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",7,false)));
 case "removed"->when(platform.membership(actor,tenant)).thenReturn(Optional.empty());
 case "version"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",8,true)));
 case "role"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"msp",7,true)));
 case "capabilities"->when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",7,true,List.of("dashboard:read"))));
 case "session"->r.getSession().invalidate();case "tenant"->r.getSession().setAttribute("mop.tenant_id",actor);case "actor"->r.setUserPrincipal(()->tenant);}
 return ReportFollowupWorklist.page(ReportFollowupWorklistTest.TENANT,ReportFollowupWorklistTest.NOW,c.getArgument(1),0,List.of());});assertEquals(401,assertThrows(DomainException.class,()->controller.followups(r)).status);}
}
