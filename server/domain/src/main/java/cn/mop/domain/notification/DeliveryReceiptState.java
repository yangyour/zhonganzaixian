package cn.mop.domain.notification;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.*;

/** Pure explicit receipt history. No sending, signature verification, retry scheduler, quota or authority. */
public final class DeliveryReceiptState {
    public static final int MAX_ATTEMPTS = 1000;
    public static final int MAX_RECEIPTS = 10000;
    public enum Channel { EMAIL, SMS, DINGTALK, WECHAT_WORK, WEBHOOK, VOICE }
    public enum Status { QUEUED, UNKNOWN, ACCEPTED, FAILED, DEAD_LETTER, DELIVERED }
    public enum ReceiptStatus { ACCEPTED, FAILED, DELIVERED }
    /** Opaque receiver reference only; never contact addresses, credentials or raw provider payload. */
    public record Deduplication(UUID resourcePoolId, UUID eventId, Channel channel, UUID receiverId, String stage) {
        public Deduplication {
            if (resourcePoolId == null || eventId == null || channel == null || receiverId == null || stage == null || !stage.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
        }
    }
    public record Attempt(UUID requestId, Instant startedAt) {
        public Attempt { if (requestId == null) throw invalid(); time(startedAt); }
    }
    /** External adapter must authenticate/correlate before calling; this reference proves neither. */
    public record Receipt(UUID tenantId, UUID deliveryId, UUID requestId, UUID receiptId,
                          ReceiptStatus status, Instant occurredAt) {
        public Receipt {
            if (tenantId == null || deliveryId == null || requestId == null || receiptId == null || status == null) throw invalid();
            time(occurredAt);
        }
    }
    /** Ordered observations are required for lossless persistence; receipt occurrence alone is not receipt time. */
    public sealed interface Transition permits Started, DeadLettered, ReceiptRecorded { Instant observedAt(); }
    public record Started(UUID requestId, Instant observedAt) implements Transition {
        public Started { if (requestId == null) throw invalid(); time(observedAt); }
    }
    public record DeadLettered(Instant observedAt) implements Transition {
        public DeadLettered { time(observedAt); }
    }
    public record ReceiptRecorded(Receipt receipt, Instant observedAt) implements Transition {
        public ReceiptRecorded { if (receipt == null) throw invalid(); time(observedAt); }
    }
    private final List<Transition> transitions;
    private final UUID tenantId, deliveryId;
    private final Deduplication deduplication;
    private final Instant queuedAt, observedAt;
    private final long version;
    private final List<Attempt> attempts;
    private final List<Receipt> receipts;
    private final boolean deadLetter;

