package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Pure, reproducible draft aggregation of an already frozen source manifest. No live-head lookups,
 * source discovery, attachment authorization, persistence or publication are performed here.
 * Configuration values are supplied explicitly; their presence is not proof of customer approval.
 */
public final class InspectionQuarterlyReport {
    public static final String FORMAT = "MOP_INSPECTION_QUARTERLY_DRAFT_V1";
    public enum DuplicateRule { REQUIRE_SINGLE_SOURCE, LATEST_REPORTED_PERFORMED_AT }
    public enum ArchivedRule { INCLUDE, EXCLUDE }
    public enum Completeness { UNKNOWN, INCOMPLETE, COMPLETE_EXPLICIT_SELECTION }
    public enum SelectionStatus { SELECTED, NO_SOURCE_IN_PERIOD, CONFIG_PENDING, SOURCE_TIME_UNKNOWN, ARCHIVE_POLICY_PENDING, MULTIPLE_SOURCES, LATEST_TIME_TIED }
    public enum PendingReason { TIME_ZONE_MISSING, DUPLICATE_RULE_MISSING, ARCHIVE_RULE_MISSING, METRIC_VERSION_MISSING, REPORT_TEMPLATE_VERSION_MISSING, CONFIG_REVISION_MISSING, SOURCE_SET_UNKNOWN, SOURCE_SET_INCOMPLETE, EMPTY_SELECTION, UNRESOLVED_ASSET_SELECTION, UNANSWERED_ITEMS, SELF_REPORTED_SOURCES_UNVERIFIED, TEMPLATE_APPROVAL_UNVERIFIED, EVIDENCE_REFERENCES_UNVERIFIED, PUBLICATION_PIPELINE_NOT_CONNECTED }

    /** Null means not configured. No fallback timezone, archive policy, or newest/worst rule. */
    public record Configuration(String revision, String timeZone, DuplicateRule duplicateRule,
                                ArchivedRule archivedRule, String metricVersion, String reportTemplateVersion) {
        public Configuration {
            optional(revision); optional(metricVersion); optional(reportTemplateVersion);
            if (timeZone != null) {
                InspectionTemplate.text(timeZone, 100, false, false);
                try { ZoneId.of(timeZone); } catch (DateTimeException invalid) { throw invalid(); }
            }
        }
        public static Configuration pending() { return new Configuration(null, null, null, null, null, null); }
        private static void optional(String value) { if (value != null) InspectionTemplate.text(value, 200, false, false); }
    }
    public record Period(int year, int quarter) {
        public Period { if (year < 1 || year > 9998 || quarter < 1 || quarter > 4) throw invalid(); }
        public Instant start(String zone) { return LocalDate.of(year, (quarter - 1) * 3 + 1, 1).atStartOfDay(ZoneId.of(zone)).toInstant(); }
        public Instant endExclusive(String zone) { return LocalDate.of(year, (quarter - 1) * 3 + 1, 1).plusMonths(3).atStartOfDay(ZoneId.of(zone)).toInstant(); }
    }
    /** Candidates retain all captured revisions, including excluded ones, for auditable drilldown. */
    public record AssetRow(UUID assetId, SelectionStatus status, List<InspectionSourceSnapshot> candidates,
                           InspectionSourceSnapshot selected) {
        public AssetRow { candidates = List.copyOf(candidates); }
        public List<InspectionWorksheet.Row> items() { return selected == null ? List.of() : selected.content().worksheet().rows(); }
        public InspectionWorksheet.Summary summary() { return selected == null ? null : selected.content().worksheet().summary(); }
        public String assetName() { return selected == null ? null : selected.content().worksheet().assetName(); }
    }
    public record Finding(UUID assetId, InspectionSourceSnapshot.Reference source, InspectionTemplate.Item item,
                          InspectionWorksheet.Observation observation) { }
    /** Counts only selected frozen rows; never an inspected tenant population or a compliance rate. */
    public record Summary(int capturedAssets, int selectedAssets, int unresolvedAssets, int totalItems,
                          int answered, int normal, int attention, int requiresAction, int unanswered) { }

