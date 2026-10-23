package cn.mop.worker.notification;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptJournal;
import cn.mop.persistence.DeliveryReceiptJournal.Command;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Access;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Receipt;
import cn.mop.worker.notification.DeliveryDispatch.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit isolated journal/provider doubles. These tests do not assert database or vendor integration. */
class DeliveryDispatchTest {
    private final UUID tenant = UUID.randomUUID();
    private final Access access = new Access(tenant, UUID.randomUUID(), 1);
    private final Scope scope = new Scope(tenant, UUID.randomUUID(), new Deduplication(
            UUID.randomUUID(), UUID.randomUUID(), Channel.WEBHOOK, UUID.randomUUID(), "OPENED"));
    private final Instant at = Instant.parse("2026-10-23T06:00:00Z");
    private final Command start = new Command(0, UUID.randomUUID(), access.actorId(), 1,
            "synthetic-dispatch", new Started(UUID.randomUUID(), at.plusSeconds(1)));
    private final Original original = new Original(access, scope, start);
    private final Stored stored = new Stored();
    private final Gateway gateway = new Gateway();
    private final List<Step> checked = new ArrayList<>();
    private Step denied;

    private class Stored implements DeliveryDispatch.Journal {
        DeliveryReceiptJournal state = DeliveryReceiptJournal.queued(scope, at);
        boolean loseStartReply;
        boolean loseReceiptReply;
        boolean replayStart;
        boolean denyReceipt;
        int reads;

        @Override
        public Optional<Receipt> current(Access requestedAccess, Scope requestedScope) {
            assertEquals(access, requestedAccess);
            assertEquals(scope, requestedScope);
            reads++;
            // Decode stored bytes to exercise recovery against the actual V50 representation.
            state = DeliveryReceiptJournal.restore(state.root().bytes(), state.root().sha256(), scope,
                    state.entries(), state.state().version(), state.headSha256());
            return Optional.of(new Receipt(state, state.state().version(), false));
        }

        @Override
        public Receipt append(Access requestedAccess, Scope requestedScope, Command command) {
            assertEquals(access, requestedAccess);
            assertEquals(scope, requestedScope);
            if (denyReceipt && command.transition() instanceof ReceiptRecorded) {
                throw new IllegalStateException("SYNTHETIC_UNTRUSTED_PROVIDER");
            }
            var applied = state.append(command);
            state = applied.journal();
            if (loseStartReply && command.transition() instanceof Started
                    || loseReceiptReply && command.transition() instanceof ReceiptRecorded) {
                throw new IllegalStateException("SYNTHETIC_PRIVATE_COMMIT_RESPONSE_LOST");
            }
            return new Receipt(state, applied.appliedVersion(), applied.replay() || replayStart);
        }
    }

    private class Gateway implements Provider {
        int sends;
        int lookups;
        boolean loseSendReply;
        Optional<Observation> submitted = Optional.empty();
        Optional<Observation> lookedUp = Optional.empty();

        @Override
        public Optional<Observation> submit(Scope supplied, UUID requestId) {
            assertEquals(scope, supplied);
            assertEquals(original.requestId(), requestId);
            assertEquals(Status.UNKNOWN, stored.state.state().status());
            assertEquals(start, stored.state.command(start.commandId()).orElseThrow());
            sends++;
            if (loseSendReply) {
                throw new IllegalStateException("SYNTHETIC_PRIVATE_NETWORK_ERROR");
            }
            return submitted;
        }

        @Override
        public Optional<Observation> lookup(Scope supplied, UUID requestId) {
            assertEquals(scope, supplied);
            assertEquals(original.requestId(), requestId);
            lookups++;
            return lookedUp;
        }
    }

    private DeliveryDispatch dispatch(boolean enabled) {
        return new DeliveryDispatch(new Binding(enabled, enabled ? "synthetic-publication" : null),
                (binding, requestedAccess, requestedScope, step) -> {
                    checked.add(step);
                    if (step == denied) {
                        throw new IllegalStateException("SYNTHETIC_PRIVATE_AUTHORITY");
                    }
                }, gateway, stored);
    }

