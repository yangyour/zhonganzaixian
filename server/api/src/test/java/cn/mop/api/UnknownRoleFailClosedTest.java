package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Defensive malformed-membership checks; no unknown role is inserted into the real database. */
class UnknownRoleFailClosedTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000003",TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    PlatformRepository repository;
    @BeforeEach void setup(){repository=mock(PlatformRepository.class);when(repository.user(ACTOR)).thenReturn(Optional.of(new FixtureUser(ACTOR,"虚构测试身份","padmin")));when(repository.tenant(TENANT)).thenReturn(new Tenant(TENANT,"虚构租户"));when(repository.allowedTenants(ACTOR)).thenReturn(List.of(new Tenant(TENANT,"虚构租户")));when(repository.metadata()).thenReturn(new RepositoryMetadata("remote_test","isolated",false,"isolated"));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void role(String role,boolean active){when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,active)));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/session");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-role-check");r.setAttribute(CsrfToken.class.getName(),new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","synthetic-token"));return r;}
    SessionService session(){return new SessionService(repository,new ObjectMapper(),mock(SecurityContextRepository.class),new HttpSessionCsrfTokenRepository(),new StaticListableBeanFactory().getBeanProvider(OidcClientInitiatedLogoutSuccessHandler.class));}
    @Test void unknownNullCaseChangedOrWhitespaceRolesHaveNoFallbackCapabilities(){for(String role:Arrays.asList(null,"","unknown","admin","VIEWER","viewer "))assertEquals(List.of(),Capabilities.forRole(role));}
    @Test void assetReadsFailBeforeObjectLookupForUnknownMembershipRoles(){
        var beans=new StaticListableBeanFactory();var controller=new PlatformController(repository,new ObjectMapper(),beans.getBeanProvider(TestOidcConfiguration.OidcDependencyProbe.class),beans.getBeanProvider(PhotoConfiguration.PhotoDependencyProbe.class),beans.getBeanProvider(AssetImportConfiguration.ImportDependencyProbe.class));
        for(String unsupported:Arrays.asList("unknown",null)){role(unsupported,true);assertEquals(403,assertThrows(DomainException.class,()->controller.asset(OTHER,request())).status);}verify(repository,never()).find(anyString(),anyString());
    }
    @Test void sessionDoesNotProjectUnsupportedOrInactiveMembershipAsUsable(){
        for(String unsupported:Arrays.asList("unknown",null,"viewer ")){role(unsupported,true);assertEquals(401,assertThrows(DomainException.class,()->session().session(request())).status);}
        role("viewer",false);assertEquals(401,assertThrows(DomainException.class,()->session().session(request())).status);
    }
    @Test void oidcSuccessCannotCreateAPlatformSessionWithAnUnsupportedMembership(){
        role("unknown",true);var authentication=mock(OAuth2AuthenticationToken.class);when(authentication.getName()).thenReturn(ACTOR);var request=new MockHttpServletRequest();
        assertEquals(401,assertThrows(DomainException.class,()->session().establishOidcSession(authentication,request,new MockHttpServletResponse())).status);assertNull(request.getSession(false));verify(repository,never()).audit(any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void unsupportedTargetCannotChangeCurrentTenantOrCreateAReplayRecord(){
        role("padmin",true);when(repository.membership(ACTOR,OTHER)).thenReturn(Optional.of(new Membership(ACTOR,OTHER,"unknown",8,true)));var request=request();request.setMethod("POST");request.setRequestURI("/api/v1/session/tenant");request.addHeader("Idempotency-Key","synthetic-switch");
        assertEquals(401,assertThrows(DomainException.class,()->session().switchTenant(new ObjectMapper().createObjectNode().put("tenant_id",OTHER),request)).status);assertEquals(TENANT,request.getSession().getAttribute("mop.tenant_id"));verify(repository,never()).idempotent(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
}
