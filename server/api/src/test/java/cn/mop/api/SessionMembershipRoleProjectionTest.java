package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Session projection only, with no external component or integration-success claim. */
class SessionMembershipRoleProjectionTest {
    private static final String ACTOR="55000000-0000-4000-8000-000000000003",TENANT="55000000-0000-4000-8000-000000000001";
    private static final Tenant SCOPE=new Tenant(TENANT,"虚构当前租户");
    @AfterEach void clearSecurityContext(){SecurityContextHolder.clearContext();}
    private PlatformRepository repository(String globalRole,String membershipRole,String mode){
        PlatformRepository repository=mock(PlatformRepository.class);
        when(repository.user(ACTOR)).thenReturn(Optional.of(new FixtureUser(ACTOR,"虚构用户",globalRole)));
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,membershipRole,8,true)));
        when(repository.tenant(TENANT)).thenReturn(SCOPE);when(repository.allowedTenants(ACTOR)).thenReturn(List.of(SCOPE));
        when(repository.metadata()).thenReturn(new RepositoryMetadata(mode,"unit_test",false,"unit_test"));return repository;
    }
    private SessionService service(PlatformRepository repository){return new SessionService(repository,new ObjectMapper(),mock(SecurityContextRepository.class),new HttpSessionCsrfTokenRepository(),new StaticListableBeanFactory().getBeanProvider(OidcClientInitiatedLogoutSuccessHandler.class));}
    private MockHttpServletRequest authenticated(){
        var request=new MockHttpServletRequest("GET","/api/v1/session");var session=new MockHttpSession();session.setAttribute("mop.tenant_id",TENANT);request.setSession(session);request.setUserPrincipal(()->ACTOR);request.setAttribute(CsrfToken.class.getName(),new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","unit-token"));return request;
    }
    @SuppressWarnings("unchecked") private static void assertScope(Object response,String expectedRole){
        Map<String,Object> data=(Map<String,Object>)response;FixtureUser user=(FixtureUser)data.get("user");assertEquals(ACTOR,user.id());assertEquals("虚构用户",user.displayName());assertEquals(expectedRole,user.role());assertEquals(Capabilities.forRole(expectedRole),data.get("capabilities"));assertEquals(8L,data.get("permission_version"));
        List<String> capabilities=(List<String>)data.get("capabilities");assertEquals(expectedRole.equals("padmin"),capabilities.contains("configurations:write"));assertEquals(expectedRole.equals("padmin"),capabilities.contains("audit:read"));assertEquals(expectedRole.equals("viewer"),capabilities.contains("tickets:report"));
    }
    @Test void currentTenantRoleControlsUserProjectionAndCapabilitiesInBothDirections(){
        assertScope(service(repository("padmin","viewer","remote_test")).session(authenticated()),"viewer");
        assertScope(service(repository("viewer","padmin","remote_test")).session(authenticated()),"padmin");
    }
    @Test void historicalFixtureLoginAlsoComputesTheInitialSessionFromMembership(){
        for(String[] roles:List.of(new String[]{"padmin","viewer"},new String[]{"viewer","padmin"})){
            PlatformRepository repository=repository(roles[0],roles[1],"local_fixture");var request=new MockHttpServletRequest("POST","/api/v1/fixture/session");request.addHeader("Idempotency-Key","unit-fixture-"+roles[1]);
            Object response=service(repository).fixtureLogin(new ObjectMapper().createObjectNode().put("user_id",ACTOR),request,new MockHttpServletResponse());assertScope(response,roles[1]);
            verify(repository,never()).users();
        }
    }
    @Test void selectedCapabilitiesAreNotExpandedFromTheBusinessRole() {
        var repository=repository("padmin","msp","remote_test");
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",8,true,List.of("tickets:read"))));
        var data=(Map<?,?>)service(repository).session(authenticated());
        assertEquals(List.of("tickets:read"),data.get("capabilities"));
    }
    @Test void emptyGrantsStillAllowSelfSessionWithoutRestoringAnyBusinessPermission() {
        var repository=repository("padmin","padmin","remote_test");
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",8,true,List.of())));
        var data=(Map<?,?>)service(repository).session(authenticated());assertEquals(List.of(),data.get("capabilities"));
    }
    @Test void everySessionReadUsesCurrentEffectiveGrantsAndVersion() {
        var repository=repository("padmin","msp","remote_test");var service=service(repository);
        assertTrue(((List<?>)((Map<?,?>)service.session(authenticated())).get("capabilities")).contains("assets:write"));
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",9,true,List.of("assets:read"))));
        var data=(Map<?,?>)service.session(authenticated());assertEquals(List.of("assets:read"),data.get("capabilities"));assertEquals(9L,data.get("permission_version"));
    }
    @Test void historicalFixtureProjectionAlsoRespectsSelectedGrants() {
        var repository=repository("padmin","msp","local_fixture");
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",8,true,List.of("assets:read"))));
        var request=new MockHttpServletRequest("POST","/api/v1/fixture/session");request.addHeader("Idempotency-Key","selected-grants-fixture");
        var data=(Map<?,?>)service(repository).fixtureLogin(new ObjectMapper().createObjectNode().put("user_id",ACTOR),request,new MockHttpServletResponse());
        assertEquals(List.of("assets:read"),data.get("capabilities"));
    }
    @Test void tenantSwitchProjectsOnlyTheTargetMembershipGrants() {
        String target="55000000-0000-4000-8000-000000000002";
        var repository=repository("padmin","padmin","remote_test");
        when(repository.membership(ACTOR,target)).thenReturn(Optional.of(new Membership(ACTOR,target,"msp",9,true,List.of("tickets:read"))));
        when(repository.tenant(target)).thenReturn(new Tenant(target,"目标租户"));
        var request=authenticated();request.setMethod("POST");request.setRequestURI("/api/v1/session/tenant");request.addHeader("Idempotency-Key","selected-grants-switch");
        var data=(Map<?,?>)service(repository).switchTenant(new ObjectMapper().createObjectNode().put("tenant_id",target),request);
        assertEquals(List.of("tickets:read"),data.get("capabilities"));assertEquals(target,request.getSession().getAttribute("mop.tenant_id"));
    }
    @Test void invalidPermissionVersionsCannotProduceAUsableSession() {
        var repository=repository("padmin","padmin","remote_test");
        for(long version:new long[]{-1,0,9_007_199_254_740_992L}) {
            when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",version,true,List.of("assets:read"))));
            assertEquals(401,assertThrows(DomainException.class,()->service(repository).session(authenticated())).status);
        }
    }

    @Test void emptyGrantsDoNotPreventExplicitLogout() {
        var repository=repository("padmin","padmin","remote_test");
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",8,true,List.of())));
        var request=authenticated();request.setMethod("POST");request.addHeader("Idempotency-Key","empty-grants-logout");
        var data=(Map<?,?>)service(repository).logout(request,new MockHttpServletResponse());
        assertEquals(true,data.get("logged_out"));assertNull(request.getSession(false));
    }

}
