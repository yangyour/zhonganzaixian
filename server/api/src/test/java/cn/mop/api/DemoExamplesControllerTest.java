package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;

class DemoExamplesControllerTest {
    private static final String DEMO = "11111111-1111-4111-8111-111111111111";
    private static final String REAL = "55000000-0000-4000-8000-000000000001";
    private static final String ACTOR = "22222222-2222-4222-8222-222222222222";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realTenantHasNoDemoDefaults() {
        var repository = repository(REAL, "padmin");
        var result =
                mapper.valueToTree(
                        controller(repository)
                                .read(
                                        "sla-scenarios",
                                        request(REAL),
                                        new MockHttpServletResponse()));
        assertFalse(result.path("data").path("available").asBoolean());
        assertTrue(result.path("data").path("input").isNull());
    }

    @Test
    void demoExamplesAreMarkedInputsAndNeverClaimBusinessOrProviderWrites() {
        var repository = repository(DEMO, "padmin");
        for (String page :
                List.of(
                        "sla-scenarios",
                        "sla-reports",
                        "operational-comparison",
                        "capacity-estimates",
                        "observation-window-diagnostics",
                        "threshold-diagnostics",
                        "contact-import-preview")) {
            var result =
                    mapper.valueToTree(
                                    controller(repository)
                                            .read(
                                                    page,
                                                    request(DEMO),
                                                    new MockHttpServletResponse()))
                            .path("data");
            assertTrue(result.path("available").asBoolean());
            assertTrue(result.path("input").isObject());
            assertEquals(DEMO, result.path("tenant_id").asText());
            assertEquals("EXPLICIT_DEMO_INPUT", result.path("source").asText());
            assertFalse(result.path("business_data_written").asBoolean());
            assertFalse(result.path("provider_accessed").asBoolean());
        }
    }

    @Test
    void revocationDuringReadRejectsTheExample() {
        var repository = repository(DEMO, "padmin");
        when(repository.membership(ACTOR, DEMO))
                .thenReturn(
                        Optional.of(new Membership(ACTOR, DEMO, "padmin", 1, true)),
                        Optional.empty());
        assertEquals(
                401,
                assertThrows(
                                DomainException.class,
                                () ->
                                        controller(repository)
                                                .read(
                                                        "capacity-estimates",
                                                        request(DEMO),
                                                        new MockHttpServletResponse()))
                        .status);
    }

    @Test
    void demoAllowlistDoesNotGrantPageCapabilitiesOrAcceptTenantOverrides() {
        var repository = repository(DEMO, "viewer");
        assertEquals(
                403,
                assertThrows(
                                DomainException.class,
                                () ->
                                        controller(repository)
                                                .read(
                                                        "capacity-estimates",
                                                        request(DEMO),
                                                        new MockHttpServletResponse()))
                        .status);
        var request = request(DEMO);
        request.setQueryString("tenant_id=" + REAL);
        assertEquals(
                404,
                assertThrows(
                                DomainException.class,
                                () ->
                                        controller(repository)
                                                .read(
                                                        "sla-scenarios",
                                                        request,
                                                        new MockHttpServletResponse()))
                        .status);
    }

    private DemoExamplesController controller(PlatformRepository repository) {
        return new DemoExamplesController(repository, new DemoChannelScope(DEMO), mapper);
    }

    private PlatformRepository repository(String tenant, String role) {
        var repository = mock(PlatformRepository.class);
        when(repository.membership(ACTOR, tenant))
                .thenReturn(Optional.of(new Membership(ACTOR, tenant, role, 1, true)));
        return repository;
    }

    private MockHttpServletRequest request(String tenant) {
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", tenant);
        return request;
    }
}
