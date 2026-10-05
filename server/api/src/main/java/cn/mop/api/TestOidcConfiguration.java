package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Authorization code, state, nonce, signature and issuer validation are owned by Spring Security. */
@Configuration @Profile("remote_test")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="MOP_OIDC_ENABLED",havingValue="true")
public class TestOidcConfiguration {
    private final PlatformRepository repository;
    private final org.springframework.beans.factory.ObjectProvider<ProjectIdentityService> projectIdentity;
    public TestOidcConfiguration(PlatformRepository repository){this(repository,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public TestOidcConfiguration(PlatformRepository repository,org.springframework.beans.factory.ObjectProvider<ProjectIdentityService> projectIdentity){this.repository=repository;this.projectIdentity=projectIdentity;}
    @Bean ClientRegistrationRepository oidcClients(
        @Value("${MOP_OIDC_ISSUER}") String issuer,@Value("${MOP_OIDC_CLIENT_ID}") String client,
        @Value("${MOP_OIDC_CLIENT_SECRET}") String secret,
        @Value("${MOP_TEST_PUBLIC_ORIGIN:}") String publicOrigin){
        if(projectIdentity!=null&&projectIdentity.getIfAvailable()!=null)TestOidcEndpointPolicy.validateUnified(issuer,client,secret);
        else TestOidcEndpointPolicy.validate(issuer,client,secret,publicOrigin);
        return new InMemoryClientRegistrationRepository(ClientRegistrations.fromIssuerLocation(issuer)
            .registrationId("mop").clientId(client).clientSecret(secret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .scope("openid","profile").redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").clientName("MOP 统一身份认证").build());
    }
    @Bean OidcClientInitiatedLogoutSuccessHandler oidcLogout(ClientRegistrationRepository clients){
        var handler=new OidcClientInitiatedLogoutSuccessHandler(clients);
        handler.setPostLogoutRedirectUri("{baseUrl}/login");handler.setDefaultTargetUrl("/login");return handler;
    }
    @Bean OidcDependencyProbe oidcDependencyProbe(ClientRegistrationRepository clients,ObjectMapper mapper){
        return new OidcDependencyProbe(clients.findByRegistrationId("mop").getProviderDetails().getIssuerUri(),mapper);
    }
    public static final class OidcDependencyProbe {
        private final String issuer;
        private final ObjectMapper mapper;
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        public OidcDependencyProbe(String issuer,ObjectMapper mapper){this.issuer=issuer;this.mapper=mapper;}
        public boolean ready(){
            try{
                var request=HttpRequest.newBuilder(URI.create(issuer+"/.well-known/openid-configuration")).timeout(Duration.ofSeconds(3)).header("Accept","application/json").GET().build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                return response.statusCode()==200&&response.body().length()<262144&&issuer.equals(mapper.readTree(response.body()).path("issuer").asText());
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
            catch(Exception unavailable){return false;}
        }
    }
    OAuth2UserService<OidcUserRequest,OidcUser> platformUserService(){
        OidcUserService delegate=new OidcUserService();
        return request->{
            OidcUser validated=delegate.loadUser(request);
            // Only the verified ID token supplies the identity key; request bodies cannot select a user.
            String subject=validated.getIdToken().getSubject();
            if(subject==null||subject.isBlank())throw unmapped();
            String identityKey=validated.getIdToken().getIssuer().toExternalForm()+"|"+subject;
            try{
                if(projectIdentity!=null&&projectIdentity.getIfAvailable()!=null){
                    var bound=projectIdentity.getIfAvailable().repository().externalIdentity(validated.getIdToken().getIssuer().toExternalForm(),subject).orElseThrow(TestOidcConfiguration::unmapped);
                    if(repository.allowedTenants(bound.userId()).isEmpty())throw unmapped();
                    return new ProjectOidcUser(bound.userId(),bound.securityVersion(),validated);
                }
                var user=repository.resolveIdentity("test_oidc",identityKey).orElseThrow(TestOidcConfiguration::unmapped);
                if(repository.allowedTenants(user.id()).isEmpty())throw unmapped();
                return new PlatformOidcUser(user.id(),validated);
            }catch(OAuth2AuthenticationException denied){throw denied;}
            catch(RuntimeException unavailable){throw new OAuth2AuthenticationException(new OAuth2Error("identity_mapping_unavailable"),"Project identity dependency unavailable");}
        };
    }
    private static OAuth2AuthenticationException unmapped(){return new OAuth2AuthenticationException(new OAuth2Error("identity_not_mapped"),"Identity has no active project membership");}
    private record ProjectOidcUser(String userId,long securityVersion,OidcUser delegate) implements OidcUser,ProjectSessionIdentity {
        public String getName(){return userId;}
        public String identitySource(){return "oidc";}
        public Map<String,Object> getClaims(){return delegate.getClaims();}
        public Map<String,Object> getAttributes(){return delegate.getAttributes();}
        public Collection<? extends GrantedAuthority> getAuthorities(){return delegate.getAuthorities();}
        public OidcUserInfo getUserInfo(){return delegate.getUserInfo();}
        public OidcIdToken getIdToken(){return delegate.getIdToken();}
    }
    private record PlatformOidcUser(String userId,OidcUser delegate) implements OidcUser,Serializable {
        public String getName(){return userId;}
        public Map<String,Object> getClaims(){return delegate.getClaims();}
        public Map<String,Object> getAttributes(){return delegate.getAttributes();}
        public Collection<? extends GrantedAuthority> getAuthorities(){return delegate.getAuthorities();}
        public OidcUserInfo getUserInfo(){return delegate.getUserInfo();}
        public OidcIdToken getIdToken(){return delegate.getIdToken();}
    }
}
