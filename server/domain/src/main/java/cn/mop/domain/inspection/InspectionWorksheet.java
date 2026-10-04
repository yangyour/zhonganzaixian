package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable manual worksheet candidate tied to a frozen template. No scan, publication, authorization or persistence. */
public record InspectionWorksheet(UUID tenantId, UUID assetId, String assetName,
                                  InspectionTemplate.Snapshot template, Map<String, Observation> observations) {
    public enum Result {
        NORMAL("正常"), ATTENTION("建议关注"), REQUIRES_ACTION("需处理");
        private final String label;
        Result(String label) { this.label = label; }
        public String label() { return label; }
    }
    /** Evidence UUIDs are unverified references, not grants, upload successes or verified file content. */
    public record Observation(Result result, String note, List<UUID> evidenceIds) {
        public Observation {
            if (result == null || evidenceIds == null || evidenceIds.size() > 10 || evidenceIds.stream().anyMatch(id -> id == null) || new HashSet<>(evidenceIds).size() != evidenceIds.size()) throw InspectionTemplate.invalid();
            InspectionTemplate.text(note, 3000, true, true); evidenceIds = List.copyOf(evidenceIds);
        }
        public String source() { return "MANUAL_OBSERVATION"; }
        public String verificationStatus() { return "SELF_REPORTED_UNVERIFIED"; }
        public String evidenceStatus() { return "REFERENCES_NOT_VERIFIED"; }
    }
    public InspectionWorksheet {
        if (tenantId == null || assetId == null || template == null || observations == null) throw InspectionTemplate.invalid();
        if (!tenantId.equals(template.tenantId())) throw new DomainException(404, "RESOURCE_NOT_FOUND", "巡检模板不属于当前范围");
        InspectionTemplate.text(assetName, 200, false, false);
        var allowed = new HashSet<String>(); template.definition().items().forEach(item -> allowed.add(item.key()));
        if (observations.size() > allowed.size()) throw InspectionTemplate.invalid();
        for (var entry : observations.entrySet()) if (!allowed.contains(entry.getKey()) || entry.getValue() == null) throw InspectionTemplate.invalid();
        observations = Map.copyOf(observations);
    }
    public static InspectionWorksheet empty(UUID tenantId, UUID assetId, String assetName, InspectionTemplate.Snapshot template) {
        return new InspectionWorksheet(tenantId, assetId, assetName, template, Map.of());
    }
    public InspectionWorksheet record(String itemKey, Observation observation) {
        if (observation == null) throw InspectionTemplate.invalid();
        var next = new LinkedHashMap<>(observations); next.put(itemKey, observation);
        return new InspectionWorksheet(tenantId, assetId, assetName, template, next);
    }
    public InspectionWorksheet clear(String itemKey) {
        if (template.definition().items().stream().noneMatch(item -> item.key().equals(itemKey))) throw InspectionTemplate.invalid();
        var next = new LinkedHashMap<>(observations); next.remove(itemKey);
        return new InspectionWorksheet(tenantId, assetId, assetName, template, next);
    }
    /** Display order comes from the captured definition, never map order or a newer template. Null means not entered. */
    public record Row(InspectionTemplate.Item item, Observation observation) { }
    public List<Row> rows() {
        var result = new ArrayList<Row>();
        for (var item : template.definition().items()) result.add(new Row(item, observations.get(item.key())));
        return List.copyOf(result);
    }
    public record Summary(int total, int answered, int normal, int attention, int requiresAction, int unanswered) {
        public boolean allResultsEntered() { return unanswered == 0; }
    }
    /** No caller-supplied numbers: totals are always derived from the actual rows, without converting missing values to normal. */
    public Summary summary() {
        int normal = 0, attention = 0, action = 0;
        for (var observation : observations.values()) switch (observation.result()) { case NORMAL -> normal++; case ATTENTION -> attention++; case REQUIRES_ACTION -> action++; }
        return new Summary(template.definition().items().size(), observations.size(), normal, attention, action, template.definition().items().size() - observations.size());
    }
    public String verificationStatus() { return "SELF_REPORTED_UNVERIFIED"; }
    public String templateApprovalStatus() { return template.definition().approvalStatus(); }
    public boolean reportPublishable() { return false; }
    public boolean activeScanPerformed() { return false; }
}
