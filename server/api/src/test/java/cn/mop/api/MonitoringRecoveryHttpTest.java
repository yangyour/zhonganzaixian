package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.PostgresMonitoringRecoveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real MVC/filter chain with repository substitutes; actual PG/source tests are recorded separately. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({"remote_test", "isolated_recovery_http"})
@ContextConfiguration(classes = MonitoringRecoveryHttpTest.Config.class)
class MonitoringRecoveryHttpTest {
    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String ACTOR = "10000000-0000-4000-8000-000000000002";
    static final String EVENT = "10000000-0000-4000-8000-000000000003";
    static final String ASSET = "10000000-0000-4000-8000-000000000004";
    static final String RELEASE = "10000000-0000-4000-8000-000000000005";
    static final String PATH = "/api/v1/events/" + EVENT + "/recovery-observations";
    static final String BODY = "{\"expected_version\":2,\"policy_release_id\":\"" + RELEASE + "\",\"policy_release_version\":1}";

    @org.springframework.boot.test.context.TestConfiguration
    @Profile("isolated_recovery_http")
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class, MonitoringRecoveryController.class})
    static class Config {
        @Bean
        PlatformRepository platform() { return mock(PlatformRepository.class); }
        @Bean
        PostgresMonitoringRecoveryRepository recovery() { return mock(PostgresMonitoringRecoveryRepository.class); }
        @Bean
        MonitoringRecoverySource source() { return mock(MonitoringRecoverySource.class); }
        @Bean
        SessionService sessions() { return mock(SessionService.class); }
        @Bean
        ObjectMapper mapper() { return new ObjectMapper(); }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    PlatformRepository platform;
    @Autowired
    PostgresMonitoringRecoveryRepository recovery;
    @Autowired
    MonitoringRecoverySource source;
    MockMvc mvc;

    @BeforeEach
    void prepare() {
        reset(platform, recovery, source);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 7, true)));
        when(platform.idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(6).run();
            return call.<Supplier<?>>getArgument(7).get();
        });
        when(recovery.inspect(any(), eq(EVENT))).thenAnswer(call -> new LinkedHashMap<>(Map.of(
            "event_id", EVENT, "asset_id", ASSET, "configuration_status", "CONFIG_PENDING")));
        when(recovery.observe(any(), any())).thenReturn(Map.of("event_id", EVENT, "applied", false, "status", "OBSERVING"));
    }

    @Test
    void anonymousAndMissingCsrfCannotReachObservationCommand() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(user(ACTOR)).sessionAttr("mop.tenant_id", TENANT)
            .contentType("application/json").header("Idempotency-Key", "recovery-test").content(BODY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        verify(recovery, never()).observe(any(), any());
    }

    @Test
    void customerAndReadOnlyInternalMemberCannotSubmit() throws Exception {
        for (Membership member : List.of(new Membership(ACTOR, TENANT, "viewer", 7, true),
                new Membership(ACTOR, TENANT, "msp", 7, true, List.of("tickets:read")))) {
            when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(member));
            mvc.perform(post(PATH).with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id", TENANT)
                .contentType("application/json").header("Idempotency-Key", "recovery-test").content(BODY))
                .andExpect(status().isForbidden());
        }
        verify(recovery, never()).observe(any(), any());
    }

    @Test
    void validRequestReauthorizesAndCarriesOnlyExactEventAndPolicyVersions() throws Exception {
        mvc.perform(post(PATH).with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id", TENANT)
            .contentType("application/json").header("Idempotency-Key", "recovery-test").content(BODY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OBSERVING"))
            .andExpect(header().string("Cache-Control", "no-store"));
        verify(recovery).authorizeCommand(argThat(a -> a.permissionVersion() == 7 && a.tenantId().equals(TENANT)), eq(EVENT));
        verify(recovery).observe(any(), argThat(c -> c.eventId().equals(EVENT) && c.expectedVersion() == 2
            && c.releaseId().equals(RELEASE) && c.releaseVersion() == 1 && c.commandSha256().matches("[0-9a-f]{64}")));
    }

    @Test
    void forgedPointsTimesExtraFieldsDuplicateKeysAndTrailingJsonAreRejected() throws Exception {
        for (String body : List.of(BODY.replace("\"expected_version\":2", "\"expected_version\":2,\"samples\":[]"),
                BODY.replace("\"expected_version\":2", "\"expected_version\":2,\"confirmed_at\":null"),
                BODY.replace("\"expected_version\":2", "\"expected_version\":2,\"expected_version\":3"),
                BODY + "{}", BODY.replace("\"expected_version\":2", "\"expected_version\":0"))) {
            mvc.perform(post(PATH).with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id", TENANT)
                .contentType("application/json").header("Idempotency-Key", "recovery-test").content(body))
                .andExpect(status().isBadRequest());
        }
        verify(recovery, never()).observe(any(), any());
    }

    @Test
    void unavailableSourceAndCrossTenantEventRetainTheirErrors() throws Exception {
        when(recovery.observe(any(), any())).thenThrow(new DomainException(503, "RECOVERY_SOURCE_NOT_FRESH", "unavailable"));
        mvc.perform(post(PATH).with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id", TENANT)
            .contentType("application/json").header("Idempotency-Key", "recovery-test").content(BODY))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("RECOVERY_SOURCE_NOT_FRESH"));
        when(recovery.inspect(any(), eq(EVENT))).thenThrow(new DomainException(404, "RESOURCE_NOT_FOUND", "missing"));
        mvc.perform(get(PATH).with(user(ACTOR)).sessionAttr("mop.tenant_id", TENANT)).andExpect(status().isNotFound());
    }

    @Test
    void originalReceiptRecoveryIsReadOnlyAndUnknownRemains404() throws Exception {
        String hash = "a".repeat(64);
        when(recovery.receipt(any(), eq(EVENT), eq(hash))).thenThrow(new DomainException(404, "RESOURCE_NOT_FOUND", "unknown"));
        mvc.perform(get(PATH + "/receipts/" + hash).with(user(ACTOR)).sessionAttr("mop.tenant_id", TENANT))
            .andExpect(status().isNotFound());
        verify(recovery, never()).observe(any(), any());
        verify(platform, never()).idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void inspectionDoesNotAccessExternalSourceAndRechecksPermissionVersion() throws Exception {
        mvc.perform(get(PATH).with(user(ACTOR)).sessionAttr("mop.tenant_id", TENANT))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.source_binding_available").value(false));
        verify(source, never()).read(any(), any(), any());
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 7, true)),
            Optional.of(new Membership(ACTOR, TENANT, "msp", 8, true)));
        mvc.perform(get(PATH).with(user(ACTOR)).sessionAttr("mop.tenant_id", TENANT)).andExpect(status().isUnauthorized());
    }
}