    private final InspectionSourceManifest manifest;
    private final Period period;
    private final Configuration configuration;
    private final Completeness completeness;
    private final List<AssetRow> rows;
    private final List<PendingReason> pendingReasons;
    private final Summary summary;
    private final List<Finding> findings;

    private InspectionQuarterlyReport(InspectionSourceManifest manifest, Period period,
                                      Configuration configuration, Completeness completeness) {
        this.manifest = manifest;
        this.period = period;
        this.configuration = configuration;
        this.completeness = completeness;
        var grouped = new TreeMap<UUID, List<InspectionSourceSnapshot>>(Comparator.comparing(UUID::toString));
        for (var source : manifest.sources()) grouped.computeIfAbsent(source.content().worksheet().assetId(), ignored -> new ArrayList<>()).add(source);
        var result = new ArrayList<AssetRow>();
        grouped.forEach((asset, candidates) -> result.add(select(asset, candidates)));
        rows = List.copyOf(result);
        int selected = 0, unresolved = 0, total = 0, answered = 0, normal = 0, attention = 0, action = 0, unanswered = 0;
        var anomalies = new ArrayList<Finding>();
        for (var row : rows) {
            if (row.selected() == null) { if (row.status() != SelectionStatus.NO_SOURCE_IN_PERIOD) unresolved++; continue; }
            selected++;
            var counts = row.summary();
            total += counts.total(); answered += counts.answered(); normal += counts.normal();
            attention += counts.attention(); action += counts.requiresAction(); unanswered += counts.unanswered();
            for (var item : row.items()) if (item.observation() != null && item.observation().result() != InspectionWorksheet.Result.NORMAL)
                anomalies.add(new Finding(row.assetId(), row.selected().reference(), item.item(), item.observation()));
        }
        summary = new Summary(rows.size(), selected, unresolved, total, answered, normal, attention, action, unanswered);
        findings = List.copyOf(anomalies);
        var pending = new ArrayList<PendingReason>();
        if (configuration.timeZone() == null) pending.add(PendingReason.TIME_ZONE_MISSING);
        if (configuration.duplicateRule() == null) pending.add(PendingReason.DUPLICATE_RULE_MISSING);
        if (configuration.archivedRule() == null) pending.add(PendingReason.ARCHIVE_RULE_MISSING);
        if (configuration.metricVersion() == null) pending.add(PendingReason.METRIC_VERSION_MISSING);
        if (configuration.reportTemplateVersion() == null) pending.add(PendingReason.REPORT_TEMPLATE_VERSION_MISSING);
        if (configuration.revision() == null) pending.add(PendingReason.CONFIG_REVISION_MISSING);
        if (completeness == Completeness.UNKNOWN) pending.add(PendingReason.SOURCE_SET_UNKNOWN);
        if (completeness == Completeness.INCOMPLETE) pending.add(PendingReason.SOURCE_SET_INCOMPLETE);
        if (selected == 0) pending.add(PendingReason.EMPTY_SELECTION);
        if (unresolved > 0) pending.add(PendingReason.UNRESOLVED_ASSET_SELECTION);
        if (unanswered > 0) pending.add(PendingReason.UNANSWERED_ITEMS);
        if (selected > 0) {
            pending.add(PendingReason.SELF_REPORTED_SOURCES_UNVERIFIED);
            pending.add(PendingReason.TEMPLATE_APPROVAL_UNVERIFIED);
        }
        if (rows.stream().filter(r -> r.selected() != null).flatMap(r -> r.items().stream())
                .anyMatch(r -> r.observation() != null && !r.observation().evidenceIds().isEmpty())) pending.add(PendingReason.EVIDENCE_REFERENCES_UNVERIFIED);
        pending.add(PendingReason.PUBLICATION_PIPELINE_NOT_CONNECTED);
        pendingReasons = List.copyOf(pending);
    }

    public static InspectionQuarterlyReport generate(UUID authorizedTenant, InspectionSourceManifest manifest,
                                                     Period period, Configuration configuration, Completeness completeness) {
        if (authorizedTenant == null || manifest == null) throw invalid();
        // Scope check precedes report options; never leak another tenant's source details in diagnostics.
        if (!authorizedTenant.equals(manifest.tenantId())) throw new DomainException(404, "OBJECT_NOT_FOUND", "巡检来源不属于当前租户");
        if (period == null || configuration == null || completeness == null) throw invalid();
        return new InspectionQuarterlyReport(manifest, period, configuration, completeness);
    }

