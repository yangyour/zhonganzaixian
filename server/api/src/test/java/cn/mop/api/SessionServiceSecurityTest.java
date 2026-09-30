package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionServiceSecurityTest {
    @Test void publicGetLogoutCannotInvalidateAnAuthenticatedSessionWithoutPendingLogout()throws Exception{
        var request=new MockHttpServletRequest("GET","/auth/logout");var session=new MockHttpSession();session.setAttribute("mop.tenant_id","tenant-a");
        request.setSession(session);request.setUserPrincipal(()->"platform-user-id");var response=new MockHttpServletResponse();
        var provider=new StaticListableBeanFactory().getBeanProvider(OidcClientInitiatedLogoutSuccessHandler.class);
        var service=new SessionService(mock(PlatformRepository.class),new ObjectMapper(),mock(SecurityContextRepository.class),new HttpSessionCsrfTokenRepository(),provider);
        service.providerLogout(request,response);
        assertThat(session.isInvalid()).isFalse();assertThat(session.getAttribute("mop.tenant_id")).isEqualTo("tenant-a");
        assertThat(response.getCookies()).isEmpty();assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }
    @Test void securityAuditOutageReturnsUnifiedUnavailableResponse()throws Exception{
        PlatformRepository repository=mock(PlatformRepository.class);
        doThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","test dependency failure")).when(repository).audit(any(),any(),anyString(),anyString(),any(),anyString(),anyString(),anyString());
        var request=new MockHttpServletRequest("POST","/api/v1/assets");request.setAttribute("mop.trace_id","trace-audit-outage");var response=new MockHttpServletResponse();
        ObjectMapper mapper=new ObjectMapper();SecurityConfiguration.writeError(mapper,repository,request,response,403,"CSRF_INVALID","无效令牌");
        var result=mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(503);assertThat(result.path("error").path("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(result.path("trace_id").asText()).isEqualTo("trace-audit-outage");
    }
}
