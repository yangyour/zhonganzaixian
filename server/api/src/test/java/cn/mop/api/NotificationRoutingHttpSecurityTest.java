package cn.mop.api;

import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.NotificationControlStore;
import cn.mop.persistence.NotificationRoutingService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real MVC/security filters with isolated repository doubles, not designated PG or provider integration. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles("local_fixture")
@ContextConfiguration(classes = NotificationRoutingHttpSecurityTest.Configuration.class)
class NotificationRoutingHttpSecurityTest {
    private static final String TENANT = "aaaaaaaa-1111-4111-8111-111111111111";
    private static final String ACTOR = "aaaaaaaa-2222-4222-8222-222222222222";
    private static final String COMMAND = "aaaaaaaa-3333-4333-8333-333333333333";
    private static final String BODY = "{\"expected_version\":-1,\"policy\":{\"published\":false,\"resource_pool_id\":null,\"matrix\":null,\"aggregate_seconds\":null,\"precedence\":null,\"retry_budget\":null,\"retry_delay_seconds\":null}}";

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean
        PlatformRepository platform() { return mock(PlatformRepository.class); }
        @Bean
        NotificationControlStore store() { return mock(NotificationControlStore.class); }
        @Bean
        NotificationRoutingService service() { return mock(NotificationRoutingService.class); }
        @Bean
        ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean
        NotificationRoutingController controller(PlatformRepository platform, NotificationControlStore store, NotificationRoutingService service) {
            return new NotificationRoutingController(platform, store, service, true);
        }
        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(mapper()));
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    PlatformRepository platform;
    @Autowired
    NotificationControlStore store;
    @Autowired
    NotificationRoutingService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(platform, store, service);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true)));
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder request) {
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute("mop.tenant_id", TENANT);
        return request.session(session).with(user(ACTOR));
    }

    @Test
    void anonymousReadAndMissingCsrfCannotReachStorage() throws Exception {
        mvc.perform(get("/api/v1/notification-routing/policy")).andExpect(status().isUnauthorized());
        mvc.perform(scoped(post("/api/v1/notification-routing/policy").contentType("application/json").content(BODY)
                .header("Idempotency-Key", COMMAND))).andExpect(status().isForbidden());
        verifyNoInteractions(service, store);
    }

    @Test
    void scopedWriteRequiresExactCommandAndReturnsImmutableReceipt() throws Exception {
        var entry = new NotificationControlStore.Entry(NotificationControlStore.Kind.POLICY, UUID.randomUUID(), 0,
                UUID.fromString(COMMAND), new ObjectMapper().createObjectNode().put("published", false), Instant.parse("2026-10-23T12:00:00Z"));
        when(service.savePolicy(any(), eq(-1L), eq(UUID.fromString(COMMAND)), any())).thenReturn(entry);
        mvc.perform(scoped(post("/api/v1/notification-routing/policy").contentType("application/json").content(BODY)
                        .header("Idempotency-Key", COMMAND)).with(csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.command_id").value(COMMAND)).andExpect(jsonPath("$.data.version").value(0));
        verify(service).savePolicy(argThat(access -> access.tenantId().toString().equals(TENANT)
                && access.actorId().toString().equals(ACTOR) && access.permissionVersion() == 7), eq(-1L), eq(UUID.fromString(COMMAND)), any());
    }

    @Test
    void viewerCannotReadInternalQueueOrPublishPolicy() throws Exception {
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, true)));
        mvc.perform(scoped(get("/api/v1/notification-routing/entries?kind=ROUTE"))).andExpect(status().isForbidden());
        mvc.perform(scoped(post("/api/v1/notification-routing/policy").contentType("application/json").content(BODY)
                .header("Idempotency-Key", COMMAND)).with(csrf().asHeader())).andExpect(status().isForbidden());
        verifyNoInteractions(service, store);
    }

    @Test
    void tenantOverrideAndUnexpectedBodyFieldsAreRejected() throws Exception {
        mvc.perform(scoped(get("/api/v1/notification-routing/entries?kind=ROUTE&tenant_id=" + TENANT)))
                .andExpect(status().isBadRequest());
        mvc.perform(scoped(post("/api/v1/notification-routing/policy").contentType("application/json")
                .content("{\"expected_version\":-1,\"policy\":{},\"tenant_id\":\"" + TENANT + "\"}")
                .header("Idempotency-Key", COMMAND)).with(csrf().asHeader())).andExpect(status().isBadRequest());
        verifyNoInteractions(service, store);
    }

    @Test
    void missingOrMalformedCommandIdentityIsRejected() throws Exception {
        for (String key : List.of("", "unretained-request", "a".repeat(200))) {
            mvc.perform(scoped(post("/api/v1/notification-routing/policy").contentType("application/json").content(BODY)
                    .header("Idempotency-Key", key)).with(csrf().asHeader())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service, store);
    }

    @Test
    void ownerPublicationCannotChooseAnotherReceiver() throws Exception {
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, true)));
        mvc.perform(scoped(post("/api/v1/my/notification-preferences/publication").contentType("application/json")
                .content("{\"expected_version\":-1,\"draft_id\":\"" + COMMAND + "\",\"draft_version\":1,\"published\":true,\"owner_id\":\"" + COMMAND + "\"}")
                .header("Idempotency-Key", COMMAND)).with(csrf().asHeader())).andExpect(status().isBadRequest());
        verifyNoInteractions(service, store);
    }
}
