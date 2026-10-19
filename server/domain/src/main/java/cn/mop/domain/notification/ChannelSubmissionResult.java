package cn.mop.domain.notification;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Sanitized synchronous submission observation, never proof of terminal delivery. */
public record ChannelSubmissionResult(UUID tenantId, UUID deliveryId, UUID requestId,
                                      Outcome outcome, Reason reason, Instant observedAt) {
    public enum Outcome { NOT_SUBMITTED, ACCEPTED, REJECTED, UNKNOWN }
    public enum Reason { CONFIGURATION_UNAVAILABLE, TOKEN_UNAVAILABLE, PROVIDER_ACCEPTED,
        PROVIDER_REJECTED, RECIPIENT_REJECTED, TRANSPORT_UNCERTAIN, RESPONSE_UNCERTAIN }

    public ChannelSubmissionResult {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(deliveryId); Objects.requireNonNull(requestId);
        Objects.requireNonNull(outcome); Objects.requireNonNull(reason); Objects.requireNonNull(observedAt);
        if (observedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || observedAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z")))
            throw new IllegalArgumentException("Invalid channel observation time");
        boolean valid = switch (outcome) {
            case NOT_SUBMITTED -> reason == Reason.CONFIGURATION_UNAVAILABLE || reason == Reason.TOKEN_UNAVAILABLE;
            case ACCEPTED -> reason == Reason.PROVIDER_ACCEPTED;
            case REJECTED -> reason == Reason.PROVIDER_REJECTED || reason == Reason.RECIPIENT_REJECTED;
            case UNKNOWN -> reason == Reason.TRANSPORT_UNCERTAIN || reason == Reason.RESPONSE_UNCERTAIN;
        };
        if (!valid) throw new IllegalArgumentException("Inconsistent channel submission result");
    }

    /**
     * Caller must persist the started attempt before network I/O and atomically persist this result.
     * NOT_SUBMITTED/UNKNOWN retain the started attempt's UNKNOWN state; they never permit blind replay.
     * receiptId is a durable local observation ID, not an unverified provider callback identifier.
     */
    public DeliveryReceiptState apply(DeliveryReceiptState state, long expectedVersion, UUID receiptId) {
        Objects.requireNonNull(state); Objects.requireNonNull(receiptId);
        if (!state.tenantId().equals(tenantId) || !state.deliveryId().equals(deliveryId))
            throw new DomainException(404, "NOT_FOUND", "Delivery not found");
        if (state.deduplication().channel() != DeliveryReceiptState.Channel.WECHAT_WORK
                || state.attempts().stream().noneMatch(a -> a.requestId().equals(requestId)))
            throw new DomainException(409, "DELIVERY_STATE_CONFLICT", "Submission attempt does not match");
        if (outcome == Outcome.ACCEPTED || outcome == Outcome.REJECTED) {
            return state.record(expectedVersion, new DeliveryReceiptState.Receipt(tenantId, deliveryId,
                    requestId, receiptId, outcome == Outcome.ACCEPTED ? DeliveryReceiptState.ReceiptStatus.ACCEPTED
                    : DeliveryReceiptState.ReceiptStatus.FAILED, observedAt), observedAt);
        }
        if (state.version() != expectedVersion) throw new DomainException(409, "VERSION_CONFLICT", "Delivery version changed");
        if (observedAt.isBefore(state.observedAt())) throw new IllegalArgumentException("Observation time precedes attempt");
        return state;
    }
}
