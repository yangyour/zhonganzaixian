package cn.mop.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import cn.mop.domain.*;
import cn.mop.domain.reporting.ReportPublicationRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({ "local_fixture", "isolated_report_publication_http" })
@ContextConfiguration(classes = ReportPublicationHttpSecurityTest.Configuration.class)
class ReportPublicationHttpSecurityTest {

    static final String ACTOR = "79000000-0000-4000-8000-000000000001",
        TENANT = "79000000-0000-4000-8000-000000000002",
        ID = "79000000-0000-4000-8000-000000000003",
        BASE = "/api/v1/report-publications";

    @TestConfiguration
    @Profile("isolated_report_publication_http")
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ SecurityConfiguration.class, ApiExceptionHandler.class })
    static class Configuration implements WebMvcConfigurer {

        @Bean
        PlatformRepository repository() {
            return mock(PlatformRepository.class);
        }

        @Bean
        ReportPublicationRepository reports() {
            return mock(ReportPublicationRepository.class);
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper()
                .findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        }

        @Bean
        ReportPublicationController controller(PlatformRepository p, ReportPublicationRepository r, ObjectMapper m) {
            return new ReportPublicationController(p, r, m);
        }

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> c) {
            c.add(new MappingJackson2HttpMessageConverter(mapper()));
            c.add(new ByteArrayHttpMessageConverter());
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    PlatformRepository platform;

    @Autowired
    ReportPublicationRepository reports;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(platform, reports);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(
            Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true))
        );
    }

    MockHttpSession session() {
        var s = new MockHttpSession();
        s.setAttribute("mop.tenant_id", TENANT);
        return s;
    }

    @Test
    void unauthenticatedReadAndCsrfFreeWriteRejected() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(
            post(BASE + "/" + ID + "/actions")
                .session(session())
                .with(user(ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"RENDER\",\"expected_version\":1,\"reason\":null}")
        ).andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }

    @Test
    void duplicateJsonFieldsRejectedBeforeRepository() throws Exception {
        mvc.perform(
            post(BASE + "/" + ID + "/read")
                .session(session())
                .with(user(ACTOR))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version\":1,\"expected_version\":2}")
        ).andExpect(status().isBadRequest());
        verifyNoInteractions(reports);
    }

    @Test
    void foreignObjectRemains404() throws Exception {
        when(reports.get(any(), eq(ID))).thenThrow(new DomainException(404, "OBJECT_NOT_FOUND", "不存在"));
        mvc.perform(
            get(BASE + "/" + ID)
                .session(session())
                .with(user(ACTOR))
        )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));
    }

    @Test
    void emptyPageIsActualEmptyAndNeverAnExternalSend() throws Exception {
        when(reports.list(any(), eq(21), isNull(), isNull())).thenReturn(List.of());
        mvc.perform(get(BASE).session(session()).with(user(ACTOR)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.external_dispatch_enabled").value(false));
    }

    @Test
    void revisionRequiresCsrfAndRejectsCallerScope() throws Exception {
        String path = BASE + "/" + ID + "/revisions";
        String body =
            "{\"expected_version\":6,\"source_id\":\"" +
            ID +
            "\",\"expected_source_sha256\":\"" +
            "a".repeat(64) +
            "\",\"reason\":\"SYNTHETIC correction\"}";
        mvc.perform(
            post(path).session(session()).with(user(ACTOR)).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isForbidden());
        mvc.perform(
            post(path)
                .session(session())
                .with(user(ACTOR))
                .with(csrf())
                .header("Idempotency-Key", "revision-http")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body.replace("\"expected_version\":6", "\"expected_version\":6,\"tenant_id\":\"" + TENANT + "\"")
                )
        ).andExpect(status().isBadRequest());
        verifyNoInteractions(reports);
    }
}
