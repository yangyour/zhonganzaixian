package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.inspection.InspectionQuarterlyReport;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.CaptureCreation;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.Summary;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static cn.mop.api.InspectionWorksheetControllerTest.ACTOR;
import static cn.mop.api.InspectionWorksheetControllerTest.ID;
import static cn.mop.api.InspectionWorksheetControllerTest.TENANT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated input/permission checks; repository mocks do not prove database atomicity. */
class InspectionQuarterlyReportCaptureControllerTest {
    final InspectionQuarterlyReportControllerTest fixture = new InspectionQuarterlyReportControllerTest();
    InspectionQuarterlyReportCaptureController controller;

    @BeforeEach
    void setup() {
        fixture.setup();
        controller = new InspectionQuarterlyReportCaptureController(fixture.platform, fixture.repository);
        when(fixture.repository.capture(any(), anyLong(), any())).thenReturn(fixture.summary);
    }

    ObjectNode input() {
        var input = fixture.input();
        input.remove(List.of("source_collection_id", "expected_source_document_sha256", "expected_source_manifest_sha256"));
        input.putArray("sources").addObject().put("worksheet_id", ID).put("version", 2)
                .put("expected_source_sha256", "a".repeat(64));
        return input;
    }

    MockHttpServletRequest request(String body) {
        var request = fixture.request("POST");
        request.setRequestURI("/api/v1/inspection-quarterly-reports/capture");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void capturesExplicitRevisionAndKeepsUnknownRulesNull() {
        var result = fixture.data(controller.capture(request(input().toString())));
        assertEquals("ATOMIC_EXPLICIT_WORKSHEET_REVISIONS", result.get("capture_mode"));
        assertEquals("DRAFT", result.get("generation_status"));
        assertEquals(false, result.get("report_published"));
        assertNull(result.get("tenant_asset_population"));
        verify(fixture.repository).authorizeCapture(fixture.access());
        var creation = ArgumentCaptor.forClass(CaptureCreation.class);
        verify(fixture.repository).capture(eq(fixture.access()), eq(0L), creation.capture());
        assertEquals(2, creation.getValue().sources().get(0).version());
        assertEquals("a".repeat(64), creation.getValue().sources().get(0).expectedSourceSha256());
        assertEquals(InspectionQuarterlyReport.Configuration.pending(), creation.getValue().configuration());
        verify(fixture.repository, never()).create(any(), anyLong(), any());
    }

    @Test
    void explicitConfigurationIsPassedWithoutApprovingIt() {
        var input = input();
        ((ObjectNode) input.get("configuration")).put("revision", "SYNTHETIC_RULE_V1")
                .put("time_zone", "Asia/Shanghai").put("duplicate_rule", "REQUIRE_SINGLE_SOURCE")
                .put("archived_rule", "EXCLUDE").put("metric_version", "SYNTHETIC_METRIC_V1")
                .put("report_template_version", "SYNTHETIC_TEMPLATE_V1");
        var result = fixture.data(controller.capture(request(input.toString())));
        assertEquals("REQUEST_DRAFT_NOT_APPROVED", result.get("configuration_source"));
        var creation = ArgumentCaptor.forClass(CaptureCreation.class);
        verify(fixture.repository).capture(any(), eq(0L), creation.capture());
        assertEquals("Asia/Shanghai", creation.getValue().configuration().timeZone());
    }

    @Test
    void eachCaptureCapabilityIsRequiredBeforeReadingInput() {
        for (String missing : InspectionQuarterlyReportCaptureController.CAPABILITIES) {
            var capabilities = new ArrayList<>(InspectionQuarterlyReportCaptureController.CAPABILITIES);
            capabilities.remove(missing);
            when(fixture.platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(
                    new Membership(ACTOR, TENANT, "padmin", 7, true, capabilities)));
            assertEquals(403, assertThrows(DomainException.class,
                    () -> controller.capture(request("not json"))).status);
        }
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void missingOrRevokedSessionIsRejected() {
        assertEquals(401, assertThrows(DomainException.class,
                () -> controller.capture(new MockHttpServletRequest())).status);
        when(fixture.platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(
                new Membership(ACTOR, TENANT, "padmin", 7, false)));
        assertEquals(401, assertThrows(DomainException.class,
                () -> controller.capture(request(input().toString()))).status);
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void receiptReplayRechecksCapturePermission() {
        doThrow(new DomainException(403, "ACTION_FORBIDDEN", "revoked"))
                .when(fixture.repository).authorizeCapture(any());
        assertEquals(403, assertThrows(DomainException.class,
                () -> controller.capture(request(input().toString()))).status);
        verify(fixture.repository, never()).capture(any(), anyLong(), any());
    }

    @Test
    void rejectsForgedScopeComputedOutputOrPublication() {
        for (String field : List.of("tenant_id", "report_published", "summary", "source_collection_id")) {
            assertEquals(400, assertThrows(DomainException.class,
                    () -> controller.capture(request(input().put(field, "forged").toString()))).status);
        }
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void rejectsMissingVersionHashAndDuplicateSources() {
        var duplicate = input();
        duplicate.withArray("sources").add(duplicate.get("sources").get(0).deepCopy());
        var missingHash = input();
        ((ObjectNode) missingHash.get("sources").get(0)).remove("expected_source_sha256");
        var zeroVersion = input();
        ((ObjectNode) zeroVersion.get("sources").get(0)).put("version", 0);
        for (var value : List.of(duplicate, missingHash, zeroVersion, input().put("expected_version", 1))) {
            assertEquals(400, assertThrows(DomainException.class,
                    () -> controller.capture(request(value.toString()))).status);
        }
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void rejectsFractionalOverflowPeriodAndUnknownPolicy() {
        var policy = input();
        ((ObjectNode) policy.get("configuration")).put("duplicate_rule", "GUESS");
        for (var value : List.of(input().put("year", 2026.0), input().put("year", Long.MAX_VALUE),
                input().put("quarter", 0), policy)) {
            assertEquals(400, assertThrows(DomainException.class,
                    () -> controller.capture(request(value.toString()))).status);
        }
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void rejectsDuplicateKeysTrailingJsonAndOversizeBeforeMutation() {
        for (String raw : List.of("null", "{} {}", "{\"year\":2026,\"year\":2025}", "[[[[[{}]]]]]")) {
            assertEquals(400, assertThrows(DomainException.class, () -> controller.capture(request(raw))).status);
        }
        assertEquals(413, assertThrows(DomainException.class,
                () -> controller.capture(request(" ".repeat(InspectionQuarterlyReportCaptureController.MAX_BODY_BYTES + 1)))).status);
        var many = input();
        for (int i = 0; i < 1000; i++) {
            many.withArray("sources").add(many.get("sources").get(0).deepCopy());
        }
        assertEquals(413, assertThrows(DomainException.class, () -> controller.capture(request(many.toString()))).status);
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void rejectsQueryOverrideAndAbsentOrRepeatedIdempotencyKeys() {
        var query = request(input().toString());
        query.addParameter("tenant_id", TENANT);
        var missing = request(input().toString());
        missing.removeHeader("Idempotency-Key");
        var repeated = request(input().toString());
        repeated.addHeader("Idempotency-Key", "another");
        for (var request : List.of(query, missing, repeated)) {
            assertEquals(400, assertThrows(DomainException.class, () -> controller.capture(request)).status);
        }
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void hashIgnoresObjectOrderButIncludesSourceHashAndConfiguration() {
        var original = input();
        var reordered = fixture.mapper.createObjectNode();
        var keys = new ArrayList<String>();
        original.fieldNames().forEachRemaining(keys::add);
        Collections.reverse(keys);
        keys.forEach(key -> reordered.set(key, original.get(key)));
        var changed = input();
        ((ObjectNode) changed.get("sources").get(0)).put("expected_source_sha256", "b".repeat(64));
        var configured = input();
        ((ObjectNode) configured.get("configuration")).put("revision", "other");
        for (var value : List.of(original, reordered, changed, configured)) {
            controller.capture(request(value.toString()));
        }
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(fixture.platform, times(4)).idempotentAuthorized(any(), any(), any(), any(), hashes.capture(),
                anyLong(), any(), any());
        assertEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(1));
        assertNotEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(2));
        assertNotEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(3));
    }

    @Test
    void crossTenantMissingHashConflictAndUnavailableAreNotConvertedToSuccess() {
        for (int status : List.of(404, 409, 503)) {
            doThrow(new DomainException(status, "SYNTHETIC", "test"))
                    .when(fixture.repository).capture(any(), anyLong(), any());
            assertEquals(status, assertThrows(DomainException.class,
                    () -> controller.capture(request(input().toString()))).status);
        }
    }

    @Test
    void wrongTenantRepositoryResultFailsClosed() {
        Summary original = fixture.summary;
        when(fixture.repository.capture(any(), anyLong(), any())).thenReturn(new Summary(original.id(), ID,
                original.createdBy(), original.recordedAt(), 1, original.label(), original.sourceCollectionId(),
                original.sourceDocumentSha256(), original.sourceManifestSha256(), 2026, 4,
                original.reportSha256(), original.documentSha256()));
        assertEquals(503, assertThrows(DomainException.class,
                () -> controller.capture(request(input().toString()))).status);
    }
}
