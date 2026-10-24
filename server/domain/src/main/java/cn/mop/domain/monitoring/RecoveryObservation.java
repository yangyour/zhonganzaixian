package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Evaluates bound source samples against an explicitly published rule; persistence supplies authority and t2. */
public final class RecoveryObservation {
    public static final int MAX_SAMPLES = 2000;
    public enum Comparison { GT, GE, LT, LE }
    public enum Quality { KNOWN, UNKNOWN, COLLECTOR_ERROR }
    public enum Status { CONFIG_PENDING, NO_SAMPLES, UNKNOWN_LATEST, STALE_LATEST, NOT_RECOVERED, OBSERVING, CONFIRMABLE }

    /** Null rule values remain pending. There are no implicit hold, gap, minimum-count or unit defaults. */
    public record Rule(String metric, Comparison comparison, BigDecimal threshold, String unit,
                       BigDecimal holdSeconds, BigDecimal maxGapSeconds, Integer minimumSamples) {
        public Rule {
            if (metric == null || !Set.of("icmp", "loss", "latency", "tcp_22", "tcp_80", "tcp_443").contains(metric)) {
                throw invalid();
            }
            for (BigDecimal value : new BigDecimal[] {threshold, holdSeconds, maxGapSeconds}) {
                if (value != null && (value.precision() > 80 || value.scale() > 40 || value.scale() < 0)) {
                    throw invalid();
                }
            }
            if (unit != null && (unit.length() > 32 || unit.codePoints().anyMatch(Character::isISOControl))
                    || holdSeconds != null && holdSeconds.signum() < 0
                    || maxGapSeconds != null && maxGapSeconds.signum() <= 0
                    || minimumSamples != null && (minimumSamples < 1 || minimumSamples > MAX_SAMPLES)) {
                throw invalid();
            }
        }

        public boolean complete() {
            return comparison != null && threshold != null && unit != null && holdSeconds != null
                && maxGapSeconds != null && minimumSamples != null;
        }
    }

    /** Nanosecond source time is preserved; unknown/error samples must not carry a numeric value. */
    public record Sample(Instant recordedAt, Quality quality, BigDecimal value) {
        public Sample {
            time(recordedAt);
            if (quality == null || (quality == Quality.KNOWN) != (value != null)
                    || value != null && (value.precision() > 80 || value.scale() > 40 || value.scale() < 0)) {
                throw invalid();
            }
        }
    }

    public record Result(Status status, Instant spanStartedAt, Instant latestSampleAt,
                         int matchingSamples, BigDecimal spanSeconds, Instant candidateRecoveredAt) {
        public boolean confirmable() {
            return status == Status.CONFIRMABLE;
        }
    }

    private RecoveryObservation() {}

    /** Samples before the current problem or policy effective time cannot establish this recovery span. */
    public static Result evaluate(Rule rule, String sourceUnit, Instant eligibleFrom,
                                  Instant observedAt, List<Sample> samples) {
        Objects.requireNonNull(rule);
        time(eligibleFrom);
        time(observedAt);
        if (eligibleFrom.isAfter(observedAt) || samples == null || samples.size() > MAX_SAMPLES) {
            throw invalid();
        }
        Instant previous = null;
        for (Sample sample : samples) {
            if (sample == null || sample.recordedAt().isAfter(observedAt)
                    || previous != null && !sample.recordedAt().isAfter(previous)) {
                throw invalid();
            }
            previous = sample.recordedAt();
        }
        if (!rule.complete()) {
            return new Result(Status.CONFIG_PENDING, null, null, 0, null, null);
        }
        if (!rule.unit().equals(sourceUnit)) {
            throw new DomainException(422, "RECOVERY_UNIT_MISMATCH", "恢复策略单位与实际来源不一致，未进行单位转换");
        }
        Instant latest = null;
        Instant start = null;
        Instant candidate = null;
        previous = null;
        int count = 0;
        Quality quality = null;
        boolean matches = false;
        for (Sample sample : samples) {
            if (sample.recordedAt().isBefore(eligibleFrom)) {
                continue;
            }
            latest = sample.recordedAt();
            if (previous != null && seconds(previous, latest).compareTo(rule.maxGapSeconds()) > 0) {
                start = null;
                count = 0;
                candidate = null;
            }
            quality = sample.quality();
            matches = quality == Quality.KNOWN && matches(rule, sample.value());
            if (matches) {
                if (start == null) {
                    start = latest;
                }
                count++;
                if (count == rule.minimumSamples()) candidate = latest;
            } else {
                start = null;
                count = 0;
                candidate = null;
            }
            previous = latest;
        }
        if (latest == null) {
            return new Result(Status.NO_SAMPLES, null, null, 0, null, null);
        }
        if (seconds(latest, observedAt).compareTo(rule.maxGapSeconds()) > 0) {
            return new Result(Status.STALE_LATEST, null, latest, 0, null, null);
        }
        if (quality != Quality.KNOWN) {
            return new Result(Status.UNKNOWN_LATEST, null, latest, 0, null, null);
        }
        if (!matches) {
            return new Result(Status.NOT_RECOVERED, null, latest, 0, BigDecimal.ZERO, null);
        }
        BigDecimal span = seconds(start, latest);
        boolean passed = candidate != null && seconds(candidate, latest).compareTo(rule.holdSeconds()) >= 0;
        return new Result(passed ? Status.CONFIRMABLE : Status.OBSERVING, start, latest, count, span, candidate);
    }

    /**
     * A bounded healthy tail cannot establish the beginning of recovery. Require a known non-recovery
     * sample in this fault episode immediately before the retained healthy run, within the published gap.
     * Unknown samples, missing history and an earlier already-healthy run do not authorize a later t2.
     */
    public static Instant requireCoveredTransition(Rule rule, Instant problemFrom, Instant eligibleFrom,
                                                   RecoverySourceBatch batch, Result result) {
        Objects.requireNonNull(rule);
        Objects.requireNonNull(batch);
        Objects.requireNonNull(result);
        time(problemFrom);
        time(eligibleFrom);
        if (problemFrom.isAfter(eligibleFrom)) {
            throw invalid();
        }
        if (result.spanStartedAt() == null) {
            return null;
        }
        Sample predecessor = null;
        for (Sample sample : batch.samples()) {
            if (!sample.recordedAt().isBefore(result.spanStartedAt())) {
                break;
            }
            predecessor = sample;
        }
        if (!rule.complete() || result.spanStartedAt().isBefore(eligibleFrom)
                || predecessor == null || predecessor.recordedAt().isBefore(problemFrom)
                || predecessor.quality() != Quality.KNOWN || matches(rule, predecessor.value())
                || seconds(predecessor.recordedAt(), result.spanStartedAt()).compareTo(rule.maxGapSeconds()) > 0) {
            throw new DomainException(422, "RECOVERY_HISTORY_INCOMPLETE",
                    "来源历史未覆盖本次故障到连续恢复的转折，不能从健康尾部补造恢复时点");
        }
        return predecessor.recordedAt();
    }

    private static boolean matches(Rule rule, BigDecimal value) {
        int order = value.compareTo(rule.threshold());
        return switch (rule.comparison()) {
            case GT -> order > 0;
            case GE -> order >= 0;
            case LT -> order < 0;
            case LE -> order <= 0;
        };
    }

    private static BigDecimal seconds(Instant from, Instant to) {
        Duration duration = Duration.between(from, to);
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private static void time(Instant value) {
        if (value == null || value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) {
            throw invalid();
        }
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "恢复观察策略或来源采样不完整、无序、超限或时间不合法");
    }
}
