package cn.mop.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import cn.mop.domain.*;
import cn.mop.persistence.NotificationControlStore;
import cn.mop.persistence.NotificationRequestCancellationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Actual MVC/security filters with isolated storage doubles; real transactions are independently tested on designated PG. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({ "local_fixture", "isolated_notification_cancellation_http" })
@ContextConfiguration(classes = NotificationCancellationHttpSecurityTest.Configuration.class)
class NotificationCancellationHttpSecurityTest {

    private static final String TENANT = "79000000-0000-4000-8000-000000000001";
    private static final String ACTOR = "79000000-0000-4000-8000-000000000002";
    private static final String ID = "79000000-0000-4000-8000-000000000003";
    private static final String COMMAND = "79000000-0000-4000-8000-000000000004";
    private static final String PATH = "/api/v1/notification-routing/requests/" + ID + "/cancel";
    private static final String BODY = "{\"expected_version\":0,\"reason\":\"SYNTHETIC cancellation\"}";

    @TestConfiguration
    @Profile("isolated_notification_cancellation_http")
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ SecurityConfiguration.class, ApiExceptionHandler.class })
    static class Configuration {

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        PlatformRepository platform() {
            return mock(PlatformRepository.class);
        }

        @Bean
        NotificationRequestCancellationService cancellations() {
            return mock(NotificationRequestCancellationService.class);
        }

        @Bean
        NotificationCancellationController controller(
            PlatformRepository platform,
            NotificationRequestCancellationService cancellations
        ) {
            return new NotificationCancellationController(platform, cancellations, true);
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    PlatformRepository platform;

    @Autowired
    NotificationRequestCancellationService cancellations;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(platform, cancellations);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(
            Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true))
        );
    }

    private MockHttpServletRequestBuilder scoped() {
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute("mop.tenant_id", TENANT);
        return post(PATH)
            .session(session)
            .with(user(ACTOR))
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", COMMAND);
    }

    private NotificationControlStore.Entry receipt() throws Exception {
        var state = new ObjectMapper().readTree(
            "{\"status\":\"CANCELLED\",\"cancellation_reason\":\"SYNTHETIC cancellation\",\"external_dispatch_enabled\":false}"
        );
        return new NotificationControlStore.Entry(
            NotificationControlStore.Kind.REQUEST,
            UUID.fromString(ID),
            1,
            UUID.fromString(COMMAND),
            state,
            Instant.parse("2026-10-24T12:00:00Z")
        );
    }

    @Test
    void authenticationAndCsrfAreMandatoryBeforeTheCommand() throws Exception {
        mvc.perform(
            post(PATH).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(BODY)
        ).andExpect(status().isUnauthorized());
        mvc.perform(scoped().content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(cancellations);
    }

    @Test
    void strictBodyScopeVersionAndDuplicateIdempotencyHeaderFailBeforeStorage() throws Exception {
        for (String body : List.of(
            BODY.replace(":0", ":-1"),
            BODY.replace(":0", ":0.5"),
            BODY.replace("SYNTHETIC cancellation", " "),
            BODY.replace("\"reason\"", "\"tenant_id\":\"" + TENANT + "\",\"reason\"")
        )) {
            mvc.perform(scoped().with(csrf().asHeader()).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(
            scoped().with(csrf().asHeader()).header("Idempotency-Key", COMMAND).content(BODY)
        ).andExpect(status().isBadRequest());
        mvc.perform(scoped().with(csrf().asHeader()).queryParam("tenant_id", TENANT).content(BODY)).andExpect(
            status().isBadRequest()
        );
        verifyNoInteractions(cancellations);
    }

    @Test
    void customerCannotCancelInternalRoutingRequests() throws Exception {
        when(platform.membership(ACTOR, TENANT)).thenReturn(
            Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, true))
        );
        mvc.perform(scoped().with(csrf().asHeader()).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(cancellations);
    }

    @Test
    void exactOriginalReceiptKeepsCancelledStateAndExpectedVersion() throws Exception {
        when(
            cancellations.cancel(
                any(),
                eq(UUID.fromString(ID)),
                eq(0L),
                eq(UUID.fromString(COMMAND)),
                eq("SYNTHETIC cancellation")
            )
        ).thenReturn(receipt());
        mvc.perform(scoped().with(csrf().asHeader()).content(BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(ID))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.state.status").value("CANCELLED"));
        verify(platform, times(2)).membership(ACTOR, TENANT);
    }

    @Test
    void foreignObjectAndRevocationDoNotReleasePrivateCancellationData() throws Exception {
        when(cancellations.cancel(any(), any(), anyLong(), any(), any())).thenThrow(
            new DomainException(404, "NOT_FOUND", "不存在")
        );
        mvc.perform(scoped().with(csrf().asHeader()).content(BODY)).andExpect(status().isNotFound());
        doAnswer(call -> {
            when(platform.membership(ACTOR, TENANT)).thenReturn(
                Optional.of(new Membership(ACTOR, TENANT, "padmin", 8, true))
            );
            return receipt();
        })
            .when(cancellations)
            .cancel(any(), any(), anyLong(), any(), any());
        mvc.perform(scoped().with(csrf().asHeader()).content(BODY)).andExpect(status().isUnauthorized());
    }
}
