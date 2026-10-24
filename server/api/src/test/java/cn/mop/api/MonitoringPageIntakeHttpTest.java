package cn.mop.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.EventSummary;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Real Spring Security/HTTP protocol with isolated repository doubles, not external integration evidence. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles("local_fixture")
@ContextConfiguration(classes = MonitoringPageIntakeHttpTest.Configuration.class)
class MonitoringPageIntakeHttpTest {

    static final String ACTOR = "79000000-0000-4000-8000-000000000001";
    static final String TENANT = "79000000-0000-4000-8000-000000000002";
    static final String ID = "79000000-0000-4000-8000-000000000003";
    static final String BASE = "/api/v1/monitoring-object-mappings/" + ID + "/page-intakes";
    static final String BODY =
        "{\"expected_version\":2,\"first_event_id\":\"40\",\"last_event_id\":\"99\",\"from_clock\":0,\"through_clock\":100,\"page_size\":2,\"expected_source_definition_sha256\":\"" +
        "a".repeat(64) +
        "\"}";

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ SecurityConfiguration.class, ApiExceptionHandler.class })
    static class Configuration implements WebMvcConfigurer {

        @Bean
        PlatformRepository platform() {
            return mock(PlatformRepository.class);
        }

        @Bean
        MonitoringObjectBindingRepository mappings() {
            return mock(MonitoringObjectBindingRepository.class);
        }

        @Bean
        ObjectMapper json() {
            return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        }

        @Bean
        MonitoringPageIntakeController controller(
            PlatformRepository platform,
            MonitoringObjectBindingRepository mappings
        ) {
            return new MonitoringPageIntakeController(
                platform,
                mappings,
                new MockEnvironment()
                    .withProperty("MOP_MONITORING_MAPPING_ENABLED", "true")
                    .withProperty("MOP_MONITORING_MAPPING_CAPTURE_ENABLED", "true")
                    .withProperty("MOP_MONITORING_MAPPING_PROBLEM_INTAKE_ENABLED", "true")
                    .withProperty("MOP_MONITORING_MAPPING_PAGE_INTAKE_ENABLED", "true")
            );
        }

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(json()));
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    PlatformRepository platform;

    @Autowired
    MonitoringObjectBindingRepository mappings;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(platform, mappings);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(
            Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true))
        );
        when(
            platform.idempotentAuthorized(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                eq(7L),
                any(),
                any()
            )
        ).thenAnswer(call -> {
            ((Runnable) call.getArgument(6)).run();
            return ((Supplier<?>) call.getArgument(7)).get();
        });
    }

    MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute("mop.tenant_id", TENANT);
        return session;
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(post(BASE).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(
            status().isUnauthorized()
        );
        mvc.perform(
            post(BASE).session(session()).with(user(ACTOR)).contentType(MediaType.APPLICATION_JSON).content(BODY)
        ).andExpect(status().isForbidden());
        verifyNoInteractions(mappings);
    }

    @Test
    void duplicateOrCallerBusinessFieldsAreRejected() throws Exception {
        for (String body : List.of(
            BODY.replace("\"expected_version\":2", "\"expected_version\":2,\"expected_version\":3"),
            BODY.replace("{", "{\"severity\":\"DISASTER\",")
        )) {
            mvc.perform(
                post(BASE)
                    .session(session())
                    .with(user(ACTOR))
                    .with(csrf().asHeader())
                    .header("Idempotency-Key", "unit-only")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(mappings);
    }

    @Test
    void foreignMappingIs404BeforeSourceConsumption() throws Exception {
        doThrow(new DomainException(404, "MONITORING_MAPPING_NOT_FOUND", "不存在"))
            .when(mappings)
            .authorize(any(), eq(UUID.fromString(ID)), eq(true));
        mvc.perform(
            post(BASE)
                .session(session())
                .with(user(ACTOR))
                .with(csrf().asHeader())
                .header("Idempotency-Key", "unit-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
        ).andExpect(status().isNotFound());
        verify(mappings, never()).intakePage(any(), any(), anyLong(), any(), any());
    }

    @Test
    void committedEmptyPageDoesNotClaimGlobalCheckpointOrHealthyWindow() throws Exception {
        when(mappings.intakePage(any(), any(), eq(2L), any(), any())).thenReturn(
            new cn.mop.domain.monitoring.MappedSourcePage.Receipt(
                UUID.fromString(TENANT),
                UUID.fromString(ID),
                2,
                new cn.mop.domain.monitoring.MappedSourcePage.Window("40", "99", 0, 100, 2),
                List.of(),
                null
            )
        );
        mvc.perform(
            post(BASE)
                .session(session())
                .with(user(ACTOR))
                .with(csrf().asHeader())
                .header("Idempotency-Key", "page-unit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.received_count").value(0))
            .andExpect(jsonPath("$.data.global_checkpoint_advanced").value(false))
            .andExpect(jsonPath("$.data.complete_window_claimed").value(false))
            .andExpect(jsonPath("$.data.recovery_confirmed").value(false));
    }

    @Test
    void foreignTenantReceiptFailsClosed() throws Exception {
        when(mappings.intakePage(any(), any(), eq(2L), any(), any())).thenReturn(
            new cn.mop.domain.monitoring.MappedSourcePage.Receipt(
                UUID.fromString(ACTOR),
                UUID.fromString(ID),
                2,
                new cn.mop.domain.monitoring.MappedSourcePage.Window("40", "99", 0, 100, 2),
                List.of(),
                null
            )
        );
        mvc.perform(
            post(BASE)
                .session(session())
                .with(user(ACTOR))
                .with(csrf().asHeader())
                .header("Idempotency-Key", "page-unit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
        ).andExpect(status().isServiceUnavailable());
    }

    @Test
    void invalidWindowAndUnboundedSizeNeverReachSource() throws Exception {
        for (String body : List.of(
            BODY.replace("\"page_size\":2", "\"page_size\":11"),
            BODY.replace("\"first_event_id\":\"40\"", "\"first_event_id\":\"040\""),
            BODY.replace("\"through_clock\":100", "\"through_clock\":-1")
        )) {
            mvc.perform(
                post(BASE)
                    .session(session())
                    .with(user(ACTOR))
                    .with(csrf().asHeader())
                    .header("Idempotency-Key", "page-unit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(mappings);
    }

    @Test
    void pageGateRequiresEveryUnderlyingGate() {
        var env = new MockEnvironment().withProperty("MOP_MONITORING_MAPPING_PAGE_INTAKE_ENABLED", "true");
        org.junit.jupiter.api.Assertions.assertFalse(MonitoringPageIntakeController.enabled(env));
        env.withProperty("MOP_MONITORING_MAPPING_ENABLED", "true")
            .withProperty("MOP_MONITORING_MAPPING_CAPTURE_ENABLED", "true")
            .withProperty("MOP_MONITORING_MAPPING_PROBLEM_INTAKE_ENABLED", "true");
        org.junit.jupiter.api.Assertions.assertTrue(MonitoringPageIntakeController.enabled(env));
    }
}