    private AssetRow select(UUID asset, List<InspectionSourceSnapshot> candidates) {
        if (configuration.timeZone() == null) return row(asset, candidates, SelectionStatus.CONFIG_PENDING, null);
        Instant start = period.start(configuration.timeZone()), end = period.endExclusive(configuration.timeZone());
        var eligible = new ArrayList<InspectionSourceSnapshot>();
        boolean unknownTime = false, unknownArchive = false;
        for (var source : candidates) {
            // Known exclusions may be resolved before unknown facts, but no missing fact is guessed.
            if (source.revisionArchived() && configuration.archivedRule() == ArchivedRule.EXCLUDE) continue;
            Instant performed = source.content().reportedPerformedAt();
            if (performed != null && (performed.isBefore(start) || !performed.isBefore(end))) continue;
            if (source.revisionArchived() && configuration.archivedRule() == null) unknownArchive = true;
            if (performed == null) unknownTime = true;
            eligible.add(source);
        }
        if (unknownArchive) return row(asset, candidates, SelectionStatus.ARCHIVE_POLICY_PENDING, null);
        if (unknownTime) return row(asset, candidates, SelectionStatus.SOURCE_TIME_UNKNOWN, null);
        if (eligible.isEmpty()) return row(asset, candidates, SelectionStatus.NO_SOURCE_IN_PERIOD, null);
        if (eligible.size() == 1) return row(asset, candidates, SelectionStatus.SELECTED, eligible.get(0));
        if (configuration.duplicateRule() == null) return row(asset, candidates, SelectionStatus.CONFIG_PENDING, null);
        if (configuration.duplicateRule() == DuplicateRule.REQUIRE_SINGLE_SOURCE) return row(asset, candidates, SelectionStatus.MULTIPLE_SOURCES, null);
        Instant latest = eligible.stream().map(s -> s.content().reportedPerformedAt()).max(Comparator.naturalOrder()).orElseThrow();
        var latestSources = eligible.stream().filter(s -> latest.equals(s.content().reportedPerformedAt())).toList();
        if (latestSources.size() != 1) return row(asset, candidates, SelectionStatus.LATEST_TIME_TIED, null);
        return row(asset, candidates, SelectionStatus.SELECTED, latestSources.get(0));
    }
    private static AssetRow row(UUID asset, List<InspectionSourceSnapshot> sources, SelectionStatus status, InspectionSourceSnapshot selected) {
        return new AssetRow(asset, status, sources, selected);
    }
    public UUID tenantId() { return manifest.tenantId(); }
    public InspectionSourceManifest manifest() { return manifest; }
    public Period period() { return period; }
    public Configuration configuration() { return configuration; }
    public Completeness completeness() { return completeness; }
    public List<AssetRow> rows() { return rows; }
    public Summary summary() { return summary; }
    public List<Finding> findings() { return findings; }
    public List<PendingReason> pendingReasons() { return pendingReasons; }
    public String generationStatus() { return "DRAFT"; }
    public boolean reportPublishable() { return false; }
    public boolean reportPublished() { return false; }
    public boolean evidenceAccessGranted() { return false; }
    public Integer tenantAssetPopulation() { return null; }
    /** Versioned deterministic input envelope. Full frozen source bodies remain in manifest(). */
    public byte[] canonicalBytes() {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            InspectionSourceSnapshot.field(out, FORMAT);
            InspectionSourceSnapshot.field(out, manifest.fingerprint());
            out.writeInt(period.year()); out.writeInt(period.quarter());
            for (String value : new String[]{configuration.revision(), configuration.timeZone(),
                    name(configuration.duplicateRule()), name(configuration.archivedRule()), configuration.metricVersion(),
                    configuration.reportTemplateVersion(), completeness.name()}) InspectionSourceSnapshot.field(out, value);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Inspection report encoding unavailable", impossible); }
    }
    public String fingerprint() { return InspectionSourceSnapshot.sha256(canonicalBytes()); }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "巡检报告期次、配置或来源完整性无效"); }
}
