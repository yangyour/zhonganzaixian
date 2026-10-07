package cn.mop.api;

import cn.mop.domain.monitoring.MonitoringObjectMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static cn.mop.domain.monitoring.MonitoringObjectMapping.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringObjectMappingTest {
    static final UUID POOL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID REVISION = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000005");
    Key key() { return new Key(POOL, ObjectType.HOST, "001"); }
    Claims claims() { return new Claims(TENANT, ASSET); }
    Snapshot snapshot() { return new Snapshot(key(), REVISION, TENANT, ASSET, true); }
    Result check(Outcome expected, Key key, Claims claims, List<Snapshot> rows) {
        Result result = assess(key, claims, rows);
        assertEquals(expected, result.outcome());
        assertFalse(result.sourceAuthorized());
        assertFalse(result.currentMappingVerified());
        assertFalse(result.dispatchAllowed());
        assertFalse(result.durablyAccepted());
        assertEquals(expected == Outcome.CONSISTENT_INPUT, result.candidate().isPresent());
        return result;
    }
    @Test void consistentInputsRetainExplicitSnapshotWithoutAuthorizing() {
        var snapshot = snapshot();
        var result = check(Outcome.CONSISTENT_INPUT, key(), claims(), List.of(snapshot));
        assertEquals(snapshot, result.candidate().orElseThrow());
        assertEquals(REVISION, result.candidate().orElseThrow().revisionId());
    }
    @Test void emptyLookupDoesNotGuessByClaims() { check(Outcome.NOT_FOUND, key(), claims(), List.of()); }
    @Test void sameExternalIdInAnotherPoolIsNotTheSameObject() {
        var other = new Snapshot(new Key(OTHER, ObjectType.HOST, "001"), REVISION, OTHER, OTHER, true);
        check(Outcome.LOOKUP_KEY_MISMATCH, key(), claims(), List.of(other));
    }
    @Test void hostAndItemNeverAlias() {
        var item = new Snapshot(new Key(POOL, ObjectType.ITEM, "001"), REVISION, TENANT, ASSET, true);
        check(Outcome.LOOKUP_KEY_MISMATCH, key(), claims(), List.of(item));
        check(Outcome.CONSISTENT_INPUT, item.key(), claims(), List.of(item));
    }
    @ParameterizedTest @ValueSource(strings={"1", "0001", "001A", "001a", "００１"})
    void externalIdsAreExactNotNumericOrCaseNormalized(String otherId) {
        var other = new Snapshot(new Key(POOL, ObjectType.HOST, otherId), REVISION, TENANT, ASSET, true);
        check(Outcome.LOOKUP_KEY_MISMATCH, key(), claims(), List.of(other));
    }
    @Test void incompleteClaimsRemainUnknown() {
        for (var claims : List.of(new Claims(null, null), new Claims(TENANT, null), new Claims(null, ASSET)))
            check(Outcome.INCOMPLETE_CLAIMS, key(), claims, List.of(snapshot()));
    }
    @Test void wrongTenantOrAssetDoesNotExposeMappedSnapshot() {
        for (var claims : List.of(new Claims(OTHER, ASSET), new Claims(TENANT, OTHER), new Claims(OTHER, OTHER)))
            check(Outcome.CLAIMS_MISMATCH, key(), claims, List.of(snapshot()));
    }
    @Test void inactiveSnapshotDoesNotBecomeActiveFromMatchingClaims() {
        check(Outcome.INACTIVE_INPUT, key(), claims(), List.of(new Snapshot(key(), REVISION, TENANT, ASSET, false)));
    }
    @Test void duplicateRowsAreAmbiguousEvenIfIdentical() {
        check(Outcome.AMBIGUOUS, key(), claims(), List.of(snapshot(), snapshot()));
    }
    @Test void conflictingRowsNeverSelectFirstLatestActiveOrClaimMatch() {
        var conflicting = new Snapshot(key(), OTHER, OTHER, OTHER, false);
        check(Outcome.AMBIGUOUS, key(), claims(), List.of(snapshot(), conflicting));
        check(Outcome.AMBIGUOUS, key(), claims(), List.of(conflicting, snapshot()));
    }
    @Test void sizeChangeDuringSnapshotCannotHideAmbiguity() {
        List<Snapshot> changing = new java.util.AbstractList<>() {
            @Override public Snapshot get(int index) { return snapshot(); }
            @Override public int size() { return 1; }
            @Override public Object[] toArray() { return new Object[]{snapshot(), snapshot()}; }
        };
        check(Outcome.AMBIGUOUS, key(), claims(), changing);
    }
    @Test void laterListOrRevisionCannotRewriteEarlierAssessment() {
        var rows = new ArrayList<>(List.of(snapshot()));
        var first = check(Outcome.CONSISTENT_INPUT, key(), claims(), rows);
        rows.clear();
        rows.add(new Snapshot(key(), OTHER, TENANT, ASSET, true));
        var second = check(Outcome.CONSISTENT_INPUT, key(), claims(), rows);
        assertEquals(REVISION, first.candidate().orElseThrow().revisionId());
        assertEquals(OTHER, second.candidate().orElseThrow().revisionId());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", " leading", "trailing ", "a\nb", "a\u0000b", "\ud800", "\udc00"})
    void invalidIdentifiersFailWithNoIdentifierInError(String id) {
        var error = assertThrows(IllegalArgumentException.class, () -> new Key(POOL, ObjectType.HOST, id));
        assertEquals("Invalid monitoring mapping input", error.getMessage());
    }
    @Test void technicalIdentifierBoundIsExplicitAndPreservesUtf8() {
        String id = "😀".repeat(128);
        assertEquals(id, new Key(POOL, ObjectType.ITEM, id).externalId());
        assertThrows(IllegalArgumentException.class, () -> new Key(POOL, ObjectType.HOST, "x".repeat(257)));
    }
    @Test void nullStructuralInputsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Key(null, ObjectType.HOST, "1"));
        assertThrows(IllegalArgumentException.class, () -> new Key(POOL, null, "1"));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(null, REVISION, TENANT, ASSET, true));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(key(), null, TENANT, ASSET, true));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(key(), REVISION, null, ASSET, true));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(key(), REVISION, TENANT, null, true));
        assertThrows(IllegalArgumentException.class, () -> assess(null, claims(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> assess(key(), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> assess(key(), claims(), null));
        assertThrows(IllegalArgumentException.class, () -> assess(key(), claims(), java.util.Arrays.asList((Snapshot)null)));
    }
    @Test void logsDoNotExposeIdentifiersClaimsOrMappingRevision() {
        var key = new Key(POOL, ObjectType.HOST, "PRIVATE_EXTERNAL_ID");
        var snapshot = new Snapshot(key, REVISION, TENANT, ASSET, true);
        var result = assess(key, claims(), List.of(snapshot));
        String text = List.of(key, claims(), snapshot, result).toString();
        for (var value : List.of("PRIVATE_EXTERNAL_ID", POOL.toString(), TENANT.toString(), ASSET.toString(), REVISION.toString()))
            assertFalse(text.contains(value));
    }
}
