package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.dashboard.*;
import cn.mop.domain.dashboard.DashboardAvailabilitySnapshot.TrendWindow;
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
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_dashboard_http"})
@ContextConfiguration(classes=DashboardHttpSecurityTest.Configuration.class)
class DashboardHttpSecurityTest {
    private static final String ACTOR=DashboardControllerTest.ACTOR,TENANT=DashboardControllerTest.TENANT;
    @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_dashboard_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
        @Bean DashboardAvailabilityReader reader(){return mock(DashboardAvailabilityReader.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean DashboardController controller(PlatformRepository repository,DashboardAvailabilityReader reader){return new DashboardController(repository,reader);}
        @Bean
        cn.mop.domain.dashboard.DashboardPendingTicketsReader pendingReader() {
            return mock(cn.mop.domain.dashboard.DashboardPendingTicketsReader.class);
        }
        @Bean
        DashboardPendingTicketsController pendingController(PlatformRepository repository,
                cn.mop.domain.dashboard.DashboardPendingTicketsReader pendingReader) {
            return new DashboardPendingTicketsController(repository, pendingReader);
        }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
    }
    @Autowired WebApplicationContext context;
    @Autowired PlatformRepository repository;
    @Autowired DashboardAvailabilityReader reader;
    @Autowired
    cn.mop.domain.dashboard.DashboardPendingTicketsReader pendingReader;
    private MockMvc mvc;
    @BeforeEach void setup(){reset(repository,reader);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));when(reader.read(any(),any())).thenAnswer(c->DashboardControllerTest.snapshot(c.getArgument(0),c.getArgument(1)));}
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder){var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return builder.session(session).with(user(ACTOR));}
    @Test
    void pendingTicketFactsRequireSessionAndRemainNoStoreWithoutMutation() throws Exception {
        when(pendingReader.read(any())).thenReturn(new cn.mop.domain.dashboard.DashboardPendingTicketsReader.Snapshot(
                UUID.fromString(TENANT), UUID.fromString(ACTOR), 7, DashboardControllerTest.TIME,
                "REPORTER_ONLY", 0, 0, 0, 0, List.of()));
        mvc.perform(scoped(get("/api/v1/dashboard/pending-tickets")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.pending_tickets").value(0))
                .andExpect(jsonPath("$.data.overdue_sla").value(nullValue()))
                .andExpect(jsonPath("$.data.scope").value("REPORTER_ONLY"));
        mvc.perform(get("/api/v1/dashboard/pending-tickets")).andExpect(status().isUnauthorized());
        mvc.perform(scoped(post("/api/v1/dashboard/pending-tickets")).with(csrf().asHeader()))
                .andExpect(status().isMethodNotAllowed());
    }
    @Test void getNeedsNoCsrfAndPreservesNullsWithNoStore()throws Exception{
        mvc.perform(scoped(get("/api/v1/dashboard"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.trace_id").isString()).andExpect(jsonPath("$.data.tenant_id").value(TENANT)).andExpect(jsonPath("$.data.kpis[0].value").value(nullValue())).andExpect(jsonPath("$.data.panels[0].items").value(nullValue())).andExpect(jsonPath("$.data.metric_samples_loaded").value(false));
        verify(repository).membership(ACTOR,TENANT);verifyNoMoreInteractions(repository);
    }
    @Test void anonymousAndInactiveMembersDeniedBeforeRead()throws Exception{
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.empty());mvc.perform(scoped(get("/api/v1/dashboard"))).andExpect(status().isUnauthorized());verifyNoInteractions(reader);
    }
    @Test void authoritiesDoNotOverrideUnknownCurrentDatabaseRole()throws Exception{
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"unknown",7,true)));
        mvc.perform(scoped(get("/api/v1/dashboard")).with(user(ACTOR).roles("PADMIN"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTION_FORBIDDEN"));verifyNoInteractions(reader);
    }
    @Test void queryDuplicatesAndScopeOverridesAreRejected()throws Exception{
        mvc.perform(scoped(get("/api/v1/dashboard").param("window","HOURS_24","HOURS_24"))).andExpect(status().isBadRequest());
        for(String field:List.of("tenant_id","actor_id","from","to","layout"))mvc.perform(scoped(get("/api/v1/dashboard").param(field,"forged"))).andExpect(status().isBadRequest());verifyNoInteractions(reader);
    }
    @Test void databaseFailureIs503NotSuccessfulUnknownData()throws Exception{
        doThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","isolated dependency failure")).when(reader).read(any(),any());
        mvc.perform(scoped(get("/api/v1/dashboard"))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE")).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void dashboardHasNoMutationEvenWithCsrf()throws Exception{
        mvc.perform(scoped(post("/api/v1/dashboard"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(scoped(post("/api/v1/dashboard")).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());
        mvc.perform(scoped(patch("/api/v1/dashboard")).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());verifyNoInteractions(reader);
    }
}
