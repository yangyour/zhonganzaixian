package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.dashboard.DashboardAlertBellReader;
import cn.mop.domain.dashboard.DashboardAlertBellReader.Item;
import cn.mop.domain.dashboard.DashboardAlertBellReader.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Domain consistency and fresh controller authorization, separate from database and browser evidence. */
class DashboardAlertBellTest {
    static final String ACTOR = "79000000-0000-4000-8000-000000000001";
    static final String TENANT = "79000000-0000-4000-8000-000000000002";
    static final Instant NOW = Instant.parse("2026-10-23T14:00:00Z");
    PlatformRepository platform;
    DashboardAlertBellReader reader;
    DashboardAlertBellController controller;
    Membership member;

    static UUID id(int value) {
        return UUID.fromString("79000000-0000-4000-8000-" + String.format("%012d", value));
    }

    static Item item(int value, String suppression) {
        return new Item(id(value), id(3), "SYNTHETIC rule", "HIGH", "PROBLEM", "NEW", suppression, 2, NOW.minusSeconds(1), 1);
    }

    static Snapshot snapshot(long count, List<Item> rows) {
        return new Snapshot(UUID.fromString(TENANT), UUID.fromString(ACTOR), 7, NOW, count, rows);
    }

    @BeforeEach
    void setup() {
        platform = mock(PlatformRepository.class);
        reader = mock(DashboardAlertBellReader.class);
        controller = new DashboardAlertBellController(platform, reader);
        member = new Membership(ACTOR, TENANT, "padmin", 7, true, List.of("dashboard:read", "tickets:read"));
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(member));
    }

    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/dashboard/alert-bell");
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", TENANT);
        return request;
    }

    @Test
    void completeZeroAndSuppressedNewProblemsRemainDistinctFacts() {
        assertEquals(0, snapshot(0, List.of()).total());
        for (String suppression : List.of("NONE", "FLAP_SILENCED", "DEPENDENCY_SUPPRESSED", "MAINTENANCE_SILENCED", "INFO_DEFAULT_SILENT")) {
            assertEquals(suppression, snapshot(1, List.of(item(9, suppression))).items().get(0).suppressionState());
        }
    }

    @Test
    void recoveredOrAlreadyHandledEventsCannotEnterTheProjection() {
        for (String monitor : List.of("RECOVERED", "UNKNOWN")) {
            assertThrows(IllegalArgumentException.class, () -> new Item(
                    id(9), id(3), "test", "HIGH", monitor, "NEW", "NONE", 1, NOW, 1));
        }
        for (String handling : List.of("ACKNOWLEDGED", "IN_PROGRESS", "PENDING_REVIEW", "RESOLVED", "CLOSED")) {
            assertThrows(IllegalArgumentException.class, () -> new Item(
                    id(9), id(3), "test", "HIGH", "PROBLEM", handling, "NONE", 1, NOW, 1));
        }
    }

    @Test
    void topFiveMustBeCompleteSortedAndUnique() {
        var five = List.of(item(9, "NONE"), item(8, "NONE"), item(7, "NONE"), item(6, "NONE"), item(5, "NONE"));
        assertEquals(5, snapshot(20, five).items().size());
        assertThrows(IllegalArgumentException.class, () -> snapshot(20, five.subList(0, 4)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(2, List.of(item(8, "NONE"), item(9, "NONE"))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(2, List.of(item(9, "NONE"), item(9, "NONE"))));
        var mutable = new ArrayList<>(five);
        var result = snapshot(20, mutable);
        mutable.clear();
        assertEquals(5, result.items().size());
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
    }

    @Test
    void invalidCountersAndFutureTimesNeverBecomeAValidZero() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(-1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(DashboardAlertBellReader.MAX_SAFE_INTEGER + 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(1, List.of(new Item(
                id(9), id(3), "test", "HIGH", "PROBLEM", "NEW", "NONE", 1, NOW.plusNanos(1), 1))));
        assertThrows(IllegalArgumentException.class, () -> new Item(
                id(9), id(3), "test", null, "PROBLEM", "NEW", "NONE", 1, NOW, 1));
    }

    @Test
    void successCarriesOnlyExactCurrentScopeAndDoesNotAcknowledge() {
        when(reader.read(any())).thenReturn(snapshot(1, List.of(item(9, "MAINTENANCE_SILENCED"))));
        var result = (Map<?, ?>) controller.read(request());
        var data = (Map<?, ?>) result.get("data");
        assertEquals(1L, data.get("total"));
        assertEquals(Map.of("monitor_state", "PROBLEM", "handling_state", "NEW"), data.get("filter"));
        assertEquals(false, data.get("writes_performed"));
        verify(platform, times(2)).membership(ACTOR, TENANT);
        verify(reader).read(new DashboardAlertBellReader.Access(ACTOR, TENANT, 7));
    }

    @Test
    void authenticatedRoleNamesCannotReplaceCurrentCapabilities() {
        for (String role : List.of("viewer", "tadmin")) {
            when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, role, 7, true)));
            assertEquals(403, assertThrows(DomainException.class, () -> controller.read(request())).status);
        }
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true, List.of("dashboard:read"))));
        assertEquals(403, assertThrows(DomainException.class, () -> controller.read(request())).status);
        verifyNoInteractions(reader);
    }

    @Test
    void anonymousMalformedOrInactiveContextIsRejectedBeforeReader() {
        var anonymous = request();
        anonymous.setUserPrincipal(null);
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(anonymous)).status);
        var malformed = request();
        malformed.getSession().setAttribute("mop.tenant_id", "not-a-tenant");
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(malformed)).status);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, false)));
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(request())).status);
        verifyNoInteractions(reader);
    }

    @Test
    void queryOrBodyCannotOverrideTheContractFilterOrScope() {
        for (String key : List.of("tenant_id", "monitor_state", "handling_state", "limit", "cursor")) {
            var request = request();
            request.addParameter(key, "override");
            assertEquals(400, assertThrows(DomainException.class, () -> controller.read(request)).status);
        }
        var body = request();
        body.setContent(new byte[] {1});
        assertEquals(400, assertThrows(DomainException.class, () -> controller.read(body)).status);
        verifyNoInteractions(reader);
    }

    @Test
    void lostOrChangedMembershipDiscardsAnAlreadyReadSnapshot() {
        when(reader.read(any())).thenReturn(snapshot(0, List.of()));
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(member), Optional.empty());
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(request())).status);
        var changed = new Membership(ACTOR, TENANT, "padmin", 7, true, List.of("dashboard:read", "tickets:read", "assets:read"));
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(member), Optional.of(changed));
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(request())).status);
    }

    @Test
    void wrongScopeOrDependencyFailureNeverReturnsAnEmptySuccess() {
        when(reader.read(any())).thenReturn(new Snapshot(id(99), UUID.fromString(ACTOR), 7, NOW, 0, List.of()));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request())).status);
        when(reader.read(any())).thenThrow(new DomainException(503, "UPSTREAM_UNAVAILABLE", "synthetic outage"));
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request())).status);
    }
}
