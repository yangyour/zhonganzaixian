package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.InspectionTemplate;
import cn.mop.domain.inspection.InspectionTemplate.Item;
import cn.mop.domain.inspection.InspectionTemplate.Snapshot;
import cn.mop.domain.inspection.InspectionWorksheet;
import cn.mop.domain.inspection.InspectionWorksheet.Observation;
import cn.mop.domain.inspection.InspectionWorksheet.Result;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InspectionTemplateTest {
    private final UUID tenant = UUID.fromString("79000000-0000-4000-8000-000000000001"), asset = UUID.fromString("79000000-0000-4000-8000-000000000002"), templateId = UUID.fromString("79000000-0000-4000-8000-000000000003");
    Item item(String key) { return new Item(key, "安全合规", "合成检查项", null); }
    InspectionTemplate template() { return new InspectionTemplate("router", "合成路由器巡检", List.of(item("firmware"), item("backup"), item("line_quality"))); }
    Snapshot snapshot() { return Snapshot.capture(tenant, templateId, 1, template()); }
    InspectionWorksheet sheet() { return InspectionWorksheet.empty(tenant, asset, "合成设备", snapshot()); }
    Observation result(Result result) { return new Observation(result, null, List.of()); }
    @Test void fourContractTypesAndAFifthNeedNoJavaTypeChange() {
        for (String type : List.of("router", "switch", "firewall", "ap", "new_customer_device_type")) {
            var definition = new InspectionTemplate(type, "显式合成模板", List.of(new Item("custom_item", "自定义分组", "客户维护检查项", "只记录人工观察，不执行扫描")));
            var sheet = InspectionWorksheet.empty(tenant, asset, "合成设备", Snapshot.capture(tenant, templateId, 1, definition));
            assertEquals(type, sheet.template().definition().deviceType()); assertEquals(1, sheet.rows().size());
        }
    }
    @Test void capturedListsAndRowsCannotBeMutatedThroughCallerReferences() {
        var items = new ArrayList<>(List.of(item("a"))); var definition = new InspectionTemplate("router", "合成", items); items.clear();
        assertEquals(1, definition.items().size()); assertThrows(UnsupportedOperationException.class, () -> definition.items().clear()); assertThrows(UnsupportedOperationException.class, () -> sheet().rows().clear());
    }
    @Test void itemKeysAreUniqueNonemptyAndBoundedWithoutNormalization() {
        for (String key : Arrays.asList(null, "", "A", "a b", "a/../b", "a".repeat(65))) assertThrows(DomainException.class, () -> item(key));
        assertThrows(DomainException.class, () -> new InspectionTemplate("router", "合成", List.of(item("a"), item("a"))));
        assertEquals("a-b_c", item("a-b_c").key());
    }
    @Test void invalidAndAmbiguousTextIsRejectedButExplicitWhitespacePreserved() {
        for (String name : Arrays.asList(null, "", "　\u00a0\ufeff", "bad\u0000", "bad\ud800", "名".repeat(201))) assertThrows(DomainException.class, () -> new InspectionTemplate("router", name, List.of(item("a"))));
        assertEquals("  合成  ", new InspectionTemplate("router", "  合成  ", List.of(item("a"))).name());
        assertDoesNotThrow(() -> new Item("a", "分类", "😀".repeat(200), "说明\r\n\t下一行"));
        assertThrows(DomainException.class, () -> new Item("a", "分类", "😀".repeat(201), null));
        assertThrows(DomainException.class, () -> new Item("a", "分类", "标签", "\u0085"));
    }
    @Test void technicalItemLimitAndNullElementsAreExplicit() {
        assertThrows(DomainException.class, () -> new InspectionTemplate("router", "合成", null)); assertThrows(DomainException.class, () -> new InspectionTemplate("router", "合成", List.of()));
        assertThrows(DomainException.class, () -> new InspectionTemplate("router", "合成", Arrays.asList(item("a"), null)));
        var items = new ArrayList<Item>(); for (int i=0; i<100; i++) items.add(item("item_"+i)); assertEquals(100, new InspectionTemplate("router", "合成", items).items().size());
        items.add(item("item_100")); assertThrows(DomainException.class, () -> new InspectionTemplate("router", "合成", items));
    }
    @Test void fingerprintIsDeterministicAndBindsExactDefinitionOrder() {
        assertEquals(template().fingerprint(), template().fingerprint()); assertTrue(template().fingerprint().matches("[0-9a-f]{64}"));
        var reversed = new ArrayList<>(template().items()); Collections.reverse(reversed);
        assertNotEquals(template().fingerprint(), new InspectionTemplate("router", template().name(), reversed).fingerprint());
        assertNotEquals(template().fingerprint(), new InspectionTemplate("switch", template().name(), template().items()).fingerprint());
        assertNotEquals(template().fingerprint(), new InspectionTemplate("router", template().name()+" ", template().items()).fingerprint());
    }
    @Test void hashFramingSeparatesNullEmptyAndFieldBoundaries() {
        var a = new InspectionTemplate("router", "合成", List.of(new Item("a", "bc", "d", null)));
        var b = new InspectionTemplate("router", "合成", List.of(new Item("a", "b", "cd", null)));
        var c = new InspectionTemplate("router", "合成", List.of(new Item("a", "bc", "d", "")));
        assertNotEquals(a.fingerprint(), b.fingerprint()); assertNotEquals(a.fingerprint(), c.fingerprint());
        assertEquals("561edb61f43839192aed9199ed87f49a3fc98554c3ed03360fdb5999fa48c2ad", a.fingerprint());
    }
    @Test void snapshotRequiresValidIdentityVersionAndMatchingHash() {
        for (long version : new long[]{0, -1, 9_007_199_254_740_991L, Long.MAX_VALUE}) assertThrows(DomainException.class, () -> Snapshot.capture(tenant, templateId, version, template()));
        assertThrows(DomainException.class, () -> Snapshot.capture(null, templateId, 1, template())); assertThrows(DomainException.class, () -> Snapshot.capture(tenant, null, 1, template())); assertThrows(DomainException.class, () -> Snapshot.capture(tenant, templateId, 1, null));
        assertThrows(DomainException.class, () -> new Snapshot(tenant, templateId, 1, template(), "a".repeat(64)));
    }
    @Test void laterTemplateRevisionDoesNotChangeAnExistingWorksheet() {
        var old = sheet(); var next = new InspectionTemplate("router", "新合成版本", List.of(item("new_item"))); var revision = Snapshot.capture(tenant, templateId, 2, next);
        assertEquals(1, old.template().version()); assertEquals(3, old.rows().size()); assertNotEquals(old.template().contentSha256(), revision.contentSha256()); assertEquals("firmware", old.rows().get(0).item().key());
    }
    @Test void emptyWorksheetRetainsNullObservationsAndAllUnknowns() {
        var sheet = sheet(); assertTrue(sheet.rows().stream().allMatch(row -> row.observation() == null));
        assertEquals(3, sheet.summary().total()); assertEquals(3, sheet.summary().unanswered()); assertEquals(0, sheet.summary().normal()); assertEquals(0, sheet.summary().answered()); assertFalse(sheet.summary().allResultsEntered());
    }
    @Test void threeContractResultsAreExplicitAndNoFourthNormalDefault() {
        assertEquals(List.of("正常", "建议关注", "需处理"), Arrays.stream(Result.values()).map(Result::label).toList());
        assertThrows(DomainException.class, () -> new Observation(null, "合成备注", List.of()));
    }
    @Test void allNumbersComeFromActualObservationsAndCompletenessIsNotApproval() {
        var sheet = sheet().record("firmware", result(Result.NORMAL)).record("backup", result(Result.ATTENTION)).record("line_quality", result(Result.REQUIRES_ACTION)); var summary = sheet.summary();
        assertEquals(3, summary.answered()); assertEquals(1, summary.normal()); assertEquals(1, summary.attention()); assertEquals(1, summary.requiresAction()); assertEquals(0, summary.unanswered()); assertTrue(summary.allResultsEntered());
        assertEquals("UNVERIFIED", sheet.templateApprovalStatus()); assertFalse(sheet.reportPublishable()); assertFalse(sheet.activeScanPerformed()); assertEquals("SELF_REPORTED_UNVERIFIED", sheet.verificationStatus());
    }
    @Test void recordingAndClearingProduceNewSnapshotsWithoutChangingHistoryInput() {
        var original = sheet(); var first = original.record("backup", result(Result.ATTENTION)); var changed = first.record("backup", result(Result.NORMAL)); var cleared = changed.clear("backup");
        assertEquals(0, original.summary().answered()); assertEquals(1, first.summary().attention()); assertEquals(1, changed.summary().normal()); assertEquals(0, cleared.summary().answered());
        assertEquals(original.template(), cleared.template());
    }
    @Test void unknownOrNullKeysCannotAddRowsAndNullResponsesAreNotCompleted() {
        for (String key : Arrays.asList(null, "", "unknown")) { assertThrows(DomainException.class, () -> sheet().record(key, result(Result.NORMAL))); assertThrows(DomainException.class, () -> sheet().clear(key)); }
        assertThrows(DomainException.class, () -> sheet().record("backup", null));
        var values = new HashMap<String, Observation>(); values.put("backup", null); assertThrows(DomainException.class, () -> new InspectionWorksheet(tenant, asset, "合成", snapshot(), values));
    }
    @Test void observationsAndEvidenceListsRemainDetachedAndImmutable() {
        var ids = new ArrayList<>(List.of(UUID.randomUUID())); var observation = new Observation(Result.ATTENTION, "合成\n说明", ids); ids.clear();
        var values = new HashMap<String, Observation>(); values.put("backup", observation); var sheet = new InspectionWorksheet(tenant, asset, "合成", snapshot(), values); values.clear();
        assertEquals(1, sheet.summary().answered()); assertEquals(1, sheet.observations().get("backup").evidenceIds().size()); assertThrows(UnsupportedOperationException.class, () -> sheet.observations().clear()); assertThrows(UnsupportedOperationException.class, () -> observation.evidenceIds().clear());
    }
    @Test void evidenceReferencesAreBoundedUniqueAndNeverVerifiedOrGranted() {
        var id = UUID.randomUUID(); assertThrows(DomainException.class, () -> new Observation(Result.NORMAL, null, List.of(id,id))); assertThrows(DomainException.class, () -> new Observation(Result.NORMAL, null, null)); assertThrows(DomainException.class, () -> new Observation(Result.NORMAL, null, Arrays.asList(id,null)));
        var ids = new ArrayList<UUID>(); for (int i=0;i<11;i++) ids.add(UUID.randomUUID()); assertThrows(DomainException.class, () -> new Observation(Result.NORMAL,null,ids));
        var observation = new Observation(Result.NORMAL,"",List.of(id)); assertEquals("REFERENCES_NOT_VERIFIED",observation.evidenceStatus()); assertEquals("MANUAL_OBSERVATION", observation.source()); assertEquals("SELF_REPORTED_UNVERIFIED", observation.verificationStatus());
    }
    @Test void notesPreserveNullAndEmptyAndRejectOversizeInvalidUnicode() {
        assertNull(result(Result.NORMAL).note()); assertEquals("",new Observation(Result.NORMAL,"",List.of()).note());
        for (String note : List.of("a".repeat(3001), "\udc00", "bad\u0000")) assertThrows(DomainException.class, () -> new Observation(Result.NORMAL,note,List.of()));
        assertDoesNotThrow(() -> new Observation(Result.NORMAL,"😀".repeat(3000),List.of()));
    }
    @Test void templateTenantMismatchIs404AndDoesNotBecomeAuthorization() {
        var other = UUID.randomUUID(); var error = assertThrows(DomainException.class, () -> InspectionWorksheet.empty(other,asset,"合成",snapshot())); assertEquals(404,error.status);
        assertThrows(DomainException.class, () -> InspectionWorksheet.empty(tenant,null,"合成",snapshot())); assertThrows(DomainException.class, () -> InspectionWorksheet.empty(tenant,asset,"　",snapshot()));
    }
    @Test void rowOrderAlwaysUsesFrozenDefinitionNotInputMapOrder() {
        var values = new LinkedHashMap<String,Observation>(); values.put("line_quality",result(Result.NORMAL)); values.put("firmware",result(Result.ATTENTION));
        var sheet = new InspectionWorksheet(tenant,asset,"合成",snapshot(),values); assertEquals(List.of("firmware","backup","line_quality"),sheet.rows().stream().map(row->row.item().key()).toList()); assertNull(sheet.rows().get(1).observation());
    }
    @Test void templateCandidateNeverBecomesCustomerApprovedOrPublished() { assertEquals("UNVERIFIED",template().approvalStatus()); assertFalse(template().publishable()); }
}
