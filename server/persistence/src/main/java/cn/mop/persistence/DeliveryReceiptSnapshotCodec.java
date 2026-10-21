package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Canonical bounded state history only: no database, receipt authentication, dispatch or replay authorization. */
public final class DeliveryReceiptSnapshotCodec {
    public static final String FORMAT = "MOP_DELIVERY_RECEIPT_SNAPSHOT_V1";
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_TRANSITIONS = 12_000;
    public record Scope(UUID tenantId, UUID deliveryId, Deduplication key) {
        public Scope { if (tenantId == null || deliveryId == null || key == null) throw invalid(); }
    }
    public record Document(byte[] bytes, String sha256) {
        public Document {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES || sha256 == null) throw invalid();
            bytes = bytes.clone(); if (!hash(bytes).equals(sha256)) throw invalid();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public Document encode(DeliveryReceiptState state) {
        if (state == null || state.version() != state.transitions().size() || state.transitions().size() > MAX_TRANSITIONS) throw invalid();
        try (var buffer = new ByteArrayOutputStream(); var out = new DataOutputStream(buffer)) {
            var key = state.deduplication();
            out.writeUTF(FORMAT); uuid(out, state.tenantId()); uuid(out, state.deliveryId());
            uuid(out, key.resourcePoolId()); uuid(out, key.eventId()); out.writeUTF(key.channel().name());
            uuid(out, key.receiverId()); out.writeUTF(key.stage()); time(out, state.queuedAt());
            out.writeLong(state.version()); out.writeInt(state.transitions().size());
            for (var transition : state.transitions()) {
                if (transition instanceof Started started) {
                    out.writeByte(1); uuid(out, started.requestId()); time(out, started.observedAt());
                } else if (transition instanceof DeadLettered dead) {
                    out.writeByte(2); time(out, dead.observedAt());
                } else if (transition instanceof ReceiptRecorded recorded) {
                    var receipt = recorded.receipt();
                    if (!receipt.tenantId().equals(state.tenantId()) || !receipt.deliveryId().equals(state.deliveryId())) throw invalid();
                    out.writeByte(3); uuid(out, receipt.requestId()); uuid(out, receipt.receiptId());
                    out.writeUTF(receipt.status().name()); time(out, receipt.occurredAt()); time(out, recorded.observedAt());
                } else throw invalid();
            }
            out.flush(); byte[] bytes = buffer.toByteArray(); if (bytes.length > MAX_BYTES) throw invalid();
            return new Document(bytes, hash(bytes));
        } catch (IOException impossible) { throw invalid(); }
    }
    public DeliveryReceiptState decode(byte[] supplied, String expectedSha256, Scope expected) {
        if (supplied == null || supplied.length == 0 || supplied.length > MAX_BYTES || expectedSha256 == null ||
                !expectedSha256.matches("[0-9a-f]{64}") || expected == null) throw invalid();
        byte[] bytes = supplied.clone(); if (!hash(bytes).equals(expectedSha256)) throw invalid();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!FORMAT.equals(in.readUTF())) throw invalid();
            UUID tenant = uuid(in), delivery = uuid(in), pool = uuid(in), event = uuid(in);
            Channel channel = Channel.valueOf(in.readUTF()); UUID receiver = uuid(in); String stage = in.readUTF();
            var key = new Deduplication(pool, event, channel, receiver, stage);
            if (!expected.equals(new Scope(tenant, delivery, key))) throw invalid();
            Instant queued = time(in); long version = in.readLong(); int count = in.readInt();
            if (count < 0 || count > MAX_TRANSITIONS || version != count) throw invalid();
            var state = DeliveryReceiptState.queued(tenant, delivery, key, queued);
            for (int i = 0; i < count; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> state = state.start(state.version(), uuid(in), time(in));
                    case 2 -> state = state.deadLetter(state.version(), time(in));
                    case 3 -> {
                        UUID request = uuid(in), receiptId = uuid(in);
                        ReceiptStatus status = ReceiptStatus.valueOf(in.readUTF()); Instant occurred = time(in), observed = time(in);
                        state = state.record(state.version(), new Receipt(tenant, delivery, request, receiptId, status, occurred), observed);
                    }
                    default -> throw invalid();
                }
            }
            if (in.read() != -1 || state.version() != version || !Arrays.equals(bytes, encode(state).bytes())) throw invalid();
            return state;
        } catch (IOException | IllegalArgumentException | DateTimeException | DomainException rejected) { throw invalid(); }
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void time(DataOutputStream out, Instant value) throws IOException { out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano()); }
    private static Instant time(DataInputStream in) throws IOException {
        long seconds = in.readLong(); int nanos = in.readInt(); if (nanos < 0 || nanos > 999_999_999) throw invalid();
        return Instant.ofEpochSecond(seconds, nanos);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static DomainException invalid() { return new DomainException(503, "DELIVERY_SNAPSHOT_INVALID", "通知回执快照的范围、摘要、格式或状态链未通过核验；未恢复发送权限"); }
}
