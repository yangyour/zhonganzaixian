package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionQuarterlyReport.*;
import cn.mop.domain.inspection.InspectionWorksheet.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated pure computation tests, not database, storage, HTTP or customer acceptance. */
class InspectionQuarterlyReportTest {
    private UUID id(int n) { return UUID.fromString("7b000000-0000-4000-8000-" + String.format("%012x", n)); }
    private final Instant recorded = Instant.parse("2026-10-19T00:00:00Z");
    private final Period quarter = new Period(2026, 3);
    private Configuration config(DuplicateRule rule, ArchivedRule archive) {
        return new Configuration("test-config-v1", "UTC", rule, archive, "test-metric-v1", "test-template-v1");
    }
    private InspectionSourceSnapshot source(int sheet, int asset, String performed, boolean archived, Map<String, Observation> observations) {
        var definition = new InspectionTemplate("router", "合成模板", List.of(
                new InspectionTemplate.Item("security", "安全", "安全检查", null),
                new InspectionTemplate.Item("line", "线路", "线路质量", "记录实际结果")));
        var worksheet = new InspectionWorksheet(id(1), id(asset), "合成设备" + asset,
                InspectionTemplate.Snapshot.capture(id(1), id(90), 1, definition), observations);
        return new InspectionSourceSnapshot(id(1), id(sheet), archived ? 2 : 1, id(99), recorded,
                id(99), recorded, archived ? "ARCHIVE" : "CREATE", archived,
                new InspectionWorksheetContent(worksheet, performed == null ? null : Instant.parse(performed)));
    }
    private Observation observation(Result result) { return new Observation(result, "原始备注", List.of()); }
    private InspectionSourceSnapshot source(int sheet, int asset, String performed) {
        return source(sheet, asset, performed, false, Map.of("security", observation(Result.NORMAL)));
    }
    private InspectionQuarterlyReport report(Configuration config, InspectionSourceSnapshot... sources) {
        return InspectionQuarterlyReport.generate(id(1), new InspectionSourceManifest(id(1), List.of(sources)), quarter, config, Completeness.COMPLETE_EXPLICIT_SELECTION);
    }
    private InspectionQuarterlyReport report(InspectionSourceSnapshot... sources) {
        return report(config(DuplicateRule.REQUIRE_SINGLE_SOURCE, ArchivedRule.EXCLUDE), sources);
    }
    @Test void generatesOneRowPerAssetAndDerivesUnansweredWithoutInventingNormal() {
        var value = report(source(10, 4, "2026-07-01T00:00:00Z"), source(11, 3, "2026-09-30T23:59:59.999Z"));
        assertEquals(List.of(id(3), id(4)), value.rows().stream().map(AssetRow::assetId).toList());
        assertEquals(new InspectionQuarterlyReport.Summary(2, 2, 0, 4, 2, 2, 0, 0, 2), value.summary());
        assertNull(value.rows().get(0).items().get(1).observation());
        assertTrue(value.pendingReasons().contains(PendingReason.UNANSWERED_ITEMS));
    }
    @Test void findingsKeepFrozenItemDefinitionNotesEvidenceAndExactRevisionDrilldown() {
        var evidence = id(80);
        var source = source(10, 3, "2026-07-02T00:00:00Z", false, Map.of(
                "security", new Observation(Result.ATTENTION, "核查证书", List.of(evidence)),
                "line", observation(Result.REQUIRES_ACTION)));
        var value = report(source);
        assertEquals(2, value.findings().size());
        assertEquals(source.reference(), value.findings().get(0).source());
        assertEquals("安全检查", value.findings().get(0).item().label());
        assertEquals("核查证书", value.findings().get(0).observation().note());
        assertEquals(List.of(evidence), value.findings().get(0).observation().evidenceIds());
        assertTrue(value.pendingReasons().contains(PendingReason.EVIDENCE_REFERENCES_UNVERIFIED));
        assertFalse(value.evidenceAccessGranted());
    }
    @Test void quarterUsesHalfOpenBoundsNotRecordTime() {
        var value = report(source(10, 3, "2026-06-30T23:59:59.999Z"), source(11, 4, "2026-10-01T00:00:00Z"), source(12, 5, "2026-07-01T00:00:00Z"));
        assertEquals(1, value.summary().selectedAssets());
        assertEquals(0, value.summary().unresolvedAssets());
        assertEquals(SelectionStatus.NO_SOURCE_IN_PERIOD, value.rows().get(0).status());
    }
    @Test void explicitTimezoneChangesActualQuarterBoundaries() {
        var source = source(10, 3, "2026-06-30T16:00:00Z");
        assertEquals(0, report(source).summary().selectedAssets());
        var config = new Configuration("test", "Asia/Shanghai", DuplicateRule.REQUIRE_SINGLE_SOURCE, ArchivedRule.EXCLUDE, "test", "test");
        assertEquals(1, report(config, source).summary().selectedAssets());
    }
    @Test void timezoneQuarterBoundsHonorDstAndYearRollover() {
        assertEquals(Instant.parse("2026-01-01T05:00:00Z"), new Period(2026, 1).start("America/New_York"));
        assertEquals(Instant.parse("2026-04-01T04:00:00Z"), new Period(2026, 1).endExclusive("America/New_York"));
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), new Period(2026, 4).endExclusive("UTC"));
    }
    @Test void missingConfigurationRemainsExplicitAndDoesNotSelectByRecordingTime() {
        var value = report(Configuration.pending(), source(10, 3, "2026-08-01T00:00:00Z"));
        assertEquals(SelectionStatus.CONFIG_PENDING, value.rows().get(0).status());
        assertEquals(0, value.summary().normal());
        assertTrue(value.pendingReasons().containsAll(List.of(PendingReason.TIME_ZONE_MISSING, PendingReason.DUPLICATE_RULE_MISSING,
                PendingReason.ARCHIVE_RULE_MISSING, PendingReason.METRIC_VERSION_MISSING, PendingReason.REPORT_TEMPLATE_VERSION_MISSING, PendingReason.CONFIG_REVISION_MISSING)));
    }
    @Test void multipleSourcesRemainAmbiguousWithSingleSourceRule() {
        var value = report(source(10, 3, "2026-07-01T00:00:00Z"), source(11, 3, "2026-08-01T00:00:00Z"));
        assertEquals(1, value.rows().size());
        assertEquals(SelectionStatus.MULTIPLE_SOURCES, value.rows().get(0).status());
        assertNull(value.rows().get(0).summary());
        assertNull(value.rows().get(0).assetName());
        assertEquals(2, value.rows().get(0).candidates().size());
    }
    @Test void duplicateSelectionRequiresExplicitRuleAndNeverUsesWorstResult() {
        var first = source(10, 3, "2026-07-01T00:00:00Z", false, Map.of("security", observation(Result.REQUIRES_ACTION)));
        var second = source(11, 3, "2026-08-01T00:00:00Z");
        assertEquals(SelectionStatus.CONFIG_PENDING, report(config(null, ArchivedRule.EXCLUDE), first, second).rows().get(0).status());
        var value = report(config(DuplicateRule.LATEST_REPORTED_PERFORMED_AT, ArchivedRule.EXCLUDE), second, first);
        assertEquals(second, value.rows().get(0).selected());
        assertEquals(0, value.findings().size());
        assertEquals(2, value.rows().get(0).candidates().size());
    }
    @Test void latestTimeTieDoesNotUseUuidVersionOrRecordTimeAsUndocumentedTiebreaker() {
        var value = report(config(DuplicateRule.LATEST_REPORTED_PERFORMED_AT, ArchivedRule.INCLUDE),
                source(10, 3, "2026-08-01T00:00:00Z"), source(11, 3, "2026-08-01T00:00:00Z", true, Map.of()));
        assertEquals(SelectionStatus.LATEST_TIME_TIED, value.rows().get(0).status());
        assertNull(value.rows().get(0).selected());
    }
    @Test void unknownPerformedTimeBlocksOtherwiseKnownCandidateEvenWithLatestRule() {
        var value = report(config(DuplicateRule.LATEST_REPORTED_PERFORMED_AT, ArchivedRule.EXCLUDE), source(10, 3, null), source(11, 3, "2026-08-01T00:00:00Z"));
        assertEquals(SelectionStatus.SOURCE_TIME_UNKNOWN, value.rows().get(0).status());
        assertEquals(0, value.summary().answered());
        assertTrue(value.pendingReasons().contains(PendingReason.UNRESOLVED_ASSET_SELECTION));
    }
    @Test void archivedPolicyIsExplicitAndAppliesToCapturedRevisionNotLiveHead() {
        var archived = source(10, 3, "2026-08-01T00:00:00Z", true, Map.of());
        assertEquals(SelectionStatus.ARCHIVE_POLICY_PENDING, report(config(DuplicateRule.REQUIRE_SINGLE_SOURCE, null), archived).rows().get(0).status());
        assertEquals(SelectionStatus.NO_SOURCE_IN_PERIOD, report(archived).rows().get(0).status());
        assertEquals(SelectionStatus.SELECTED, report(config(DuplicateRule.REQUIRE_SINGLE_SOURCE, ArchivedRule.INCLUDE), archived).rows().get(0).status());
    }
    @Test void knownExcludedArchiveDoesNotPoisonSelectionWithUnknownTime() {
        var archived = source(10, 3, null, true, Map.of());
        var active = source(11, 3, "2026-08-01T00:00:00Z");
        assertEquals(active, report(archived, active).rows().get(0).selected());
    }
    @Test void knownOutOfPeriodArchiveDoesNotRequirePolicyToResolveSelectedRow() {
        var archive = source(10, 3, "2026-06-01T00:00:00Z", true, Map.of());
        var active = source(11, 3, "2026-08-01T00:00:00Z");
        var value = report(config(DuplicateRule.REQUIRE_SINGLE_SOURCE, null), archive, active);
        assertEquals(active, value.rows().get(0).selected());
        assertTrue(value.pendingReasons().contains(PendingReason.ARCHIVE_RULE_MISSING));
    }
    @Test void explicitSelectionCompletenessNeverBecomesTenantPopulationOrPublication() {
        var source = source(10, 3, "2026-08-01T00:00:00Z", false, Map.of("security", observation(Result.NORMAL), "line", observation(Result.NORMAL)));
        var value = report(source);
        assertNull(value.tenantAssetPopulation()); assertFalse(value.reportPublishable()); assertFalse(value.reportPublished());
        assertEquals("DRAFT", value.generationStatus());
        assertTrue(value.pendingReasons().containsAll(List.of(PendingReason.SELF_REPORTED_SOURCES_UNVERIFIED, PendingReason.TEMPLATE_APPROVAL_UNVERIFIED, PendingReason.PUBLICATION_PIPELINE_NOT_CONNECTED)));
    }
    @Test void incompleteAndUnknownSourceSetsAreKeptEvenWhenEverySelectedItemIsFilled() {
        var manifest = new InspectionSourceManifest(id(1), List.of(source(10, 3, "2026-08-01T00:00:00Z")));
        for (var completeness : List.of(Completeness.UNKNOWN, Completeness.INCOMPLETE)) {
            var value = InspectionQuarterlyReport.generate(id(1), manifest, quarter, config(DuplicateRule.REQUIRE_SINGLE_SOURCE, ArchivedRule.EXCLUDE), completeness);
            assertTrue(value.pendingReasons().contains(completeness == Completeness.UNKNOWN ? PendingReason.SOURCE_SET_UNKNOWN : PendingReason.SOURCE_SET_INCOMPLETE));
            assertEquals(1, value.summary().selectedAssets());
        }
    }
    @Test void emptyManifestIsEmptyDraftNotHealthyPopulation() {
        var value = report();
        assertEquals(0, value.summary().capturedAssets()); assertNull(value.tenantAssetPopulation());
        assertTrue(value.pendingReasons().contains(PendingReason.EMPTY_SELECTION)); assertFalse(value.reportPublishable());
    }
    @Test void deterministicAcrossSourceOrderAndDefensivelyImmutable() {
        var first = source(10, 3, "2026-07-01T00:00:00Z"); var second = source(11, 4, "2026-08-01T00:00:00Z");
        var value = report(first, second); var reverse = report(second, first);
        assertEquals(value.rows(), reverse.rows()); assertEquals(value.fingerprint(), reverse.fingerprint());
        assertArrayEquals(value.canonicalBytes(), reverse.canonicalBytes());
        assertThrows(UnsupportedOperationException.class, () -> value.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.rows().get(0).candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.pendingReasons().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.findings().clear());
        var hash = value.fingerprint(); Arrays.fill(value.canonicalBytes(), (byte) 0); assertEquals(hash, value.fingerprint());
    }
    @Test void digestBindsPeriodRulesCompletenessAndFullSourceDefinition() {
        var source = source(10, 3, "2026-08-01T00:00:00Z"); var value = report(source);
        assertNotEquals(value.fingerprint(), report(config(DuplicateRule.LATEST_REPORTED_PERFORMED_AT, ArchivedRule.EXCLUDE), source).fingerprint());
        assertNotEquals(value.fingerprint(), report(source(10, 3, "2026-08-01T00:00:00Z", false, Map.of())).fingerprint());
        assertNotEquals(value.fingerprint(), InspectionQuarterlyReport.generate(id(1), value.manifest(), new Period(2026, 4), value.configuration(), value.completeness()).fingerprint());
        assertNotEquals(value.fingerprint(), InspectionQuarterlyReport.generate(id(1), value.manifest(), quarter, value.configuration(), Completeness.UNKNOWN).fingerprint());
    }
    @Test void newerWorksheetAndTemplateCannotChangeAlreadyGeneratedReport() {
        var old = source(10, 3, "2026-08-01T00:00:00Z"); var report = report(old); var hash = report.fingerprint();
        var changed = old.content().worksheet().record("security", observation(Result.REQUIRES_ACTION));
        assertNotEquals(old.content().fingerprint(), new InspectionWorksheetContent(changed, old.content().reportedPerformedAt()).fingerprint());
        assertEquals(hash, report.fingerprint()); assertEquals(1, report.summary().normal()); assertTrue(report.findings().isEmpty());
    }
    @Test void crossTenantFails404BeforeInvalidReportOptions() {
        var manifest = new InspectionSourceManifest(id(1), List.of(source(10, 3, null)));
        var error = assertThrows(DomainException.class, () -> InspectionQuarterlyReport.generate(id(2), manifest, null, null, null));
        assertEquals(404, error.status); assertEquals("OBJECT_NOT_FOUND", error.code);
    }
    @Test void independentPythonGoldenLocksVersionedCanonicalEnvelope() {
        assertEquals(243, report().canonicalBytes().length);
        assertEquals("013abf13f75a607a3f05c06b51c0a6cf5d677d57f003c17c91dab3ffb6684a54", report().fingerprint());
    }
    @Test void templateEvolutionCannotReplaceFrozenLabelsOrAddResultsToHistoricalReport() {
        var source = source(10, 3, "2026-08-01T00:00:00Z"); var value = report(source);
        var revised = new InspectionTemplate("router", "新模板", List.of(new InspectionTemplate.Item("security", "新分组", "新版标签", null)));
        var snapshot = InspectionTemplate.Snapshot.capture(id(1), id(90), 2, revised);
        assertNotEquals(source.content().worksheet().template().contentSha256(), snapshot.contentSha256());
        assertEquals("安全检查", value.rows().get(0).items().get(0).item().label());
        assertEquals(2, value.summary().totalItems());
        assertEquals(1, value.rows().get(0).selected().content().worksheet().template().version());
    }
    @Test void rejectsInvalidInputsRatherThanDefaulting() {
        for (int quarter : new int[]{0, 5}) assertThrows(DomainException.class, () -> new Period(2026, quarter));
        for (int year : new int[]{0, 9999}) assertThrows(DomainException.class, () -> new Period(year, 1));
        assertThrows(DomainException.class, () -> new Configuration("test", "Not/A_Zone", null, null, null, null));
        assertThrows(DomainException.class, () -> new Configuration(" ", "UTC", null, null, null, null));
        assertThrows(DomainException.class, () -> InspectionQuarterlyReport.generate(id(1), new InspectionSourceManifest(id(1), List.of()), quarter, Configuration.pending(), null));
    }
}
