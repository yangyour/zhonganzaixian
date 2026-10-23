package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RoleMatrixReadAuthorizationTest {
 final String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002",other="79000000-0000-4000-8000-000000000003";
 final PlatformRepository platform=mock(PlatformRepository.class);final ProjectIdentityRepository accounts=mock(ProjectIdentityRepository.class);
 final ProjectIdentityController controller=new ProjectIdentityController(new ProjectIdentityService(accounts,new ObjectMapper(),"01".repeat(32),false),platform,mock(SessionService.class),new ObjectMapper());
 final Membership member=new Membership(actor,tenant,"padmin",7,true),target=new Membership(actor,other,"padmin",9,true);
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->actor);r.getSession().setAttribute("mop.tenant_id",tenant);when(platform.membership(actor,tenant)).thenReturn(Optional.of(member));when(platform.membership(actor,other)).thenReturn(Optional.of(target));return r;}
 @Test void currentTenantRevocationDuringReadRejectsResponse(){var r=request();when(accounts.roles(any())).thenAnswer(c->{when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",8,true)));return List.of();});assertEquals(401,assertThrows(DomainException.class,()->controller.roles(null,r)).status);}
 @Test void sameVersionCapabilityLossStillRejectsResponse(){var r=request();when(accounts.roles(any())).thenAnswer(c->{when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",7,true,List.of("roles:read"))));return List.of();});assertEquals(401,assertThrows(DomainException.class,()->controller.roles(null,r)).status);}
 @Test void sessionTenantChangeDuringReadRejectsResponse(){var r=request();when(accounts.roles(any())).thenAnswer(c->{r.getSession().setAttribute("mop.tenant_id",other);return List.of();});assertEquals(401,assertThrows(DomainException.class,()->controller.roles(null,r)).status);}
 @Test void explicitlyAuthorizedOtherTenantStillWorks(){var r=request();when(accounts.roles(any())).thenReturn(List.of());var result=(Map<?,?>)controller.roles(other,r);assertEquals(other,((Map<?,?>)result.get("data")).get("tenant_id"));verify(accounts).roles(argThat(a->a.tenant().equals(other)&&a.permissionVersion()==9));}
 @Test void targetRevocationHasUniform404(){var r=request();when(accounts.roles(any())).thenAnswer(c->{when(platform.membership(actor,other)).thenReturn(Optional.empty());return List.of();});assertEquals(404,assertThrows(DomainException.class,()->controller.roles(other,r)).status);}
 @Test void sourceRevocationAlsoRejectsExplicitOtherTenantRead(){var r=request();when(accounts.roles(any())).thenAnswer(c->{when(platform.membership(actor,tenant)).thenReturn(Optional.empty());return List.of();});assertEquals(401,assertThrows(DomainException.class,()->controller.roles(other,r)).status);}
 @Test void foreignRepositoryRowsFailClosed(){var r=request();when(accounts.roles(any())).thenReturn(List.of(Map.of("tenant_id",other)));assertEquals(503,assertThrows(DomainException.class,()->controller.roles(null,r)).status);}
 @Test void malformedTenantAndDuplicateQueryRejectedBeforeRead(){var r=request();assertEquals(400,assertThrows(DomainException.class,()->controller.roles("invalid",r)).status);r.addParameter("tenant_id",tenant,other);assertEquals(400,assertThrows(DomainException.class,()->controller.roles(tenant,r)).status);verifyNoInteractions(accounts);}
}
