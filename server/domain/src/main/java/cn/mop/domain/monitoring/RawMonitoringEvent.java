package cn.mop.domain.monitoring;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Immutable source facts only: no signature verification, durable acceptance, merge or business transition. */
public final class RawMonitoringEvent {
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public enum Phase { PROBLEM, RECOVERED }
    public enum Comparison { DIFFERENT_KEY, SAME_CONTENT, CONFLICT_REQUIRES_REVIEW }
    public record Key(UUID poolId, String sourceEventId, Phase phase) {
        public Key { if (poolId == null || phase == null) throw invalid(); label(sourceEventId); }
    }
    /** Mapping supplied by the future authorized resolver; construction does not authenticate that mapping. */
    public record Binding(UUID tenantId, UUID assetId) {
        public Binding { if (tenantId == null || assetId == null) throw invalid(); }
    }
    private final Key key;
    private final Binding binding;
    private final Instant occurredAt, receivedAt;
    private final byte[] payload;
    private final String payloadSha256, contentFingerprint;
    public RawMonitoringEvent(Key key, Binding binding, Instant occurredAt, Instant receivedAt, byte[] payload) {
        if (key == null || binding == null || occurredAt == null || receivedAt == null || payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) throw invalid();
        // Preserve source clock exactly, including sub-millisecond precision and a source clock ahead of receipt.
        this.key = key; this.binding = binding; this.occurredAt = occurredAt; this.receivedAt = receivedAt;
        this.payload = payload.clone(); this.payloadSha256 = hash(this.payload);
        this.contentFingerprint = fingerprint();
    }
    public Key key() { return key; }
    public Binding binding() { return binding; }
    public Instant occurredAt() { return occurredAt; }
    public Instant receivedAt() { return receivedAt; }
    public byte[] payload() { return payload.clone(); }
    public String payloadSha256() { return payloadSha256; }
    public String contentFingerprint() { return contentFingerprint; }
    /** Different delivery times do not change source content. No record is overwritten by this comparison. */
    public Comparison compare(RawMonitoringEvent incoming) {
        Objects.requireNonNull(incoming);
        if (!key.equals(incoming.key)) return Comparison.DIFFERENT_KEY;
        return contentFingerprint.equals(incoming.contentFingerprint) ? Comparison.SAME_CONTENT : Comparison.CONFLICT_REQUIRES_REVIEW;
    }
    private String fingerprint() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            field(out,"MOP_RAW_MONITORING_EVENT_V1"); field(out,key.poolId().toString()); field(out,key.sourceEventId()); field(out,key.phase().name());
            field(out,binding.tenantId().toString()); field(out,binding.assetId().toString());
            out.writeLong(occurredAt.getEpochSecond()); out.writeInt(occurredAt.getNano()); field(out,payloadSha256);
            out.flush(); return hash(bytes.toByteArray());
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void field(DataOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static void label(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 256
            || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw invalid();
    }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public String toString() { return "RawMonitoringEvent[content redacted; not durably accepted]"; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid raw monitoring event envelope"); }
}
