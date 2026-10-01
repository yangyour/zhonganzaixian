package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Existing security filter and MVC envelopes with isolated dependencies, not remote OIDC evidence. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_configuration_diagnostic_http"})
@ContextConfiguration(classes=ConfigurationDiagnosticHttpSecurityTest.Configuration.class)
class ConfigurationDiagnosticHttpSecurityTest {
    private static final String ACTOR=ConfigurationDiagnosticControllerTest.ACTOR,TENANT=ConfigurationDiagnosticControllerTest.TENANT,KEY=ConfigurationDiagnosticControllerTest.KEY;
    @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_configuration_diagnostic_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
        @Bean ConfigurationDiagnosticReader reader(){return mock(ConfigurationDiagnosticReader.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean ConfigurationDiagnosticController controller(PlatformRepository repository,ConfigurationDiagnosticReader reader){return new ConfigurationDiagnosticController(repository,reader);}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
    }
    @Autowired WebApplicationContext context;
    @Autowired PlatformRepository repository;
    @Autowired ConfigurationDiagnosticReader reader;
    private MockMvc mvc;
    @BeforeEach void setup(){
        reset(repository,reader);mvc=webAppContextSetup(context).apply(springSecurity()).build();
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));
        when(reader.read(new ConfigurationDiagnosticReader.TrustedContext(ACTOR,TENANT,7),KEY)).thenReturn(ConfigurationDiagnosticControllerTest.diagnostic(KEY,TENANT));
    }
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder){
        var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return builder.session(session).with(user(ACTOR));
    }
    @Test void authenticatedReadUsesExplicitPendingDtoAndNoStoreWithoutCsrf()throws Exception{
        mvc.perform(scoped(get("/api/v1/settings/effective").param("key",KEY)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.key").value(KEY)).andExpect(jsonPath("$.data.context.tenant_id").value(TENANT))
            .andExpect(jsonPath("$.data.status").value("CONFIG_PENDING")).andExpect(jsonPath("$.data.effective_value").value(nullValue()))
            .andExpect(jsonPath("$.data.publication.availability").value("NOT_IMPLEMENTED"))
            .andExpect(jsonPath("$.data.recent_proposals.items[0].workflow_status").value("APPROVED"))
            .andExpect(jsonPath("$.data.recent_proposals.items[0].confirmation_status").value("UNVERIFIED"))
            .andExpect(jsonPath("$.data.recent_proposals.items[0].proposed_value").doesNotExist());
        verify(reader).read(new ConfigurationDiagnosticReader.TrustedContext(ACTOR,TENANT,7),KEY);
    }
    @Test void anonymousAndExpiredMembershipUseSessionExpiredEnvelope()throws Exception{
        mvc.perform(get("/api/v1/settings/effective").param("key",KEY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.empty());
        mvc.perform(scoped(get("/api/v1/settings/effective").param("key",KEY))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        verifyNoInteractions(reader);
    }
    @Test void browserAuthoritiesCannotUpgradeCurrentDatabaseRole()throws Exception{
        for(String role:List.of("viewer","msp","tadmin")){
            when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,8,true)));
            mvc.perform(scoped(get("/api/v1/settings/effective").param("key",KEY)).with(user(ACTOR).roles("PADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTION_FORBIDDEN"));
        }
        verifyNoInteractions(reader);
    }
    @Test void duplicateMissingIllegalAndScopeQueriesUseValidationEnvelope()throws Exception{
        mvc.perform(scoped(get("/api/v1/settings/effective"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(scoped(get("/api/v1/settings/effective").param("key",KEY,KEY))).andExpect(status().isBadRequest());
        mvc.perform(scoped(get("/api/v1/settings/effective").param("key","../secret"))).andExpect(status().isBadRequest());
        for(String field:List.of("tenant_id","actor_id","scope","object_id","unknown"))
            mvc.perform(scoped(get("/api/v1/settings/effective").param("key",KEY).param(field,"forged"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(reader);
    }
    @Test void unknownRegistryAndUnavailableAuthoritativeSourceKeep404And503()throws Exception{
        var trusted=new ConfigurationDiagnosticReader.TrustedContext(ACTOR,TENANT,7);
        doThrow(new DomainException(404,"OBJECT_NOT_FOUND","配置登记不存在或不可见")).when(reader).read(trusted,KEY);
        mvc.perform(scoped(get("/api/v1/settings/effective").param("key",KEY))).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));
        doThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","指定 PostgreSQL 配置诊断来源不可用，未返回替代结果")).when(reader).read(trusted,KEY);
        mvc.perform(scoped(get("/api/v1/settings/effective").param("key",KEY))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE")).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void postDoesNotIntroducePublicationOrAnotherWriteRoute()throws Exception{
        mvc.perform(scoped(post("/api/v1/settings/effective").param("key",KEY)).with(csrf().asHeader()))
            .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(reader);
    }
}
