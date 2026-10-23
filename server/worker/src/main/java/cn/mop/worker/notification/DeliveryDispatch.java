package cn.mop.worker.notification;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.ReceiptRecorded;
import cn.mop.domain.notification.DeliveryReceiptState.Started;
import cn.mop.domain.notification.DeliveryReceiptState.Status;
import cn.mop.persistence.DeliveryReceiptJournal;
import cn.mop.persistence.DeliveryReceiptJournal.Command;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.PostgresDeliveryReceiptRepository;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Access;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Receipt;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** V50-backed single-attempt consumer. Durable START acknowledgment precedes any provider call. */
public final class DeliveryDispatch {
    public enum Step { READ, START, SUBMIT, LOOKUP, RECORD, RETURN }
    public enum Outcome { DISABLED, RECONCILIATION_REQUIRED, ACCEPTED, DELIVERED_RECORDED, MANUAL_REVIEW }

    /** Explicit runtime authorization; absence of a published binding does not enable provider access. */
    public record Binding(boolean enabled, String publication) {
        public Binding {
            if (enabled && (publication == null || publication.isBlank() || publication.length() > 256)) {
                throw invalid();
            }
        }
    }

    /** Check current tenant authority, route publication, binding, quiet hours and limits at each boundary. */
    @FunctionalInterface
    public interface Authority {
        void check(Binding binding, Access access, Scope scope, Step step);
    }

    /** Provider adapters must authenticate and correlate observations; the repository verifier checks again. */
    public interface Provider {
        Optional<Observation> submit(Scope scope, UUID originalRequestId);
        Optional<Observation> lookup(Scope scope, UUID originalRequestId);
    }

    /** Retained provider observation identity, not raw payload or a caller's declaration of authenticity. */
    public record Observation(UUID commandId, DeliveryReceiptState.Receipt receipt, Instant observedAt) {
        public Observation {
            if (commandId == null || receipt == null || observedAt == null) {
                throw invalid();
            }
        }
    }

    /** The caller retains this exact command across retries; the consumer never invents a replacement request. */
    public record Original(Access access, Scope scope, Command start) {
        public Original {
            if (access == null || scope == null || start == null || !(start.transition() instanceof Started)
                    || !access.actorId().equals(start.actorId()) || access.permissionVersion() != start.permissionVersion()) {
                throw invalid();
            }
            if (!access.tenantId().equals(scope.tenantId())) {
                throw new DomainException(404, "NOT_FOUND", "当前范围未找到通知投递");
            }
        }

        public UUID requestId() {
            return ((Started) start.transition()).requestId();
        }
    }

    /** Recorded status only: V50 itself does not certify provider signatures or terminal delivery. */
    public record Result(Outcome outcome, Status journalStatus, boolean submitInvoked, boolean lookupInvoked) {
        public boolean providerReceiptVerified() {
            return false;
        }
    }

    // The test seam cannot be selected by runtime configuration; public assembly requires the PostgreSQL repository.
    interface Journal {
        Optional<Receipt> current(Access access, Scope scope);
        Receipt append(Access access, Scope scope, Command command);
    }

    private final Binding binding;
    private final Authority authority;
    private final Provider provider;
    private final Journal journal;

    public DeliveryDispatch(Binding binding, Authority authority, Provider provider,
                            PostgresDeliveryReceiptRepository repository) {
        this(binding, authority, provider, postgres(Objects.requireNonNull(repository)));
    }

    DeliveryDispatch(Binding binding, Authority authority, Provider provider, Journal journal) {
        this.binding = Objects.requireNonNull(binding);
        this.authority = Objects.requireNonNull(authority);
        this.provider = Objects.requireNonNull(provider);
        this.journal = Objects.requireNonNull(journal);
    }

    /** First acknowledged START may submit once. An existing command or uncertain commit can only be reconciled. */
    public Result once(Original original) {
        Objects.requireNonNull(original);
        if (!binding.enabled()) {
            return new Result(Outcome.DISABLED, null, false, false);
        }
        Receipt current = read(original);
        var known = current.journal().command(original.start().commandId());
        if (known.isPresent()) {
            if (!known.get().equals(original.start())) {
                throw conflict();
            }
            return result(original, current.journal().state(), false, false);
        }
        // Failed/dead-letter work needs a separately authorized disposition; this worker never spends a retry budget.
        if (current.journal().state().status() != Status.QUEUED) {
            // Another command owns the attempt; its success must not be presented as this command's result.
            throw conflict();
        }
        check(original, Step.START);
        Receipt started;
        try {
            started = journal.append(original.access(), original.scope(), original.start());
            validate(started, original.scope());
            if (!started.journal().command(original.start().commandId()).filter(original.start()::equals).isPresent()) {
                throw unavailable();
            }
        } catch (RuntimeException uncertain) {
            return uncertain(original, false, false);
        }
        if (started.replay() || started.journal().state().status() != Status.UNKNOWN) {
            return result(original, started.journal().state(), false, false);
        }
        check(original, Step.SUBMIT);
        Optional<Observation> observation;
        try {
            observation = provider.submit(original.scope(), original.requestId());
            if (observation == null) {
                throw unavailable();
            }
        } catch (RuntimeException uncertain) {
            return uncertain(original, true, false);
        }
        return record(original, started, observation, true, false);
    }

