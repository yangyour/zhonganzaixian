package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.AssetLedgerFactsRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetLedgerFactsControllerTest {
    private static final String TENANT = "21000000-0000-4000-8000-000000000001";
    private static final String ACTOR = "22000000-0000-4000-8000-000000000001";
    private static final String ASSET = "23000000-0000-4000-8000-000000000001";
    private final PlatformRepository platform = mock(PlatformRepository.class);
    private final AssetLedgerFactsRepository facts = mock(AssetLedgerFactsRepository.class);
    private final AssetLedgerFactsController controller = new AssetLedgerFactsController(platform, facts);

    @BeforeEach
    void setup() {
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, true)));
        when(facts.read(any(), any())).thenReturn(snapshot(TENANT, ASSET, null));
    }

    private AssetLedgerFactsRepository.Snapshot snapshot(String tenant, String asset, Instant recorded) {
        return new AssetLedgerFactsRepository.Snapshot(tenant, Instant.parse("2026-10-23T00:00:00Z"),
                List.of(new AssetLedgerFactsRepository.Facts(asset, 2, "site-a", "Synthetic site",
                        recorded == null ? null : 2L, recorded, recorded == null ? "NOT_RECORDED" : "MANUAL_DECLARATION",
                        null, null)));
    }

    private MockHttpServletRequest request() {
        var r = new MockHttpServletRequest("GET", "/api/v1/assets/ledger-facts");
        r.setUserPrincipal(() -> ACTOR);
        r.getSession().setAttribute("mop.tenant_id", TENANT);
        r.addParameter("ids", ASSET);
        return r;
    }

    @Test
    void assetsReadAllowsPublicFieldProjectionWithoutInternalMetadataWriteRights() {
        var response = new MockHttpServletResponse();
        var result = (Map<?, ?>) controller.read(request(), response);
        assertEquals(TENANT, ((Map<?, ?>) result.get("data")).get("tenant_id"));
        verify(facts).read(eq(new AssetLedgerFactsRepository.Access(ACTOR, TENANT, 7)), eq(List.of(ASSET)));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        verify(platform, times(2)).membership(ACTOR, TENANT);
    }

    @Test
    void missingOrRevokedSessionCannotRead() {
        var anonymous = request();
        anonymous.setUserPrincipal(null);
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(anonymous, new MockHttpServletResponse())).status);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(request(), new MockHttpServletResponse())).status);
        verifyNoInteractions(facts);
    }

    @Test
    void queryOverridesAndDuplicateParametersAreRejected() {
        var override = request();
        override.addParameter("tenant_id", ACTOR);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.read(override, new MockHttpServletResponse())).status);
        var duplicate = request();
        duplicate.addParameter("ids", ASSET);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.read(duplicate, new MockHttpServletResponse())).status);
        verifyNoInteractions(facts);
    }

    @Test
    void crossTenantMissingAndDatabaseFailureRetainTheirDenial() {
        when(facts.read(any(), any())).thenThrow(new DomainException(404, "OBJECT_NOT_FOUND", "hidden"));
        assertEquals(404, assertThrows(DomainException.class, () -> controller.read(request(), new MockHttpServletResponse())).status);
        doThrow(new DomainException(503, "ASSET_LEDGER_FACTS_UNAVAILABLE", "unavailable")).when(facts).read(any(), any());
        assertEquals(503, assertThrows(DomainException.class, () -> controller.read(request(), new MockHttpServletResponse())).status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "removed", "version", "capabilities", "role", "tenant", "session", "actor"})
    void completeLiveMembershipAndSessionAreRecheckedBeforeReturningFacts(String change) {
        var request = request();
        when(facts.read(any(), any())).thenAnswer(invocation -> {
            switch (change) {
                case "inactive" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, false)));
                case "removed" -> when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
                case "version" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "viewer", 8, true)));
                case "capabilities" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, true, List.of("dashboard:read"))));
                case "role" -> when(platform.membership(ACTOR, TENANT)).thenReturn(
                        Optional.of(new Membership(ACTOR, TENANT, "tadmin", 7, true)));
                case "tenant" -> request.getSession().setAttribute("mop.tenant_id", UUID.randomUUID().toString());
                case "session" -> request.getSession().invalidate();
                case "actor" -> request.setUserPrincipal(() -> UUID.randomUUID().toString());
                default -> throw new AssertionError(change);
            }
            return snapshot(TENANT, ASSET, null);
        });
        var error = assertThrows(DomainException.class, () -> controller.read(request, new MockHttpServletResponse()));
        assertEquals(401, error.status);
        assertEquals("SESSION_EXPIRED", error.code);
    }

    @Test
    void mismatchedScopeIncompleteSelectionOrFutureMetadataCannotBeReturned() {
        for (var result : List.of(snapshot(ACTOR, ASSET, null), snapshot(TENANT, ACTOR, null),
                snapshot(TENANT, ASSET, Instant.parse("2026-10-23T00:00:00.001Z")),
                new AssetLedgerFactsRepository.Snapshot(TENANT, Instant.parse("2026-10-23T00:00:00Z"), List.of()))) {
            when(facts.read(any(), any())).thenReturn(result);
            assertEquals(503, assertThrows(DomainException.class,
                    () -> controller.read(request(), new MockHttpServletResponse())).status);
        }
    }

    @Test
    void malformedIdentifiersAndDuplicatedSelectionFailBeforeRead() {
        for (String selected : List.of("bad", ASSET + ",", ASSET + "," + ASSET, ASSET.toUpperCase().replace("230", "23A"))) {
            var request = request();
            request.setParameter("ids", selected);
            assertEquals(400, assertThrows(DomainException.class,
                    () -> controller.read(request, new MockHttpServletResponse())).status);
        }
        var actor = request();
        actor.setUserPrincipal(() -> "invalid-actor");
        assertEquals(401, assertThrows(DomainException.class,
                () -> controller.read(actor, new MockHttpServletResponse())).status);
        verifyNoInteractions(facts);
    }
}
