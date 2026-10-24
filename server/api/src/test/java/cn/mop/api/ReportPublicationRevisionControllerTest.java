package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.reporting.ReportPublication;
import cn.mop.domain.reporting.ReportPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Public revision commands retain strict input, object authorization and durable-command boundaries. */
class ReportPublicationRevisionControllerTest {

    private static final String ACTOR = "79000000-0000-4000-8000-000000000001";
    private static final String TENANT = "79000000-0000-4000-8000-000000000002";
    private static final String PARENT = "79000000-0000-4000-8000-000000000003";
    private static final String SOURCE = "79000000-0000-4000-8000-000000000004";
    private static final String CHILD = "79000000-0000-4000-8000-000000000005";
    private final PlatformRepository platform = mock(PlatformRepository.class);
    private final ReportPublicationRepository reports = mock(ReportPublicationRepository.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ReportPublicationController controller = new ReportPublicationController(platform, reports, json);

    @BeforeEach
    void prepareCurrentMemberAndProtectedCommand() {
        when(platform.membership(ACTOR, TENANT)).thenReturn(
            Optional.of(new Membership(ACTOR, TENANT, "padmin", 7, true))
        );
        when(
            platform.idempotentAuthorized(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyLong(),
                any(),
                any()
            )
        ).thenAnswer(call -> {
            ((Runnable) call.getArgument(6)).run();
            return ((Supplier<?>) call.getArgument(7)).get();
        });
    }

    @Test
    void explicitRevisionRunsInsideAuthorizedIdempotentCommand() {
        when(reports.revise(any(), eq(PARENT), eq(6L), eq(SOURCE), eq("a".repeat(64)), eq("修正来源"))).thenReturn(
            child(TENANT, PARENT)
        );
        assertNotNull(controller.revise(PARENT, body(), request()));
        verify(reports).authorize(any(), eq(PARENT), eq(true));
        verify(reports).revise(any(), eq(PARENT), eq(6L), eq(SOURCE), eq("a".repeat(64)), eq("修正来源"));
        verify(platform).idempotentAuthorized(
            eq(ACTOR),
            eq(TENANT),
            anyString(),
            eq("revision-unit"),
            matches("[a-f0-9]{64}"),
            eq(7L),
            any(),
            any()
        );
    }

    @Test
    void callerCannotReplaceTenantFamilyPayloadOrPolicy() {
        for (String extra : List.of("tenant_id", "family_id", "payload", "policy", "revision")) {
            var body = body();
            body.put(extra, "caller-controlled");
            assertEquals(
                400,
                assertThrows(DomainException.class, () -> controller.revise(PARENT, body, request())).status
            );
        }
        verifyNoInteractions(reports);
    }

    @Test
    void blankReasonAndInvalidVersionOrHashFailBeforeWrites() {
        var blank = body();
        blank.put("reason", "  ");
        var staleShape = body();
        staleShape.put("expected_version", 1.5);
        var hash = body();
        hash.put("expected_source_sha256", "not-a-digest");
        for (var value : List.of(blank, staleShape, hash)) {
            assertEquals(
                400,
                assertThrows(DomainException.class, () -> controller.revise(PARENT, value, request())).status
            );
        }
        verifyNoInteractions(reports);
    }

    @Test
    void duplicateIdempotencyAndQueryOverridesAreRejected() {
        var duplicate = request();
        duplicate.addHeader("Idempotency-Key", "another-key");
        assertEquals(
            400,
            assertThrows(DomainException.class, () -> controller.revise(PARENT, body(), duplicate)).status
        );
        var query = request();
        query.addParameter("source_id", SOURCE);
        assertEquals(400, assertThrows(DomainException.class, () -> controller.revise(PARENT, body(), query)).status);
        verifyNoInteractions(reports);
    }

    @Test
    void mismatchedTenantOrParentResultCannotBeAcknowledged() {
        for (var row : List.of(child(ACTOR, PARENT), child(TENANT, SOURCE))) {
            when(reports.revise(any(), anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(row);
            assertEquals(
                "REPORT_PUBLICATION_UNAVAILABLE",
                assertThrows(DomainException.class, () -> controller.revise(PARENT, body(), request())).code
            );
        }
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/report-publications/" + PARENT + "/revisions");
        request.setUserPrincipal(() -> ACTOR);
        request.getSession().setAttribute("mop.tenant_id", TENANT);
        request.addHeader("Idempotency-Key", "revision-unit");
        return request;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode body() {
        return json
            .createObjectNode()
            .put("expected_version", 6)
            .put("source_id", SOURCE)
            .put("expected_source_sha256", "a".repeat(64))
            .put("reason", "修正来源");
    }

    private ReportPublicationRepository.Stored child(String tenant, String parent) {
        var at = Instant.parse("2026-10-24T00:00:00Z");
        var document = new ReportPublication.Document(
            UUID.fromString(tenant),
            UUID.fromString(CHILD),
            UUID.fromString(PARENT),
            UUID.fromString(parent),
            2,
            ReportPublication.Kind.QUARTERLY,
            ReportPublication.Scope.CUSTOMER_DELIVERY,
            "b".repeat(64),
            "c".repeat(64),
            "unit",
            "unit",
            UUID.fromString(ACTOR),
            at,
            List.of("SELF_REPORTED_SOURCES_UNVERIFIED")
        );
        var state = new ReportPublication.State(
            document,
            1,
            ReportPublication.Phase.DRAFT,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            "修正来源",
            at
        );
        byte[] payload = (
            "{\"source_id\":\"" +
            SOURCE +
            "\",\"source_document_sha256\":\"" +
            "a".repeat(64) +
            "\"}"
        ).getBytes(StandardCharsets.UTF_8);
        return new ReportPublicationRepository.Stored("SYNTHETIC revision", state, payload);
    }
}
