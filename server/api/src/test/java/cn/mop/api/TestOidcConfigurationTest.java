package cn.mop.api;

import cn.mop.domain.FixtureUser;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated mapping policy checks; real signature/state/nonce verification is covered by remote OIDC integration. */
class TestOidcConfigurationTest {
    private static final String ISSUER="http://127.0.0.1:18110/realms/mop-test";
    private OidcUserRequest request(String subject,String username){
        var client=ClientRegistration.withRegistrationId("mop").clientId("isolated-unit-client")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://127.0.0.1:5173/login/oauth2/code/mop")
            .scope("openid").authorizationUri(ISSUER+"/auth").tokenUri(ISSUER+"/token").jwkSetUri(ISSUER+"/certs").issuerUri(ISSUER).build();
        Instant now=Instant.now();
        var idToken=new OidcIdToken("isolated-unit-id-token",now,now.plusSeconds(60),Map.of("iss",ISSUER,"sub",subject,"preferred_username",username));
        var accessToken=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"isolated-unit-access-token",now,now.plusSeconds(60),Set.of("openid"));
        return new OidcUserRequest(client,accessToken,idToken);
    }
    @Test void mapsOnlyVerifiedIssuerAndImmutableSubject(){
        PlatformRepository repository=mock(PlatformRepository.class);FixtureUser user=new FixtureUser("platform-user-id","平台管理员","padmin");
        when(repository.resolveIdentity("test_oidc",ISSUER+"|immutable-sub")).thenReturn(Optional.of(user));
        when(repository.allowedTenants(user.id())).thenReturn(List.of(new Tenant("tenant-a","测试A")));
        var principal=new TestOidcConfiguration(repository).platformUserService().loadUser(request("immutable-sub","renamed-user"));
        assertThat(principal.getName()).isEqualTo(user.id());
        verify(repository).resolveIdentity("test_oidc",ISSUER+"|immutable-sub");
        verify(repository,never()).resolveIdentity("test_oidc","renamed-user");
    }
    @Test void aRecreatedSameUsernameCannotReuseAnotherSubjectsAuthorization(){
        PlatformRepository repository=mock(PlatformRepository.class);
        when(repository.resolveIdentity("test_oidc",ISSUER+"|new-sub")).thenReturn(Optional.empty());
        assertThatThrownBy(()->new TestOidcConfiguration(repository).platformUserService().loadUser(request("new-sub","mopfixture-padmin"))).isInstanceOf(OAuth2AuthenticationException.class);
        verify(repository,never()).resolveIdentity("test_oidc","mopfixture-padmin");
    }
    @Test void mappedIdentityWithoutActiveTenantIsDenied(){
        PlatformRepository repository=mock(PlatformRepository.class);FixtureUser user=new FixtureUser("platform-user-id","已撤权","padmin");
        when(repository.resolveIdentity("test_oidc",ISSUER+"|immutable-sub")).thenReturn(Optional.of(user));
        when(repository.allowedTenants(user.id())).thenReturn(List.of());
        assertThatThrownBy(()->new TestOidcConfiguration(repository).platformUserService().loadUser(request("immutable-sub","mopfixture-padmin"))).isInstanceOf(OAuth2AuthenticationException.class);
    }
    @Test void aDifferentRealmOrUnscopedClientFailsBeforeDiscovery(){
        var configuration=new TestOidcConfiguration(mock(PlatformRepository.class));
        assertThatThrownBy(()->configuration.oidcClients("https://other-provider.example/realms/production","mop-web","isolated-unit-secret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->configuration.oidcClients(ISSUER,"shared-client","isolated-unit-secret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->configuration.oidcClients(ISSUER,"mop-web"," ")).isInstanceOf(IllegalStateException.class);
    }
}