    private Observation receipt(ReceiptStatus status) {
        return new Observation(UUID.randomUUID(), new DeliveryReceiptState.Receipt(tenant, scope.deliveryId(),
                original.requestId(), UUID.randomUUID(), status, at.plusSeconds(2)), at.plusSeconds(3));
    }

    @Test
    void disabledDoesNoDatabaseOrProviderWork() {
        assertEquals(Outcome.DISABLED, dispatch(false).once(original).outcome());
        assertEquals(Outcome.DISABLED, dispatch(false).reconcile(original).outcome());
        assertEquals(0, stored.reads);
        assertEquals(0, gateway.sends + gateway.lookups);
        assertTrue(checked.isEmpty());
    }

    @Test
    void acknowledgedStartPrecedesExactlyOneSubmitEvenAfterRestart() {
        var first = dispatch(true).once(original);
        assertEquals(Outcome.RECONCILIATION_REQUIRED, first.outcome());
        assertTrue(first.submitInvoked());
        assertEquals(Status.UNKNOWN, first.journalStatus());
        assertEquals(List.of(Step.READ, Step.START, Step.SUBMIT, Step.RETURN), checked);
        assertFalse(dispatch(true).once(original).submitInvoked());
        assertEquals(1, gateway.sends);
        assertEquals(1, stored.state.state().attempts().size());
    }

    @Test
    void lostStartCommitReplyNeverSubmitsAndRetainsOriginalAttempt() {
        stored.loseStartReply = true;
        assertEquals(Outcome.RECONCILIATION_REQUIRED, dispatch(true).once(original).outcome());
        assertEquals(0, gateway.sends);
        assertEquals(start, stored.state.command(start.commandId()).orElseThrow());
        assertFalse(dispatch(true).once(original).submitInvoked());
        dispatch(true).reconcile(original);
        assertEquals(1, gateway.lookups);
        assertEquals(0, gateway.sends);
    }

    @Test
    void racingStartReplayCannotAcquirePermissionToSubmit() {
        stored.replayStart = true;
        assertFalse(dispatch(true).once(original).submitInvoked());
        assertEquals(0, gateway.sends);
        assertEquals(Status.UNKNOWN, stored.state.state().status());
    }

    @Test
    void providerResponseLossReconcilesOriginalRequestWithoutResend() {
        gateway.loseSendReply = true;
        assertEquals(Outcome.RECONCILIATION_REQUIRED, dispatch(true).once(original).outcome());
        gateway.lookedUp = Optional.of(receipt(ReceiptStatus.DELIVERED));
        var recovered = dispatch(true).reconcile(original);
        assertEquals(Outcome.DELIVERED_RECORDED, recovered.outcome());
        assertFalse(recovered.providerReceiptVerified());
        assertEquals(1, gateway.sends);
        assertEquals(1, gateway.lookups);
        assertFalse(dispatch(true).once(original).submitInvoked());
    }

    @Test
    void acceptedIsNotDeliveredAndRemainsLookupOnly() {
        gateway.submitted = Optional.of(receipt(ReceiptStatus.ACCEPTED));
        assertEquals(Outcome.ACCEPTED, dispatch(true).once(original).outcome());
        assertEquals(Outcome.ACCEPTED, dispatch(true).reconcile(original).outcome());
        assertEquals(1, gateway.sends);
        assertEquals(1, gateway.lookups);
    }

    @Test
    void failedAndDeadLetterRequireManualDispositionWithoutNewAttempt() {
        gateway.submitted = Optional.of(receipt(ReceiptStatus.FAILED));
        assertEquals(Outcome.MANUAL_REVIEW, dispatch(true).once(original).outcome());
        assertEquals(Outcome.MANUAL_REVIEW, dispatch(true).reconcile(original).outcome());
        stored.state = stored.state.append(new Command(2, UUID.randomUUID(), access.actorId(), 1,
                "manual", new DeadLettered(at.plusSeconds(4)))).journal();
        assertEquals(Outcome.MANUAL_REVIEW, dispatch(true).once(original).outcome());
        assertEquals(Outcome.MANUAL_REVIEW, dispatch(true).reconcile(original).outcome());
        assertEquals(1, gateway.sends);
        assertEquals(0, gateway.lookups);
        assertEquals(1, stored.state.state().attempts().size());
    }

