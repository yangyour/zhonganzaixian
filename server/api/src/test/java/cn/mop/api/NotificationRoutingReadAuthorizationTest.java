package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.NotificationControlStore;
import cn.mop.persistence.NotificationControlStore.Entry;
import cn.mop.persistence.NotificationControlStore.Kind;
import cn.mop.persistence.NotificationRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises each completed notification read after storage returns; no live database or provider is involved. */
class NotificationRoutingReadAuthorizationTest {
    private static final String ACTOR = "7a000000-0000-4000-8000-000000000001";
    private static final String TENANT = "7a000000-0000-4000-8000-000000000002";
    private static final String OBJECT = "7a000000-0000-4000-8000-000000000003";
    private static final List<String> ROUTES = List.of("policy", "publication", "binding", "list", "detail", "command");

    private final PlatformRepository platform = mock(PlatformRepository.class);
    private final NotificationControlStore store = mock(NotificationControlStore.class);
    private final NotificationRoutingService service = mock(NotificationRoutingService.class);
    private final NotificationRoutingController controller = new NotificationRoutingController(platform, store, service, true);
    private Runnable duringRead = () -> {};

    @BeforeEach
    void prepare() {
        SecurityContextHolder.clearContext();
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true)));
        var entry = new Entry(Kind.ROUTE, UUID.fromString(OBJECT), 0, UUID.fromString(OBJECT),
                new ObjectMapper().createObjectNode().put("private_route", "SYNTHETIC_CURRENT_TENANT_ONLY"),
                Instant.parse("2026-10-23T12:00:00Z"));
        when(service.policy(any())).thenAnswer(call -> afterRead(entry));
        when(service.preference(any())).thenAnswer(call -> afterRead(entry));
        when(service.binding(any(), any(), any())).thenAnswer(call -> afterRead(entry));
        when(service.detail(any(), any(), any())).thenAnswer(call -> afterRead(entry));
        when(store.page(any(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> afterRead(new NotificationControlStore.Page(List.of(entry), null, null)));
        when(store.command(any(), any())).thenAnswer(call -> afterRead(Optional.of(entry)));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private <T> T afterRead(T value) {
        duringRead.run();
        return value;
    }

    private MockHttpServletRequest request(String route) {
        var request = new MockHttpServletRequest("GET", "/api/v1/notification-routing/" + route);
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", TENANT);
        request.setAttribute("mop.trace_id", "synthetic-notification-read");
        if (route.equals("list")) {
            request.addParameter("kind", "ROUTE");
        }
        return request;
    }

    private Object read(String route, MockHttpServletRequest request) {
        return switch (route) {
            case "policy" -> controller.policy(request);
            case "publication" -> controller.publication(request);
            case "binding" -> controller.bindingRead(ACTOR, "EMAIL", request);
            case "list" -> controller.list(request);
            case "detail" -> controller.detail("ROUTE", OBJECT, request);
            case "command" -> controller.recover(OBJECT, request);
            default -> throw new AssertionError(route);
        };
    }

    static Stream<Arguments> revokedReads() {
        return ROUTES.stream().flatMap(route -> Stream.of("tenant", "session", "inactive", "removed", "version", "capability", "role")
                .map(change -> Arguments.of(route, change)));
    }

    @ParameterizedTest
    @MethodSource("revokedReads")
    void completedReadCannotEscapeItsOriginalSessionAndMembership(String route, String change) {
        var request = request(route);
        duringRead = () -> {
            switch (change) {
                case "tenant" -> request.getSession().setAttribute("mop.tenant_id", OBJECT);
                case "session" -> request.getSession().invalidate();
                case "inactive" -> when(platform.membership(ACTOR, TENANT))
                        .thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, false)));
                case "removed" -> when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
                case "version" -> when(platform.membership(ACTOR, TENANT))
                        .thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 8, true)));
                case "capability" -> when(platform.membership(ACTOR, TENANT))
                        .thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true, List.of())));
                case "role" -> when(platform.membership(ACTOR, TENANT))
                        .thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 7, true)));
                default -> throw new AssertionError(change);
            }
        };
        var failure = assertThrows(DomainException.class, () -> read(route, request));
        assertEquals(401, failure.status);
        assertEquals("SESSION_EXPIRED", failure.code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy", "publication", "binding", "list", "detail", "command"})
    void stableAuthorizedReadRetainsItsExistingResponse(String route) {
        var result = (Map<?, ?>) read(route, request(route));
        assertNotNull(result.get("data"));
        verify(platform, times(2)).membership(ACTOR, TENANT);
    }
}