    /** A lookup uses the persisted request ID; UNKNOWN and ACCEPTED never route back to submit. */
    public Result reconcile(Original original) {
        Objects.requireNonNull(original);
        if (!binding.enabled()) {
            return new Result(Outcome.DISABLED, null, false, false);
        }
        Receipt current = read(original);
        if (!current.journal().command(original.start().commandId()).filter(original.start()::equals).isPresent()) {
            throw conflict();
        }
        Status status = current.journal().state().status();
        if (status != Status.UNKNOWN && status != Status.ACCEPTED) {
            return result(original, current.journal().state(), false, false);
        }
        var attempts = current.journal().state().attempts();
        if (!attempts.get(attempts.size() - 1).requestId().equals(original.requestId())) {
            throw conflict();
        }
        check(original, Step.LOOKUP);
        Optional<Observation> observation;
        try {
            observation = provider.lookup(original.scope(), original.requestId());
            if (observation == null) {
                throw unavailable();
            }
        } catch (RuntimeException uncertain) {
            return uncertain(original, false, true);
        }
        return record(original, current, observation, false, true);
    }

    private Result record(Original original, Receipt current, Optional<Observation> observation,
                          boolean submitted, boolean lookedUp) {
        if (observation.isEmpty()) {
            return result(original, current.journal().state(), submitted, lookedUp);
        }
        var observed = observation.get();
        var receipt = observed.receipt();
        if (!receipt.tenantId().equals(original.scope().tenantId())
                || !receipt.deliveryId().equals(original.scope().deliveryId())
                || !receipt.requestId().equals(original.requestId())) {
            return uncertain(original, submitted, lookedUp);
        }
        check(original, Step.RECORD);
        try {
            var existing = current.journal().command(observed.commandId());
            Command command;
            if (existing.isPresent()) {
                command = existing.get();
                if (!(command.transition() instanceof ReceiptRecorded recorded)
                        || !recorded.receipt().equals(receipt) || !recorded.observedAt().equals(observed.observedAt())
                        || !command.actorId().equals(original.access().actorId())
                        || command.permissionVersion() != original.access().permissionVersion()) {
                    throw conflict();
                }
            } else {
                command = new Command(current.journal().state().version(), observed.commandId(),
                        original.access().actorId(), original.access().permissionVersion(), original.start().traceId(),
                        new ReceiptRecorded(receipt, observed.observedAt()));
            }
            Receipt stored = journal.append(original.access(), original.scope(), command);
            validate(stored, original.scope());
            return result(original, stored.journal().state(), submitted, lookedUp);
        } catch (RuntimeException uncertain) {
            return uncertain(original, submitted, lookedUp);
        }
    }

    private Receipt read(Original original) {
        check(original, Step.READ);
        try {
            var current = journal.current(original.access(), original.scope()).orElseThrow(DeliveryDispatch::unavailable);
            validate(current, original.scope());
            return current;
        } catch (DomainException rejected) {
            if (rejected.status == 403 || rejected.status == 404) {
                throw new DomainException(rejected.status, "DELIVERY_DISPATCH_DENIED", "当前身份无权核对该通知投递");
            }
            throw unavailable();
        } catch (RuntimeException rejected) {
            throw unavailable();
        }
    }

    private Result result(Original original, DeliveryReceiptState state, boolean submitted, boolean lookedUp) {
        check(original, Step.RETURN);
        Outcome outcome = switch (state.status()) {
            case DELIVERED -> Outcome.DELIVERED_RECORDED;
            case ACCEPTED -> Outcome.ACCEPTED;
            case FAILED, DEAD_LETTER -> Outcome.MANUAL_REVIEW;
            default -> Outcome.RECONCILIATION_REQUIRED;
        };
        return new Result(outcome, state.status(), submitted, lookedUp);
    }

    private Result uncertain(Original original, boolean submitted, boolean lookedUp) {
        check(original, Step.RETURN);
        return new Result(Outcome.RECONCILIATION_REQUIRED, null, submitted, lookedUp);
    }

    private void check(Original original, Step step) {
        try {
            authority.check(binding, original.access(), original.scope(), step);
        } catch (RuntimeException denied) {
            throw new DomainException(403, "DELIVERY_DISPATCH_DENIED", "通知投递当前授权或前置条件未满足");
        }
    }

    private static void validate(Receipt receipt, Scope scope) {
        if (receipt == null || receipt.journal() == null
                || !scope.tenantId().equals(receipt.journal().state().tenantId())
                || !scope.deliveryId().equals(receipt.journal().state().deliveryId())
                || !scope.key().equals(receipt.journal().state().deduplication())) {
            throw unavailable();
        }
    }

    private static Journal postgres(PostgresDeliveryReceiptRepository repository) {
        return new Journal() {
            @Override
            public Optional<Receipt> current(Access access, Scope scope) {
                return repository.current(access, scope);
            }

            @Override
            public Receipt append(Access access, Scope scope, Command command) {
                return repository.append(access, scope, command);
            }
        };
    }

    private static DomainException invalid() {
        return new DomainException(400, "INVALID_DELIVERY_DISPATCH", "通知原命令或运行绑定无效");
    }

    private static DomainException conflict() {
        return new DomainException(409, "DELIVERY_DISPATCH_CONFLICT", "通知原命令不匹配；没有生成替代请求");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "DELIVERY_DISPATCH_UNAVAILABLE", "通知当前记录未能核验；没有恢复发送权限");
    }
}
