package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** In-flight account revision checks with synthetic identities; no real login or credential changes. */
class ProjectReadReturnIdentityTest {
 final String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002";
 final ProjectIdentityRepository identities=mock(ProjectIdentityRepository.class);final PlatformRepository platform=mock(PlatformRepository.class);final Membership member=new Membership(actor,tenant,"padmin",7,true);
 final MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/v1/dashboard/pending-tickets");final MockHttpSession session=new MockHttpSession();
 final ProjectSessionValidationFilter filter=new ProjectSessionValidationFilter(new ProjectIdentityService(identities,new ObjectMapper(),"01".repeat(32),false));
 ProjectReadReturnIdentityTest(){request.setUserPrincipal(()->actor);request.setSession(session);session.setAttribute("mop.tenant_id",tenant);when(platform.membership(actor,tenant)).thenReturn(Optional.of(member));when(identities.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(5,false)));authenticate(5);}
 void authenticate(long version){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ProjectIdentityService.LocalPrincipal(actor,version),null,List.of()));}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
 @ParameterizedTest @ValueSource(strings={"revision","removed","authentication","session_id","anonymous"})
 void accountOrRequestIdentityChangeCannotReturnOtherwiseValidMembership(String change){var error=assertThrows(DomainException.class,()->filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{switch(change){case "revision"->when(identities.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(6,false)));case "removed"->when(identities.sessionState(actor)).thenReturn(Optional.empty());case "authentication"->authenticate(5);case "session_id"->request.changeSessionId();case "anonymous"->SecurityContextHolder.clearContext();}DashboardReadAuthorization.recheck(request,platform,member);}));assertEquals(401,error.status);assertEquals("SESSION_EXPIRED",error.code);assertFalse(session.isInvalid(),"Reject this read without invalidating a concurrently refreshed session");}
 @Test void stableIdentityIsReadAgainBeforeResult()throws Exception{filter.doFilter(request,new MockHttpServletResponse(),(r,s)->DashboardReadAuthorization.recheck(request,platform,member));verify(identities,times(2)).sessionState(actor);verify(platform).membership(actor,tenant);}
 @Test void unavailableRevisionStoreDoesNotLeakPrivateDiagnosticOrReturnData(){var error=assertThrows(DomainException.class,()->filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{when(identities.sessionState(actor)).thenThrow(new IllegalStateException("synthetic-private-address"));DashboardReadAuthorization.recheck(request,platform,member);}));assertEquals(503,error.status);assertEquals("IDENTITY_UNAVAILABLE",error.code);assertFalse(error.getMessage().contains("synthetic-private-address"));}
 @Test void newForcedChangeRequirementBlocksBusinessReadEvenIfRevisionWasNotIncremented(){var error=assertThrows(DomainException.class,()->filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{when(identities.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(5,true)));DashboardReadAuthorization.recheck(request,platform,member);}));assertEquals(403,error.status);assertEquals("PASSWORD_CHANGE_REQUIRED",error.code);}
 @Test void allowedAccountEndpointRemainsAvailableForForcedPasswordChange()throws Exception{request.setRequestURI("/api/v1/account");when(identities.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(5,true)));filter.doFilter(request,new MockHttpServletResponse(),(r,s)->DashboardReadAuthorization.recheck(request,platform,member));verify(identities,times(2)).sessionState(actor);}
 @Test void projectIdentityCannotSkipAnUninstalledReturnGuard(){var error=assertThrows(DomainException.class,()->DashboardReadAuthorization.recheck(request,platform,member));assertEquals(503,error.status);assertEquals("IDENTITY_UNAVAILABLE",error.code);}
 @Test void nullRevisionResultFailsAsUnavailable(){var error=assertThrows(DomainException.class,()->filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{when(identities.sessionState(actor)).thenReturn(null);DashboardReadAuthorization.recheck(request,platform,member);}));assertEquals(503,error.status);assertEquals("IDENTITY_UNAVAILABLE",error.code);}
}
