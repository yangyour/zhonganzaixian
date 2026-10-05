package cn.mop.api;

import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectIdentitySecurityTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void passwordHashesHaveIndependentRandomSaltsAndNeverUsePlaintextFallback(){
        var codec=new ProjectPasswordCodec();String secret=" 演示口令 with spaces ";String first=codec.encode(secret),second=codec.encode(secret);
        assertThat(first).startsWith("{mop-argon2id-v1}$argon2id$").isNotEqualTo(second).doesNotContain(secret);
        assertThat(codec.matches(secret,first)).isTrue();assertThat(codec.matches(secret.strip(),first)).isFalse();
        assertThat(codec.matches(secret,"{noop}"+secret)).isFalse();assertThat(codec.matches(secret,secret)).isFalse();
    }
    @Test void unsetLengthPolicyDoesNotInventTheDeclinedFifteenCharacterRule(){
        assertThatCode(()->ProjectIdentityRepository.passwordInput("x")).doesNotThrowAnyException();
        assertThatThrownBy(()->ProjectIdentityRepository.passwordInput("")).isInstanceOf(cn.mop.domain.DomainException.class);
        assertThatThrownBy(()->ProjectIdentityRepository.passwordInput("字".repeat(400))).isInstanceOf(cn.mop.domain.DomainException.class);
    }
    @Test void sensitiveReplayUsesPrivateKeyedCanonicalFingerprints()throws Exception{
        var mapper=new ObjectMapper();var service=new ProjectIdentityService(mock(ProjectIdentityRepository.class),mapper,"01".repeat(32),false);
        var body=mapper.readTree("{\"new_password\":\"unit-secret\",\"nested\":{\"b\":2,\"a\":1}}");var reordered=mapper.readTree("{\"nested\":{\"a\":1,\"b\":2},\"new_password\":\"unit-secret\"}");
        assertThat(service.commandHash(body)).isEqualTo(service.commandHash(reordered)).matches("[0-9a-f]{64}");
        var other=new ProjectIdentityService(mock(ProjectIdentityRepository.class),mapper,"02".repeat(32),false);
        assertThat(other.commandHash(body)).isNotEqualTo(service.commandHash(body));
        assertThat(service.options()).containsEntry("local_enabled",true).containsEntry("sso_enabled",false).containsEntry("login_url",null);
    }
    @Test void passwordResetRevisionInvalidatesTheOldServerSession()throws Exception{
        var repository=mock(ProjectIdentityRepository.class);when(repository.sessionState("user")).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(2,false)));
        var service=new ProjectIdentityService(repository,new ObjectMapper(),"01".repeat(32),false);var filter=new ProjectSessionValidationFilter(service);
        var request=new MockHttpServletRequest("GET","/api/v1/assets");var session=new MockHttpSession();request.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ProjectIdentityService.LocalPrincipal("user",1),null,List.of()));
        var called=new AtomicBoolean();filter.doFilter(request,new MockHttpServletResponse(),(a,b)->{called.set(true);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();});
        assertThat(called).isTrue();assertThat(session.isInvalid()).isTrue();
    }
    @Test void unavailableIdentityStorageFailsClosedWithoutPassingTheRequest()throws Exception{
        var repository=mock(ProjectIdentityRepository.class);when(repository.sessionState("user")).thenThrow(new IllegalStateException("unit-private-diagnostic"));
        var filter=new ProjectSessionValidationFilter(new ProjectIdentityService(repository,new ObjectMapper(),"01".repeat(32),false));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ProjectIdentityService.LocalPrincipal("user",1),null,List.of()));
        var response=new MockHttpServletResponse();var called=new AtomicBoolean();filter.doFilter(new MockHttpServletRequest("GET","/api/v1/assets"),response,(a,b)->called.set(true));
        assertThat(called).isFalse();assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).doesNotContain("unit-private-diagnostic");assertThat(response.getHeader("X-Trace-Id")).isNotBlank();
    }
    @Test void configuredForcedChangeBlocksBusinessCallsButAllowsThePasswordEndpoint()throws Exception{
        var repository=mock(ProjectIdentityRepository.class);when(repository.sessionState("user")).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(1,true)));
        var filter=new ProjectSessionValidationFilter(new ProjectIdentityService(repository,new ObjectMapper(),"01".repeat(32),false));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ProjectIdentityService.LocalPrincipal("user",1),null,List.of()));
        var denied=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/v1/assets"),denied,(a,b)->{throw new AssertionError();});assertThat(denied.getStatus()).isEqualTo(403);
        var allowed=new AtomicBoolean();filter.doFilter(new MockHttpServletRequest("POST","/api/v1/account/password"),new MockHttpServletResponse(),(a,b)->allowed.set(true));assertThat(allowed).isTrue();
    }
    @Test void optionalUnifiedIdentityRequiresExplicitIssuerAndClient(){
        assertThatCode(()->TestOidcEndpointPolicy.validateUnified("https://identity.example.test/realms/company","mop-client","unit-secret")).doesNotThrowAnyException();
        for(String issuer:List.of("http://identity.example.test/realms/company","https://user:secret@identity.example.test","https://identity.example.test/path?token=unit-secret","https://identity.example.test/path/../other"))
            assertThatThrownBy(()->TestOidcEndpointPolicy.validateUnified(issuer,"mop-client","unit-secret")).isInstanceOf(IllegalStateException.class);
    }
}
