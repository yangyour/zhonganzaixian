package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.AssetLedgerFactsRepository;
import org.junit.jupiter.api.*;
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
        when(facts.read(any(), any())).thenReturn(new AssetLedgerFactsRepository.Snapshot(TENANT, Instant.parse("2026-10-23T00:00:00Z"), List.of(new AssetLedgerFactsRepository.Facts(ASSET,1,"unit-site","Unit site",null,null,"NOT_RECORDED",null,null))));
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
    }

    @Test
    void missingOrRevokedSessionCannotRead() {
        var anonymous = request(); anonymous.setUserPrincipal(null);
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(anonymous, new MockHttpServletResponse())).status);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
        assertEquals(401, assertThrows(DomainException.class, () -> controller.read(request(), new MockHttpServletResponse())).status);
        verifyNoInteractions(facts);
    }

    @Test
    void queryOverridesAndDuplicateParametersAreRejected() {
        var override = request(); override.addParameter("tenant_id", ACTOR);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.read(override, new MockHttpServletResponse())).status);
        var duplicate = request(); duplicate.addParameter("ids", ASSET);
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
    @Test void lateRevocationAndForeignResultsNeverReturnData() {
        var r=request();
        when(facts.read(any(),any())).thenAnswer(call->{
            when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());
            return new AssetLedgerFactsRepository.Snapshot(TENANT,Instant.parse("2026-10-23T00:00:00Z"),
                List.of(new AssetLedgerFactsRepository.Facts(ASSET,1,"unit-site","Unit site",null,null,"NOT_RECORDED",null,null)));
        });
        assertEquals(401,assertThrows(DomainException.class,()->controller.read(r,new MockHttpServletResponse())).status);
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));
        doReturn(new AssetLedgerFactsRepository.Snapshot(ACTOR,Instant.now(),List.of())).when(facts).read(any(),any());
        assertEquals(503,assertThrows(DomainException.class,()->controller.read(request(),new MockHttpServletResponse())).status);
    }

    @Test void publicJsonHasExplicitSnakeCaseAndIsoTimestamps() {
        var output=controller.read(request(),new MockHttpServletResponse());
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(output).get("data");
        assertEquals("2026-10-23T00:00:00Z",json.get("observed_at").asText());
        assertEquals(ASSET,json.get("items").get(0).get("asset_id").asText());
        assertFalse(json.get("items").get(0).has("assetId"));
        assertTrue(json.get("items").get(0).get("metadata_recorded_at").isNull());
    }

    @Test void malformedIdentityAndSelectionsAreRejectedBeforeRepositoryRead() {
        for(String ids:List.of("bad", ASSET+","+ASSET, ASSET+",", "")) {
            var r=request();r.setParameter("ids",ids);
            assertEquals(400,assertThrows(DomainException.class,()->controller.read(r,new MockHttpServletResponse())).status);
        }
        var r=request();r.setUserPrincipal(()->"malformed");
        assertEquals(401,assertThrows(DomainException.class,()->controller.read(r,new MockHttpServletResponse())).status);
        verifyNoInteractions(facts);
    }

}
