package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.change.*;
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

/** Isolated MVC/filter checks with mocked repositories; not PG/OIDC/monitoring integration. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_approval_http"})
@ContextConfiguration(classes=ChangeNoticeApprovalHttpSecurityTest.Configuration.class)
class ChangeNoticeApprovalHttpSecurityTest {
    private static final String ACTOR=ChangeNoticeApprovalControllerTest.ACTOR,TENANT=ChangeNoticeApprovalControllerTest.TENANT;
    @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_approval_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
        @Bean ChangeNoticeApprovalRepository reader(){return mock(ChangeNoticeApprovalRepository.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean ChangeNoticeApprovalController controller(PlatformRepository repository,ChangeNoticeApprovalRepository reader){return new ChangeNoticeApprovalController(repository,reader);}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
    }
    @Autowired WebApplicationContext context;
    @Autowired PlatformRepository repository;
    @Autowired ChangeNoticeApprovalRepository reader;
    private MockMvc mvc;
    @BeforeEach void setup(){reset(repository,reader);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));when(reader.view(any(),any(),anyLong())).thenReturn(new ChangeNoticeApprovalRepository.View(ChangeNoticeApprovalControllerTest.snapshot(),List.of(ChangeNoticeApproval.Action.SUBMIT))); when(reader.transition(any(),any(),anyLong(),anyLong(),any(),any())).thenReturn(ChangeNoticeApprovalControllerTest.snapshot()); when(repository.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(c->{((Runnable)c.getArgument(6)).run(); return ((java.util.function.Supplier<?>)c.getArgument(7)).get();});}
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder){var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return builder.session(session).with(user(ACTOR));}
    static final String BASE="/api/v1/change-notice-drafts/"+ChangeNoticeApprovalControllerTest.ID+"/revisions/4/approval";
    static final String BODY="{\"expected_version\":1,\"action\":\"SUBMIT\",\"note\":null}";
    @Test void csrfRequiredBeforeMutation() throws Exception {
        mvc.perform(scoped(post(BASE+"/actions")).contentType("application/json").content(BODY).header("Idempotency-Key","test"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        verifyNoInteractions(reader);
        mvc.perform(scoped(post(BASE+"/actions")).with(csrf().asHeader()).contentType("application/json").content(BODY).header("Idempotency-Key","test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.effective").value(false)).andExpect(jsonPath("$.data.automatic_p3_decision").value(nullValue()));
        verify(reader).authorizeCurrentAccess(any(),eq(ChangeNoticeApprovalControllerTest.ID),eq(4L),eq(ChangeNoticeApproval.Action.SUBMIT));
    }
    @Test void getUsesSessionAndNeverActivates() throws Exception {
        mvc.perform(scoped(get(BASE))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.draft_revision").value(4)).andExpect(jsonPath("$.data.device_execution_allowed").value(false));
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }
    @Test void malformedBodyAndForgedScopeFailSanitized() throws Exception {
        for(String body:List.of("{}{}", "{\"expected_version\":1,\"expected_version\":2}", BODY.replace("null","null,\"tenant_id\":\"SECRET\"")))
            mvc.perform(scoped(post(BASE+"/actions")).with(csrf().asHeader()).contentType("application/json").content(body).header("Idempotency-Key","test"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
        verifyNoInteractions(reader);
    }
    @Test void databaseAndUnexpectedErrorsDoNotLeakDetails() throws Exception {
        when(reader.view(any(),any(),anyLong())).thenThrow(new IllegalStateException("SECRET JDBC SQL"));
        mvc.perform(scoped(get(BASE))).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
    }
    @Test void deniedCurrentCapabilityOverridesSessionAuthority() throws Exception {
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));
        mvc.perform(scoped(get(BASE)).with(user(ACTOR).roles("PADMIN"))).andExpect(status().isForbidden());verifyNoInteractions(reader);
    }
}
