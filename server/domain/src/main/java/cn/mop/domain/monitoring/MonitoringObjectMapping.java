package cn.mop.domain.monitoring;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Consistency of explicitly supplied mapping inputs, never source authentication or current authorization. */
public final class MonitoringObjectMapping {
    private MonitoringObjectMapping() {}
    public enum ObjectType { HOST, ITEM }
    public enum Outcome {
        NOT_FOUND, AMBIGUOUS, LOOKUP_KEY_MISMATCH, INACTIVE_INPUT,
        INCOMPLETE_CLAIMS, CLAIMS_MISMATCH, CONSISTENT_INPUT
    }
    public record Key(UUID poolId, ObjectType objectType, String externalId) {
        public Key {
            if (poolId == null || objectType == null) throw invalid();
            identifier(externalId);
        }
        @Override public String toString() { return "MonitoringObjectKey[redacted]"; }
    }
    /** These optional claims are untrusted statements, not tenant or asset ownership. */
    public record Claims(UUID tenantId, UUID assetId) {
        @Override public String toString() { return "MonitoringObjectClaims[unverified; redacted]"; }
    }
    /** Revision and active flag are supplied facts; this class cannot verify their provenance or freshness. */
    public record Snapshot(Key key, UUID revisionId, UUID tenantId, UUID assetId, boolean active) {
        public Snapshot {
            if (key == null || revisionId == null || tenantId == null || assetId == null) throw invalid();
        }
        @Override public String toString() { return "MonitoringMappingSnapshot[unverified input; redacted]"; }
    }
    /** Future repository boundary. Missing data is an empty list; lookup failure must throw, not return empty. */
    public interface SnapshotLookup {
        List<Snapshot> findExact(Key key);
    }
    public static final class Result {
        private final Outcome outcome;
        private final Snapshot candidate;
        private Result(Outcome outcome, Snapshot candidate) {
            this.outcome = outcome;
            this.candidate = candidate;
        }
        public Outcome outcome() { return outcome; }
        /** Present only for consistent complete input, not an authorized RawMonitoringEvent.Binding. */
        public Optional<Snapshot> candidate() { return Optional.ofNullable(candidate); }
        public boolean sourceAuthorized() { return false; }
        public boolean currentMappingVerified() { return false; }
        public boolean dispatchAllowed() { return false; }
        public boolean durablyAccepted() { return false; }
        @Override public String toString() { return "MonitoringMappingAssessment[" + outcome + "; unverified; redacted]"; }
    }
    /** Caller supplies a bounded exact-key lookup result; this method performs no I/O or fallback lookup. */
    public static Result assess(Key requested, Claims claims, List<Snapshot> supplied) {
        if (requested == null || claims == null || supplied == null) throw invalid();
        // A unique lookup can have zero or one row. Two or more rows are already ambiguous.
        // Do not select a first, newest, active or matching-claim winner from inconsistent input.
        if (supplied.size() > 1) return result(Outcome.AMBIGUOUS);
        List<Snapshot> snapshots;
        try { snapshots = List.copyOf(supplied); }
        catch (NullPointerException error) { throw invalid(); }
        if (snapshots.size() > 1) return result(Outcome.AMBIGUOUS);
        if (snapshots.isEmpty()) return result(Outcome.NOT_FOUND);
        Snapshot snapshot = snapshots.get(0);
        if (!Objects.equals(requested, snapshot.key())) return result(Outcome.LOOKUP_KEY_MISMATCH);
        if (!snapshot.active()) return result(Outcome.INACTIVE_INPUT);
        if (claims.tenantId() == null || claims.assetId() == null) return result(Outcome.INCOMPLETE_CLAIMS);
        if (!claims.tenantId().equals(snapshot.tenantId()) || !claims.assetId().equals(snapshot.assetId()))
            return result(Outcome.CLAIMS_MISMATCH);
        return new Result(Outcome.CONSISTENT_INPUT, snapshot);
    }
    private static Result result(Outcome outcome) { return new Result(outcome, null); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid monitoring mapping input"); }
    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || !value.equals(value.strip())) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }
}
