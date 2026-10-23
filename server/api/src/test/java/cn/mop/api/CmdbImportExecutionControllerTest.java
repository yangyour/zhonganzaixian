package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetimport.CmdbImportExecutionRepository;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CmdbImportExecutionControllerTest {
    private static final String ACTOR = "10000000-0000-4000-8000-000000000011";
    private static final String TENANT = "20000000-0000-4000-8000-000000000011";
    private static final String SOURCE = "30000000-0000-4000-8000-000000000011";
    private final PlatformRepository platform = mock(PlatformRepository.class);
    private final CmdbImportExecutionRepository imports = mock(CmdbImportExecutionRepository.class);
    private final CmdbImportExecutionController controller = new CmdbImportExecutionController(platform, imports);

    @BeforeEach
    void setup() {
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 7, true)));
        when(platform.idempotentAuthorized(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
                .thenAnswer(call -> {
                    call.getArgument(6, Runnable.class).run();
                    return call.getArgument(7, Supplier.class).get();
                });
        when(imports.execute(any(), anyString(), any())).thenReturn(Map.of("status", "IMPORTED_DRAFTS"));
    }

    private MockHttpServletRequest request(String body) {
        var r = new MockHttpServletRequest("POST", "/api/v1/assets/import-sources/" + SOURCE + "/cmdb-imports");
        r.setUserPrincipal(() -> ACTOR);
        r.getSession().setAttribute("mop.tenant_id", TENANT);
        r.addHeader("Idempotency-Key", "unit-key");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private String body() {
        return "{\"expected_version\":4,\"source_parse_hash\":\"" + "a".repeat(64) + "\"}";
    }

    @Test
    void onlyServerSessionAndSourceBindingReachProtectedCommand() {
        var response = new MockHttpServletResponse();
        controller.execute(SOURCE, request(body()), response);
        var order = inOrder(imports);
        order.verify(imports).authorize(argThat(a -> a.actorId().equals(ACTOR) && a.tenantId().equals(TENANT) && a.permissionVersion() == 7), eq(SOURCE));
        order.verify(imports).execute(any(), eq(SOURCE), eq(new CmdbImportExecutionRepository.Command(4, "a".repeat(64))));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void rejectsSpoofedTenantRulesCellsUnknownAndDuplicateFields() {
        for (String value : List.of(body().replace("}", ",\"tenant_id\":\"x\"}"),
                body().replace("}", ",\"expected_version\":4}"), body() + "{}",
                "{\"expected_version\":4,\"cells\":[]}", body().replace("4", "1.5"))) {
            assertEquals(400, assertThrows(DomainException.class,
                    () -> controller.execute(SOURCE, request(value), new MockHttpServletResponse())).status);
        }
        verifyNoInteractions(imports);
    }

    @Test
    void rejectsQueryOverridesOversizedMetadataAndMissingKeys() {
        var query = request(body());
        query.addParameter("tenant_id", TENANT);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.execute(SOURCE, query, new MockHttpServletResponse())).status);
        var keyless = request(body());
        keyless.removeHeader("Idempotency-Key");
        assertEquals(400, assertThrows(DomainException.class, () -> controller.execute(SOURCE, keyless, new MockHttpServletResponse())).status);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.execute(SOURCE, request(" ".repeat(1025)), new MockHttpServletResponse())).status);
        verifyNoInteractions(imports);
    }

    @Test
    void revokedViewerAndCustomerMembersCannotWrite() {
        for (String role : List.of("viewer", "tadmin")) {
            when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, role, 7, true)));
            assertEquals(403, assertThrows(DomainException.class, () -> controller.execute(SOURCE, request(body()), new MockHttpServletResponse())).status);
        }
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
        assertEquals(401, assertThrows(DomainException.class, () -> controller.execute(SOURCE, request(body()), new MockHttpServletResponse())).status);
        verifyNoInteractions(imports);
    }

    @Test
    void replayAuthorizationFailureCannotExposeCachedReceipt() {
        doThrow(new DomainException(404, "OBJECT_NOT_FOUND", "missing")).when(imports).authorize(any(), anyString());
        assertEquals(404, assertThrows(DomainException.class,
                () -> controller.execute(SOURCE, request(body()), new MockHttpServletResponse())).status);
        verify(imports, never()).execute(any(), anyString(), any());
    }

    @Test
    void receiptLookupIsReadonlyCreatorScopedAndHasNoFailureInference() {
        var response = new MockHttpServletResponse();
        when(imports.receipt(any(), eq(SOURCE))).thenReturn(Map.of("status", "NO_COMMITTED_RECEIPT", "absence_proves_failure", false));
        controller.receipt(SOURCE, request(""), response);
        verify(imports).receipt(argThat(a -> a.actorId().equals(ACTOR) && a.tenantId().equals(TENANT)), eq(SOURCE));
        verify(imports, never()).execute(any(), anyString(), any());
        verify(platform, never()).idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void receiptLookupRejectsTenantOverridesAndHiddenSources() {
        var query = request("");
        query.addParameter("tenant_id", TENANT);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.receipt(SOURCE, query, new MockHttpServletResponse())).status);
        when(imports.receipt(any(), anyString())).thenThrow(new DomainException(404, "OBJECT_NOT_FOUND", "missing"));
        assertEquals(404, assertThrows(DomainException.class, () -> controller.receipt(SOURCE, request(""), new MockHttpServletResponse())).status);
    }
}
