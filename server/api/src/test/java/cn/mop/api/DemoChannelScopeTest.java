package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.PostgresEmailRepository;
import cn.mop.persistence.PostgresWeComRepository;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

class DemoChannelScopeTest {
    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String ACTOR = "22222222-2222-4222-8222-222222222222";
    private static final String REAL = "55000000-0000-4000-8000-000000000001";

    @Test
    void explicitScopeCannotContainProtectedOrInvalidTenants() {
        for (String value :
                List.of(
                        REAL,
                        "55000000-0000-4000-8000-000000000002",
                        "bad",
                        TENANT + ",",
                        TENANT + "," + TENANT)) {
            assertThrows(IllegalArgumentException.class, () -> new DemoChannelScope(value));
        }
        assertFalse(new DemoChannelScope("").contains(TENANT));
    }

    @Test
    void statusIsExplicitlyDisabledAndDoesNotInventProviderSuccess() {
        var scope = new DemoChannelScope(TENANT);
        for (boolean email : List.of(true, false)) {
            var status = scope.status(TENANT, email);
            assertEquals(false, status.get("enabled"));
            assertEquals(false, status.get("secret_configured"));
            assertEquals(false, status.get("dispatch_enabled"));
            assertEquals(TENANT, status.get("tenant_id"));
            assertEquals("DEMO_DISABLED_CONFIGURATION", status.get("source"));
        }
        assertThrows(DomainException.class, () -> scope.status(REAL, true));
    }

    @Test
    void onlyInactiveSyntheticBindingsAreAccepted() {
        var scope = new DemoChannelScope(TENANT);
        scope.validateBinding(TENANT, false, "demo@example.invalid", true);
        scope.validateBinding(TENANT, false, "DEMO_user", false);
        assertThrows(
                DomainException.class,
                () -> scope.validateBinding(TENANT, true, "demo@example.invalid", true));
        assertThrows(
                DomainException.class,
                () -> scope.validateBinding(TENANT, false, "real@example.com", true));
        assertThrows(
                DomainException.class,
                () -> scope.validateBinding(TENANT, false, "real_user", false));
    }

    @Test
    void emailOutboundAndConnectionChecksStopBeforeProviderOrRepository() {
        var platform = platform();
        var repository = mock(PostgresEmailRepository.class);
        var service = mock(EmailService.class);
        when(service.tenant()).thenReturn(REAL);
        var controller = new EmailController(service, repository, platform);
        controller.demoChannels = new DemoChannelScope(TENANT);
        assertEquals(
                "DEMO_CHANNEL_DISABLED",
                assertThrows(
                                DomainException.class,
                                () -> controller.check(request("/email/check", "{}")))
                        .code);
        assertEquals(
                "DEMO_CHANNEL_DISABLED",
                assertThrows(
                                DomainException.class,
                                () ->
                                        controller.test(
                                                request(
                                                        "/email/test-messages",
                                                        "{\"expected_version\":0}"),
                                                new MockHttpServletResponse()))
                        .code);
        assertEquals(
                "DEMO_CHANNEL_DISABLED",
                assertThrows(
                                DomainException.class,
                                () ->
                                        controller.notification(
                                                ACTOR,
                                                request(
                                                        "/my/notifications/" + ACTOR + "/email",
                                                        "{\"expected_version\":0}"),
                                                new MockHttpServletResponse()))
                        .code);
        verifyNoInteractions(repository);
        verify(service, never()).check();
        verify(service, never()).requireDispatch();
    }

    @Test
    void wecomOutboundAndConnectionChecksStopBeforeProviderOrRepository() {
        var platform = platform();
        var repository = mock(PostgresWeComRepository.class);
        var service = mock(WeComService.class);
        when(service.tenant()).thenReturn(REAL);
        var controller =
                new WeComController(
                        service,
                        repository,
                        platform,
                        mock(ProjectIdentityService.class),
                        mock(SessionService.class));
        controller.demoChannels = new DemoChannelScope(TENANT);
        assertEquals(
                "DEMO_CHANNEL_DISABLED",
                assertThrows(
                                DomainException.class,
                                () -> controller.check(request("/wecom/check", "{}")))
                        .code);
        assertEquals(
                "DEMO_CHANNEL_DISABLED",
                assertThrows(
                                DomainException.class,
                                () ->
                                        controller.test(
                                                request(
                                                        "/wecom/test-messages",
                                                        "{\"expected_version\":0}"),
                                                new MockHttpServletResponse()))
                        .code);
        assertEquals(
                "DEMO_CHANNEL_DISABLED",
                assertThrows(
                                DomainException.class,
                                () ->
                                        controller.notification(
                                                ACTOR,
                                                request(
                                                        "/my/notifications/" + ACTOR + "/wecom",
                                                        "{\"expected_version\":0}"),
                                                new MockHttpServletResponse()))
                        .code);
        verifyNoInteractions(repository);
        verify(service, never()).check();
        verify(service, never()).verifyMember(anyString());
    }

    @Test
    void demoReadsUseOnlyIndependentProviderKeys() {
        var platform = platform();
        var emailRepo = mock(PostgresEmailRepository.class);
        var emailService = mock(EmailService.class);
        when(emailService.tenant()).thenReturn(REAL);
        var email = new EmailController(emailService, emailRepo, platform);
        var scope = new DemoChannelScope(TENANT);
        email.demoChannels = scope;
        email.bindings(request("/email/bindings", ""));
        verify(emailRepo)
                .bindings(argThat(a -> a.tenantId().equals(TENANT)), eq(scope.sender(TENANT)));
        verify(emailService, never()).sender();
        var wecomRepo = mock(PostgresWeComRepository.class);
        var wecomService = mock(WeComService.class);
        when(wecomService.tenant()).thenReturn(REAL);
        var wecom =
                new WeComController(
                        wecomService,
                        wecomRepo,
                        platform,
                        mock(ProjectIdentityService.class),
                        mock(SessionService.class));
        wecom.demoChannels = scope;
        wecom.bindings(request("/wecom/bindings", ""));
        verify(wecomRepo)
                .bindings(argThat(a -> a.tenantId().equals(TENANT)), eq(scope.corp(TENANT)));
        verify(wecomService, never()).corp();
    }

    private PlatformRepository platform() {
        var platform = mock(PlatformRepository.class);
        when(platform.membership(ACTOR, TENANT))
                .thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 1, true)));
        return platform;
    }

    private MockHttpServletRequest request(String path, String body) {
        var request = new MockHttpServletRequest("POST", "/api/v1" + path);
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", TENANT);
        request.setAttribute("mop.trace_id", "demo-test");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
