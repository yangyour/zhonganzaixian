package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader.Item;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader.Snapshot;
import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.WorkflowLifecycle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure aggregation invariants and controller authorization, not PostgreSQL evidence. */
class DashboardPendingTicketsTest {
    static final String ACTOR = "10000000-0000-4000-8000-000000000001";
    static final String TENANT = "20000000-0000-4000-8000-000000000002";
    static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    PlatformRepository platform;
    DashboardPendingTicketsReader reader;
    DashboardPendingTicketsController controller;

    @BeforeEach
    void setup() {
        platform = mock(PlatformRepository.class);
        reader = mock(DashboardPendingTicketsReader.class);
        controller = new DashboardPendingTicketsController(platform, reader);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 1, true)));
    }

    static Item item(String model, String state) {
        return new Item(UUID.randomUUID(), "SYNTHETIC", "合成工单", NOW.minusSeconds(1), model, state, 1);
    }

    Snapshot snapshot(String scope, long unknown, List<Item> items) {
        return new Snapshot(UUID.fromString(TENANT), UUID.fromString(ACTOR), 1, NOW, scope,
                items.size() + unknown, items.size(), 0, unknown, items);
    }

    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/dashboard/pending-tickets");
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", TENANT);
        return request;
    }

    @Test
    void bothWorkflowModelsIncludeAllNonterminalStates() {
        for (var state : WorkflowLifecycle.State.values()) {
            assertEquals(state != WorkflowLifecycle.State.CLOSED && state != WorkflowLifecycle.State.CANCELLED,
                    DashboardPendingTicketsReader.pending("WORKFLOW_V33", state.name()));
        }
        for (var state : TicketLifecycle.State.values()) {
            assertEquals(state != TicketLifecycle.State.CLOSED,
                    DashboardPendingTicketsReader.pending("LIFECYCLE_V42", state.name()));
        }
    }

    @Test
    void conflictingAndUnknownModelsOrStatesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> DashboardPendingTicketsReader.pending("CONFLICT", "OPEN"));
        assertThrows(IllegalArgumentException.class, () -> DashboardPendingTicketsReader.pending("WORKFLOW_V33", "NEW"));
        assertThrows(IllegalArgumentException.class, () -> item("WORKFLOW_V33", "CLOSED"));
    }

    @Test
    void absentWorkflowPreventsFalseCompleteCount() {
        assertNull(snapshot("CURRENT_TENANT_INTERNAL", 1, List.of(item("WORKFLOW_V33", "OPEN"))).pendingTickets());
        assertEquals(0L, snapshot("CURRENT_TENANT_INTERNAL", 0, List.of()).pendingTickets());
        assertEquals(1L, snapshot("CURRENT_TENANT_INTERNAL", 0, List.of(item("LIFECYCLE_V42", "WAITING_VENDOR"))).pendingTickets());
    }

    @Test
    void corruptCountsAndDuplicateOrIncompleteTopFiveAreRejected() {
        var row = item("WORKFLOW_V33", "OPEN");
        assertThrows(IllegalArgumentException.class, () -> snapshot("CURRENT_TENANT_INTERNAL", 0, List.of(row, row)));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(UUID.fromString(TENANT), UUID.fromString(ACTOR),
                1, NOW, "CURRENT_TENANT_INTERNAL", 6, 6, 0, 0, List.of(row)));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(UUID.fromString(TENANT), UUID.fromString(ACTOR),
                1, NOW, "CURRENT_TENANT_INTERNAL", 1, 0, 0, 0, List.of()));
    }

    @Test
    void controllerReportsPartialCountsAndNoInferredSla() {
        when(reader.read(any())).thenReturn(snapshot("CURRENT_TENANT_INTERNAL", 1, List.of(item("WORKFLOW_V33", "RESOLVED"))));
        Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) controller.read(request())).get("data");
        assertEquals("PARTIAL", data.get("state"));
        assertEquals(1L, data.get("known_pending"));
        assertNull(data.get("pending_tickets"));
        assertNull(data.get("overdue_sla"));
        assertEquals(false, data.get("writes_performed"));
        verify(reader).read(new DashboardPendingTicketsReader.Access(ACTOR, TENANT, 1));
    }

    @Test
    void customerCannotReceiveInternalScopeProjection() {
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 1, true)));
        when(reader.read(any())).thenReturn(snapshot("CURRENT_TENANT_INTERNAL", 0, List.of()));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request())).status);
        when(reader.read(any())).thenReturn(snapshot("REPORTER_ONLY", 0, List.of()));
        assertNotNull(controller.read(request()));
    }

    @Test
    void noSessionOrCurrentCapabilitiesRejectBeforeRead() {
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(new MockHttpServletRequest())).status);
        for (var capabilities : List.of(List.of("dashboard:read"), List.of("tickets:read"))) {
            when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 1, true, capabilities)));
            assertEquals(403, assertThrows(DomainException.class, () -> controller.read(request())).status);
        }
        verifyNoInteractions(reader);
    }

    @Test
    void queryScopeAndLimitOverridesAreRejected() {
        for (String key : List.of("tenant_id", "limit", "state", "sort")) {
            var request = request();
            request.addParameter(key, "forged");
            assertEquals(400, assertThrows(DomainException.class, () -> controller.read(request)).status);
        }
        verifyNoInteractions(reader);
    }

    @Test
    void databaseFailureDoesNotBecomeEmptySuccess() {
        when(reader.read(any())).thenThrow(new DomainException(503, "UPSTREAM_UNAVAILABLE", "unavailable"));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request())).status);
    }
}