    private DeliveryReceiptState(UUID tenantId, UUID deliveryId, Deduplication deduplication,
                                 Instant queuedAt, Instant observedAt, long version,
                                 List<Attempt> attempts, List<Receipt> receipts, boolean deadLetter, List<Transition> transitions) {
        this.tenantId = tenantId; this.deliveryId = deliveryId; this.deduplication = deduplication;
        this.queuedAt = queuedAt; this.observedAt = observedAt; this.version = version;
        this.attempts = List.copyOf(attempts); this.receipts = List.copyOf(receipts); this.deadLetter = deadLetter;
        this.transitions = List.copyOf(transitions);
    }
    public static DeliveryReceiptState queued(UUID tenantId, UUID deliveryId, Deduplication key, Instant at) {
        if (tenantId == null || deliveryId == null || key == null) throw invalid(); time(at);
        return new DeliveryReceiptState(tenantId, deliveryId, key, at, at, 0, List.of(), List.of(), false, List.of());
    }
    /** Records a planned request only; a caller must independently authorize and persist before sending. */
    public DeliveryReceiptState start(long expectedVersion, UUID requestId, Instant at) {
        change(expectedVersion, at);
        if (status() != Status.QUEUED && status() != Status.DEAD_LETTER) throw conflict();
        if (attempts.size() >= MAX_ATTEMPTS || attempts.stream().anyMatch(a -> a.requestId().equals(requestId))) throw conflict();
        var next = new ArrayList<>(attempts); next.add(new Attempt(requestId, at));
        return copy(at, next, receipts, false, new Started(requestId, at));
    }
    /** Explicit failed-attempt disposition; no guessed retry budget, time or SMS quota. */
    public DeliveryReceiptState deadLetter(long expectedVersion, Instant at) {
        change(expectedVersion, at);
        if (status() != Status.FAILED) throw conflict();
        return copy(at, attempts, receipts, true, new DeadLettered(at));
    }
    public DeliveryReceiptState record(long expectedVersion, Receipt receipt, Instant receivedAt) {
        if (receipt == null) throw invalid();
        if (!tenantId.equals(receipt.tenantId()) || !deliveryId.equals(receipt.deliveryId()))
            throw new DomainException(404, "NOT_FOUND", "Delivery not found");
        time(receivedAt);
        // Exact repeated receipts are no-ops even after another observation changed the logical version.
        for (var old : receipts) if (old.receiptId().equals(receipt.receiptId())) {
            if (!old.equals(receipt)) throw conflict();
            return this;
        }
        change(expectedVersion, receivedAt);
        var attempt = attempts.stream().filter(a -> a.requestId().equals(receipt.requestId())).findFirst().orElseThrow(DeliveryReceiptState::invalid);
        if (receipt.occurredAt().isBefore(attempt.startedAt()) || receipt.occurredAt().isAfter(receivedAt) || receipts.size() >= MAX_RECEIPTS) throw invalid();
        var next = new ArrayList<>(receipts); next.add(receipt);
        return copy(receivedAt, attempts, next, deadLetter, new ReceiptRecorded(receipt, receivedAt));
    }
    public Status status() {
        if (receipts.stream().anyMatch(r -> r.status() == ReceiptStatus.DELIVERED)) return Status.DELIVERED;
        if (deadLetter) return Status.DEAD_LETTER;
        if (attempts.isEmpty()) return Status.QUEUED;
        UUID current = attempts.get(attempts.size() - 1).requestId();
        // A late ACCEPTED for a known failed request cannot undo the failure.
        if (receipts.stream().anyMatch(r -> r.requestId().equals(current) && r.status() == ReceiptStatus.FAILED)) return Status.FAILED;
        if (receipts.stream().anyMatch(r -> r.requestId().equals(current) && r.status() == ReceiptStatus.ACCEPTED)) return Status.ACCEPTED;
        return Status.UNKNOWN;
    }
    public int logicalDeliveredCount() { return status() == Status.DELIVERED ? 1 : 0; }
    public Instant firstDeliveredAt() {
        return receipts.stream().filter(r -> r.status() == ReceiptStatus.DELIVERED).map(Receipt::occurredAt).min(Instant::compareTo).orElse(null);
    }
    public UUID tenantId() { return tenantId; }
    public UUID deliveryId() { return deliveryId; }
    public Deduplication deduplication() { return deduplication; }
    public Instant queuedAt() { return queuedAt; }
    public Instant observedAt() { return observedAt; }
    public long version() { return version; }
    public List<Transition> transitions() { return transitions; }
    public List<Attempt> attempts() { return attempts; }
    public List<Receipt> receipts() { return receipts; }
    public String scope() { return "EXPLICIT_RECEIPT_STATE_ONLY"; }
    public boolean providerReceiptVerified() { return false; }
    public boolean automaticSendEnabled() { return false; }
    public Object effectiveRetryBudget() { return null; }
    public Object effectiveSmsQuota() { return null; }
    private DeliveryReceiptState copy(Instant at, List<Attempt> attempts, List<Receipt> receipts, boolean deadLetter, Transition transition) {
        var history = new ArrayList<>(transitions); history.add(transition);
        return new DeliveryReceiptState(tenantId, deliveryId, deduplication, queuedAt, at, version + 1, attempts, receipts, deadLetter, history);
    }
    private void change(long expectedVersion, Instant at) {
        if (version != expectedVersion || version >= 9007199254740991L) throw new DomainException(409, "VERSION_CONFLICT", "Delivery version changed");
        time(at); if (at.isBefore(observedAt)) throw invalid();
    }
    private static void time(Instant value) {
        if (value == null || value.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) throw invalid();
    }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "Invalid bounded delivery reference or time"); }
    private static DomainException conflict() { return new DomainException(409, "DELIVERY_STATE_CONFLICT", "Delivery history or state conflicts"); }
}