    @Test
    void exactReceiptReplayUsesOriginalVersionAndCreatesNoPhantomTransition() {
        var observation = receipt(ReceiptStatus.ACCEPTED);
        gateway.submitted = Optional.of(observation);
        gateway.lookedUp = Optional.of(observation);
        dispatch(true).once(original);
        assertEquals(Outcome.ACCEPTED, dispatch(true).reconcile(original).outcome());
        assertEquals(2, stored.state.state().version());
        assertEquals(1, stored.state.state().receipts().size());
    }

    @Test
    void receiptCommitResponseLossRecoversCurrentHeadWithoutResubmission() {
        stored.loseReceiptReply = true;
        gateway.submitted = Optional.of(receipt(ReceiptStatus.DELIVERED));
        assertEquals(Outcome.RECONCILIATION_REQUIRED, dispatch(true).once(original).outcome());
        assertEquals(Outcome.DELIVERED_RECORDED, dispatch(true).reconcile(original).outcome());
        assertEquals(1, gateway.sends);
        assertEquals(0, gateway.lookups);
    }

    @Test
    void forgedReceiptScopeAndUnverifiedProviderObservationCannotBecomeDelivery() {
        gateway.submitted = Optional.of(new Observation(UUID.randomUUID(), new DeliveryReceiptState.Receipt(
                UUID.randomUUID(), scope.deliveryId(), original.requestId(), UUID.randomUUID(),
                ReceiptStatus.DELIVERED, at.plusSeconds(2)), at.plusSeconds(3)));
        assertEquals(Outcome.RECONCILIATION_REQUIRED, dispatch(true).once(original).outcome());
        assertEquals(Status.UNKNOWN, stored.state.state().status());
        stored.denyReceipt = true;
        gateway.lookedUp = Optional.of(receipt(ReceiptStatus.DELIVERED));
        assertEquals(Outcome.RECONCILIATION_REQUIRED, dispatch(true).reconcile(original).outcome());
        assertEquals(Status.UNKNOWN, stored.state.state().status());
    }

    @Test
    void changedOriginalBodyCannotReplayExistingStart() {
        dispatch(true).once(original);
        var changed = new Original(access, scope, new Command(0, start.commandId(), access.actorId(), 1,
                start.traceId(), new Started(original.requestId(), at.plusSeconds(2))));
        assertEquals(409, assertThrows(DomainException.class, () -> dispatch(true).once(changed)).status);
        assertEquals(409, assertThrows(DomainException.class, () -> dispatch(true).reconcile(changed)).status);
        assertEquals(1, gateway.sends);
    }

    @Test
    void unknownCommandCannotBorrowAnotherCommandsAcceptedResult() {
        gateway.submitted = Optional.of(receipt(ReceiptStatus.ACCEPTED));
        dispatch(true).once(original);
        var replacement = new Original(access, scope, new Command(0, UUID.randomUUID(), access.actorId(), 1,
                start.traceId(), new Started(UUID.randomUUID(), at.plusSeconds(1))));
        assertEquals(409, assertThrows(DomainException.class, () -> dispatch(true).once(replacement)).status);
        assertEquals(Outcome.ACCEPTED, dispatch(true).once(original).outcome());
        assertEquals(1, gateway.sends);
    }

    @Test
    void authorizationRevokedBeforeSubmitPreventsNetworkAndDoesNotReturnPrivateDetails() {
        denied = Step.SUBMIT;
        var failure = assertThrows(DomainException.class, () -> dispatch(true).once(original));
        assertEquals(403, failure.status);
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("PRIVATE"));
        assertEquals(0, gateway.sends);
        assertEquals(Status.UNKNOWN, stored.state.state().status());
    }

    @Test
    void crossTenantIs404AndMissingOriginalCannotBeLookedUp() {
        assertEquals(404, assertThrows(DomainException.class, () -> new Original(
                new Access(UUID.randomUUID(), access.actorId(), 1), scope, start)).status);
        assertEquals(409, assertThrows(DomainException.class, () -> dispatch(true).reconcile(original)).status);
        assertEquals(0, gateway.lookups);
    }
}
