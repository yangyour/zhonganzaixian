package cn.mop.worker.notification;

import cn.mop.domain.notification.TicketNotificationRepository;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bounded fair polling over the durable workflow outbox; successful database commit is the acknowledgment. */
public final class TicketNotificationRelay {
    private final TicketNotificationRepository repository;
    private final List<String> tenants;
    private final int batchSize;

    /** Counts only confirmed commits. A lost database response remains retryable through outbox deduplication. */
    public record Cycle(int confirmedIntents, int failedTenants, boolean dependencyReady) {}

    public TicketNotificationRelay(TicketNotificationRepository repository, List<String> tenants, int batchSize) {
        this.repository = Objects.requireNonNull(repository);
        this.tenants = List.copyOf(tenants);
        if (this.tenants.isEmpty() || this.tenants.size() > 100
                || this.tenants.stream().distinct().count() != this.tenants.size()
                || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Explicit bounded tenant scope and batch required");
        }
        this.batchSize = batchSize;
    }

    /** One batch per allowed tenant avoids an endlessly growing tenant starving later tenants. */
    public Cycle poll(BooleanSupplier running) {
        Objects.requireNonNull(running);
        if (!running.getAsBoolean()) {
            return new Cycle(0, 0, false);
        }
        try {
            if (!repository.readinessProblems().isEmpty()) {
                return new Cycle(0, 0, false);
            }
        } catch (RuntimeException unavailable) {
            return new Cycle(0, 0, false);
        }
        int confirmed = 0;
        int failed = 0;
        for (String tenant : tenants) {
            if (!running.getAsBoolean()) {
                break;
            }
            try {
                int count = repository.relay(tenant, batchSize);
                if (count < 0 || count > batchSize) {
                    throw new IllegalStateException("Invalid relay result");
                }
                confirmed += count;
            } catch (RuntimeException unavailable) {
                // The outbox/intent transaction remains authoritative; do not invent an acknowledgment.
                failed++;
            }
        }
        return new Cycle(confirmed, failed, true);
    }
}
