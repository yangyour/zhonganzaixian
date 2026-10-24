package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardActionableTicketsReader;
import cn.mop.domain.dashboard.DashboardActionableTicketsReader.Page;
import cn.mop.domain.dashboard.DashboardActionableTicketsReader.Snapshot;
import cn.mop.domain.dashboard.DashboardPendingTicketSlaReader.Item;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import cn.mop.domain.dashboard.DashboardTicketSla;
import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.WorkflowLifecycle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static cn.mop.api.DashboardPendingTicketsTest.ACTOR;
import static cn.mop.api.DashboardPendingTicketsTest.NOW;
import static cn.mop.api.DashboardPendingTicketsTest.TENANT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure population invariants and controller race checks; this suite is not PostgreSQL integration evidence. */
class DashboardActionableTicketsTest {
    PlatformRepository platform;
    DashboardActionableTicketsReader reader;
    DashboardActionableTicketsController controller;

    @BeforeEach
    void setup() {
        platform = mock(PlatformRepository.class);
        reader = mock(DashboardActionableTicketsReader.class);
        controller = new DashboardActionableTicketsController(platform, reader);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 1, true)));
    }

    static Item item(String model, String state, Boolean overdue) {
        var ticket = DashboardPendingTicketsTest.item(model, state);
        var clock = new DashboardTicketSla.Clock("RUNNING", overdue, null, NOW.plusSeconds(3600),
                null, overdue == null ? null : 3_600_000L, overdue == null ? null : "FROZEN_SERVICE_WINDOWS");
        return new Item(ticket, overdue == null ? DashboardTicketSla.unavailable(1, "CONFIG_PENDING")
                : new DashboardTicketSla.Assessment("P1", "STANDARD", "SLA_CLOCK_V2", 1, clock, clock));
    }

    static Snapshot snapshot(long known, long excluded, long unconfigured, long overdue, long unknown,
                             Page page, List<Item> items) {
        return new Snapshot(UUID.fromString(TENANT), UUID.fromString(ACTOR), 1, NOW, "CURRENT_TENANT_INTERNAL",
                known + excluded + unconfigured, known, excluded, unconfigured, overdue, unknown, page, items);
    }

    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/dashboard/actionable-tickets");
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", TENANT);
        return request;
    }

    @Test
    void bothPersistedModelsExcludeResolvedWithoutChangingLegacySemantics() {
        for (var state : WorkflowLifecycle.State.values()) {
            assertEquals(!List.of("CLOSED", "CANCELLED", "RESOLVED").contains(state.name()),
                    DashboardActionableTicketsReader.actionable("WORKFLOW_V33", state.name()));
        }
        for (var state : TicketLifecycle.State.values()) {
            assertEquals(!List.of("CLOSED", "RESOLVED").contains(state.name()),
                    DashboardActionableTicketsReader.actionable("LIFECYCLE_V42", state.name()));
        }
        assertTrue(DashboardPendingTicketsReader.pending("WORKFLOW_V33", "RESOLVED"));
        assertTrue(DashboardPendingTicketsReader.pending("LIFECYCLE_V42", "RESOLVED"));
        assertThrows(IllegalArgumentException.class, () -> DashboardActionableTicketsReader.actionable("CONFLICT", "OPEN"));
        assertThrows(IllegalArgumentException.class, () -> DashboardActionableTicketsReader.actionable("LIFECYCLE_V42", "CANCELLED"));
    }

    @Test
    void exactZeroAndUnknownAreDifferentAndExclusionsDoNotInflateUnknownSla() {
        var empty = snapshot(0, 12, 0, 0, 0, new Page(1, 5), List.of());
        assertEquals(0L, empty.pendingTickets());
        assertEquals(0L, empty.overdueSla());
        assertEquals(0L, empty.totalPages());
        var missingWorkflow = snapshot(0, 12, 1, 0, 0, new Page(1, 5), List.of());
        assertNull(missingWorkflow.pendingTickets());
        assertNull(missingWorkflow.overdueSla());
        assertEquals(1, missingWorkflow.unknownSla());
        var missingSla = snapshot(1, 0, 0, 0, 1, new Page(1, 5), List.of(item("LIFECYCLE_V42", "NEW", null)));
        assertEquals(1L, missingSla.pendingTickets());
        assertNull(missingSla.overdueSla());
    }

    @Test
    void completePaginationBeyondOneHundredDoesNotLimitTotals() {
        var last = snapshot(101, 5, 0, 1, 0, new Page(21, 5), List.of(item("WORKFLOW_V33", "OPEN", true)));
        assertEquals(101L, last.pendingTickets());
        assertEquals(21L, last.totalPages());
        assertEquals(1L, last.overdueSla());
        assertEquals(100L, last.page().offset());
        assertEquals(List.of(), snapshot(101, 0, 0, 1, 0, new Page(22, 5), List.of()).items());
    }

    @Test
    void incompletePagesResolvedRowsDuplicateIdsAndImpossibleSlaCountsFailClosed() {
        var one = item("WORKFLOW_V33", "OPEN", false);
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(6, 0, 0, 0, 0, new Page(1, 5), List.of(one)));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(2, 0, 0, 0, 0, new Page(1, 5), List.of(one, one)));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1, 0, 0, 0, 0, new Page(1, 5), List.of(item("WORKFLOW_V33", "RESOLVED", false))));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1, 0, 0, 0, 0, new Page(1, 5), List.of(item("WORKFLOW_V33", "OPEN", true))));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1, 0, 0, 1, 1, new Page(1, 5), List.of(one)));
    }

    @Test
    void responseHasFullPopulationSemanticsAndUnchangedClockFields() {
        var request = request();
        request.addParameter("page", "21");
        request.addParameter("page_size", "5");
        when(reader.read(any(), any())).thenReturn(snapshot(101, 9, 0, 1, 0, new Page(21, 5),
                List.of(item("WORKFLOW_V33", "OPEN", true))));
        Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) controller.read(request)).get("data");
        assertEquals("MOP_ACTIONABLE_TICKETS_V1", data.get("metric_version"));
        assertEquals("MOP_ACTIONABLE_TICKETS_SLA_V1", data.get("projection_version"));
        assertEquals(List.of("CLOSED", "CANCELLED", "RESOLVED"), data.get("excluded_states"));
        assertEquals(101L, data.get("evaluated_pending"));
        assertEquals(0L, data.get("unevaluated_pending"));
        assertNull(data.get("evaluation_limit"));
        assertEquals(9L, data.get("excluded"));
        assertFalse(data.containsKey("terminal"));
        assertEquals(21L, data.get("total_pages"));
        verify(reader).read(new DashboardPendingTicketsReader.Access(ACTOR, TENANT, 1), new Page(21, 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "01", "+1", "1.0", "1e2", " 1", "1 ", "", "9007199254740991", "99999999999999999"})
    void unsafePageParametersCannotReachReader(String value) {
        var request = request();
        request.addParameter("page", value);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.read(request)).status);
        verifyNoInteractions(reader);
    }

    @Test
    void duplicateUnknownAndUnsafeSizeParametersCannotReachReader() {
        for (String key : List.of("tenant_id", "actor_id", "state", "sort", "limit", "observed_at")) {
            var request = request();
            request.addParameter(key, "forged");
            assertEquals(400, assertThrows(DomainException.class, () -> controller.read(request)).status);
        }
        for (String key : List.of("page", "page_size")) {
            var request = request();
            request.addParameter(key, "1", "1");
            assertEquals(400, assertThrows(DomainException.class, () -> controller.read(request)).status);
        }
        for (String size : List.of("0", "101", "2147483648")) {
            var request = request();
            request.addParameter("page_size", size);
            assertEquals(400, assertThrows(DomainException.class, () -> controller.read(request)).status);
        }
        verifyNoInteractions(reader);
    }

    @Test
    void missingSessionCapabilitiesOrMismatchedScopeCannotExposeProjection() {
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(new MockHttpServletRequest())).status);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(
                new Membership(ACTOR, TENANT, "padmin", 1, true, List.of("dashboard:read"))));
        assertEquals(403, assertThrows(DomainException.class, () -> controller.read(request())).status);
        verifyNoInteractions(reader);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 1, true)));
        when(reader.read(any(), any())).thenReturn(snapshot(0, 0, 0, 0, 0, new Page(1, 5), List.of()));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request())).status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "removed", "version", "capability", "role", "tenant", "session"})
    void revocationDuringReadNeverReturnsSnapshot(String change) {
        var request = request();
        when(reader.read(any(), any())).thenAnswer(invocation -> {
            switch (change) {
                case "inactive" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "padmin", 1, false)));
                case "removed" -> when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
                case "version" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "padmin", 2, true)));
                case "capability" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "padmin", 1, true, List.of("dashboard:read"))));
                case "role" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "msp", 1, true)));
                case "tenant" -> request.getSession().setAttribute("mop.tenant_id", UUID.randomUUID().toString());
                case "session" -> request.getSession().invalidate();
                default -> throw new AssertionError(change);
            }
            return snapshot(0, 0, 0, 0, 0, new Page(1, 5), List.of());
        });
        var error = assertThrows(DomainException.class, () -> controller.read(request));
        assertEquals(401, error.status);
        assertEquals("SESSION_EXPIRED", error.code);
    }

    @Test
    void databaseFailureAndIncorrectReturnedPageCannotBecomeSuccessfulEmptyData() {
        when(reader.read(any(), any())).thenThrow(new DomainException(503, "UPSTREAM_UNAVAILABLE", "synthetic failure"));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request())).status);
        reset(reader);
        when(reader.read(any(), any())).thenReturn(snapshot(0, 0, 0, 0, 0, new Page(2, 5), List.of()));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request())).status);
    }
}
