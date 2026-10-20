package cn.mop.domain.sla;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure M5-18 plans. A future fenced Worker must atomically claim each key and write notification intents. */
public final class SlaReminderPlanner {
    private SlaReminderPlanner() {}
    public enum Clock { RESPONSE, RECOVERY }
    public enum State { RUNNING, PAUSED, COMPLETED }
    public record Key(UUID tenantId, UUID ticketId, Clock clock, BigDecimal threshold) {
        public Key {
            if (tenantId == null || ticketId == null || clock == null || threshold == null
                    || threshold.signum() <= 0 || threshold.compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("Scoped clock and threshold in (0,1] required");
            threshold = threshold.stripTrailingZeros();
        }
    }
    public record Plan(String status, List<Key> due, boolean escalationRequired) {
        public Plan { due = List.copyOf(due); }
        public boolean delivered() { return false; }
        public boolean claimed() { return false; }
    }

    /**
     * Elapsed time must already use the opening snapshot's calendar and Q27 policy.
     * Missing timing, configured progress nodes or durable deduplication state is unresolved.
     * No default thresholds, recipients or retry intervals are supplied.
     */
    public static Plan plan(UUID tenantId, UUID ticketId, Clock clock, State state,
                            Duration elapsed, Duration budget, List<BigDecimal> thresholds, Set<Key> recorded) {
        if (tenantId == null || ticketId == null || clock == null || state == null)
            throw new IllegalArgumentException("Explicit scoped clock state required");
        if (elapsed != null && elapsed.isNegative() || budget != null && (budget.isNegative() || budget.isZero()))
            throw new IllegalArgumentException("Nonnegative elapsed and positive budget required");
        if (state != State.RUNNING) return new Plan(state.name(), List.of(), false);
        if (elapsed == null || budget == null || thresholds == null || recorded == null)
            return new Plan("CONFIG_PENDING", List.of(), false);
        if (thresholds.isEmpty() || thresholds.size() > 100)
            throw new IllegalArgumentException("Explicit bounded nonempty reminder progress list required");
        var candidates = new ArrayList<Key>();
        var unique = new HashSet<Key>();
        for (BigDecimal threshold : thresholds) {
            if (threshold != null && (threshold.scale() > 18 || threshold.scale() < -18 || threshold.precision() > 32))
                throw new IllegalArgumentException("Progress exceeds technical precision budget");
            var key = new Key(tenantId, ticketId, clock, threshold);
            if (!unique.add(key)) throw new IllegalArgumentException("Duplicate numeric progress node");
            candidates.add(key);
        }
        for (Key key : recorded) {
            if (key == null || !tenantId.equals(key.tenantId()) || !ticketId.equals(key.ticketId()) || clock != key.clock())
                throw new IllegalArgumentException("Deduplication state belongs to another clock");
        }
        candidates.sort((a,b)->a.threshold().compareTo(b.threshold()));
        BigDecimal elapsedNanos = new BigDecimal(nanos(elapsed));
        BigDecimal budgetNanos = new BigDecimal(nanos(budget));
        var due = candidates.stream().filter(key -> !recorded.contains(key)
                && elapsedNanos.compareTo(budgetNanos.multiply(key.threshold())) >= 0).toList();
        // Escalation is a plan tied to the unique 100% node, not permission to contact anyone.
        return new Plan("PLANNED", due, due.stream().anyMatch(key -> key.threshold().compareTo(BigDecimal.ONE) == 0));
    }
    private static BigInteger nanos(Duration value) {
        return BigInteger.valueOf(value.getSeconds()).multiply(BigInteger.valueOf(1_000_000_000L)).add(BigInteger.valueOf(value.getNano()));
    }
}
