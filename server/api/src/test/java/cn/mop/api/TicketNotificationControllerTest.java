package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.notification.TicketNotificationRepository;
import cn.mop.domain.notification.TicketNotificationRepository.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketNotificationControllerTest {
    String actor = "55000000-0000-4000-8000-000000000011", tenant = "55000000-0000-4000-8000-000000000001", id = "55000000-0000-4000-8000-000000000099", ticket = "55000000-0000-4000-8000-000000000077";
    Instant at = Instant.parse("2026-10-18T06:00:00Z");
    PlatformRepository platform; TicketNotificationRepository repository; TicketNotificationController controller;
    Message message(String recipient, long version, Instant read) { return new Message(id, tenant, "55000000-0000-4000-8000-000000000088", ticket, recipient, "CUSTOMER", "ticket.resolved", "[R-1] 已解决，待您确认", "请确认", at, version, read); }
    @BeforeEach void setup() {
        platform = mock(PlatformRepository.class); repository = mock(TicketNotificationRepository.class); controller = new TicketNotificationController(platform, repository);
        when(platform.membership(actor, tenant)).thenReturn(Optional.of(new Membership(actor, tenant, "viewer", 3, true)));
        when(repository.messages(any(), anyInt(), any(), any(), anyBoolean())).thenReturn(new Page(List.of(message(actor, 0, null)), true, 1, at.plusSeconds(1)));
        when(platform.idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenAnswer(x -> { ((Runnable) x.getArgument(6)).run(); return ((Supplier<?>) x.getArgument(7)).get(); });
    }
    MockHttpServletRequest request(String method, String path, String body) {
        var r = new MockHttpServletRequest(method, "/api/v1" + path); r.setUserPrincipal(() -> actor); r.getSession().setAttribute("mop.tenant_id", tenant); r.setAttribute("mop.trace_id", "notification-test");
        r.addHeader("Idempotency-Key", "notification-read"); r.setContentType("application/json"); r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return r;
    }
    Map<?, ?> data(Object value) { return (Map<?, ?>) ((Map<?, ?>) value).get("data"); }

    @Test void listUsesSessionScopeAndMarksChannelInAppOnly() {
        var result = data(controller.list(request("GET", "/my/notifications", "")));
        var item = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertEquals("IN_APP", item.get("channel")); assertEquals("NOT_CONFIGURED", result.get("external_dispatch")); assertEquals(1L, result.get("unread_count"));
        assertEquals(id, result.get("next_before_id")); assertEquals(at.toString(), result.get("next_before_created_at"));
        verify(repository).messages(argThat(a -> a.tenantId().equals(tenant) && a.actorId().equals(actor) && a.permissionVersion() == 3), eq(20), isNull(), isNull(), eq(false));
    }
    @Test void foreignRecordFromRepositoryIsNeverReturned() {
        when(repository.messages(any(), anyInt(), any(), any(), anyBoolean())).thenReturn(new Page(List.of(message(id, 0, null)), false, 1, at));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.list(request("GET", "/my/notifications", ""))).status);
    }
    @Test void invalidQueriesRejected() {
        for (String[] p : List.of(new String[]{"tenant_id", tenant}, new String[]{"before_id", id}, new String[]{"page_size", "51"}, new String[]{"page_size", "0"}, new String[]{"unread_only", "yes"}, new String[]{"before_created_at", "2026-10-18T06:00:00.000001Z"})) {
            var r = request("GET", "/my/notifications", ""); r.addParameter(p[0], p[1]); if (p[0].equals("before_created_at")) r.addParameter("before_id", id);
            assertEquals(400, assertThrows(DomainException.class, () -> controller.list(r)).status, p[0] + "=" + p[1]);
        }
    }
    @Test void readUsesIdempotentCommandAndReturnsFirstReadReceipt() {
        when(repository.markRead(any(), eq(id), eq(0L))).thenReturn(message(actor, 1, at.plusSeconds(5)));
        var result = data(controller.read(id, request("POST", "/my/notifications/" + id + "/read", "{\"expected_version\":0}")));
        assertEquals(1L, result.get("version")); assertEquals(at.plusSeconds(5).toString(), result.get("read_at")); verify(repository).authorizeRead(any(), eq(id));
        verify(platform).idempotentAuthorized(eq(actor), eq(tenant), eq("POST /api/v1/my/notifications/" + id + "/read"), eq("notification-read"), any(), eq(3L), any(), any());
    }
    @Test void invalidReadBodiesAndMissingKeyRejected() {
        for (String b : List.of("{}", "{\"expected_version\":2}", "{\"expected_version\":0.0}", "{\"expected_version\":0,\"read_at\":null}"))
            assertEquals(400, assertThrows(DomainException.class, () -> controller.read(id, request("POST", "/my/notifications/" + id + "/read", b))).status);
        var noKey = new MockHttpServletRequest("POST", "/api/v1/my/notifications/" + id + "/read"); noKey.setUserPrincipal(() -> actor); noKey.getSession().setAttribute("mop.tenant_id", tenant); noKey.setContentType("application/json"); noKey.setContent("{\"expected_version\":0}".getBytes());
        assertEquals(400, assertThrows(DomainException.class, () -> controller.read(id, noKey)).status);
        verify(repository, never()).markRead(any(), any(), anyLong());
    }
    @Test void intentLedgerIsInternalOnly() {
        assertEquals(403, assertThrows(DomainException.class, () -> controller.intents(request("GET", "/notification-intents", ""))).status);
        when(platform.membership(actor, tenant)).thenReturn(Optional.of(new Membership(actor, tenant, "msp", 4, true)));
        when(repository.intents(any(), anyInt(), any(), any())).thenReturn(new IntentPage(List.of(new Intent(id, ticket, "R-1", "ticket.sla_100", "SLA_100", "P1", "PARTIAL", 1, List.of("Q15_ESCALATION_CHAIN"), "NOT_CONFIGURED", List.of("Q14_CHANNEL_MATRIX", "Q18_PROVIDER_CREDENTIALS"), at)), false, at));
        var item = (Map<?, ?>) ((List<?>) data(controller.intents(request("GET", "/notification-intents", ""))).get("items")).get(0);
        assertEquals("PARTIAL", item.get("recipient_status")); assertEquals(List.of("Q15_ESCALATION_CHAIN"), item.get("pending_reasons")); assertEquals("NOT_CONFIGURED", item.get("external_dispatch"));
    }
    @Test void anonymousAndInactiveRejected() {
        assertEquals(401, assertThrows(DomainException.class, () -> controller.list(new MockHttpServletRequest())).status);
        when(platform.membership(actor, tenant)).thenReturn(Optional.of(new Membership(actor, tenant, "viewer", 3, false)));
        assertEquals(401, assertThrows(DomainException.class, () -> controller.list(request("GET", "/my/notifications", ""))).status);
    }
    @Test void responsesAreNotCached() { var response = new MockHttpServletResponse(); controller.headers(response); assertEquals("no-store", response.getHeader("Cache-Control")); }
}
