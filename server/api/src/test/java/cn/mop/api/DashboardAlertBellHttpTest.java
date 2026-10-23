package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardAlertBellReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Actual MVC/security filters with isolated repositories; not a live PostgreSQL or login test. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({"local_fixture", "isolated_alert_bell_http"})
@ContextConfiguration(classes = DashboardAlertBellHttpTest.Configuration.class)
class DashboardAlertBellHttpTest {
    private static final String ACTOR = DashboardAlertBellTest.ACTOR;
    private static final String TENANT = DashboardAlertBellTest.TENANT;
    private static final String PATH = "/api/v1/dashboard/alert-bell";

    @org.springframework.boot.test.context.TestConfiguration
    @Profile("isolated_alert_bell_http")
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        PlatformRepository platform() {
            return mock(PlatformRepository.class);
        }

        @Bean
        DashboardAlertBellReader reader() {
            return mock(DashboardAlertBellReader.class);
        }

        @Bean
        DashboardAlertBellController controller(PlatformRepository platform, DashboardAlertBellReader reader) {
            return new DashboardAlertBellController(platform, reader);
        }

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(objectMapper()));
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    PlatformRepository platform;

    @Autowired
    DashboardAlertBellReader reader;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(platform, reader);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true)));
        when(reader.read(any())).thenReturn(new DashboardAlertBellReader.Snapshot(UUID.fromString(TENANT),
                UUID.fromString(ACTOR), 7, Instant.parse("2026-10-23T14:00:00Z"), 0, List.of()));
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder request) {
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute("mop.tenant_id", TENANT);
        return request.session(session).with(user(ACTOR));
    }

    @Test
    void anonymousReadIsUnauthorized() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test
    void readHasNoStoreAndTruthfulZeroWithoutCsrfOrBusinessMutation() throws Exception {
        mvc.perform(scoped(get(PATH))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.filter.monitor_state").value("PROBLEM"))
                .andExpect(jsonPath("$.data.filter.handling_state").value("NEW"))
                .andExpect(jsonPath("$.data.writes_performed").value(false));
    }

    @Test
    void springRoleCannotOverrideCustomerMembership() throws Exception {
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, true)));
        mvc.perform(scoped(get(PATH)).with(user(ACTOR).roles("PADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(reader);
    }

    @Test
    void filterOrTenantOverrideIsRejected() throws Exception {
        mvc.perform(scoped(get(PATH).param("tenant_id", TENANT))).andExpect(status().isBadRequest());
        mvc.perform(scoped(get(PATH).param("monitor_state", "RECOVERED"))).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test
    void failedReaderCannotBecomeSuccessfulEmptyBell() throws Exception {
        when(reader.read(any())).thenThrow(new DomainException(503, "UPSTREAM_UNAVAILABLE", "synthetic outage"));
        mvc.perform(scoped(get(PATH))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void noMutationRouteExistsEvenWithCsrf() throws Exception {
        mvc.perform(scoped(post(PATH))).andExpect(status().isForbidden());
        mvc.perform(scoped(post(PATH)).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());
        mvc.perform(scoped(patch(PATH)).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(reader);
    }
}
