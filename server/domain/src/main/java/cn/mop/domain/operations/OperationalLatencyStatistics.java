package cn.mop.domain.operations;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Shared elapsed-time arithmetic. Source authorization and complete populations remain caller responsibilities. */
public final class OperationalLatencyStatistics {

    public static final int MAX_SAMPLES = 10000;
    public static final String VERSION = "mop-elapsed-statistics-v1";
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private OperationalLatencyStatistics() {}

    public record Result(int sampleCount, BigDecimal meanMillis, BigDecimal medianMillis, BigInteger p95Millis) {}

    public record NanosecondResult(
        int sampleCount,
        BigDecimal meanNanos,
        BigDecimal medianNanos,
        BigInteger p95Nanos
    ) {}

    private record Moments(int count, BigDecimal mean, BigDecimal median, BigInteger p95) {}

    public static Result calculate(List<BigInteger> values) {
        var result = moments(values);
        return new Result(result.count(), result.mean(), result.median(), result.p95());
    }

    /** Recovery evidence carries original seconds/nanos; preserve them until presentation rounding. */
    public static NanosecondResult calculateDurations(List<Duration> durations) {
        if (durations == null) {
            throw new IllegalArgumentException("Explicit duration collection required");
        }
        var values = new ArrayList<BigInteger>();
        for (var duration : durations) {
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("Explicit nonnegative durations required");
            }
            values.add(
                BigInteger.valueOf(duration.getSeconds())
                    .multiply(NANOS_PER_SECOND)
                    .add(BigInteger.valueOf(duration.getNano()))
            );
        }
        var result = moments(values);
        return new NanosecondResult(result.count(), result.mean(), result.median(), result.p95());
    }

    private static Moments moments(List<BigInteger> values) {
        if (values == null || values.isEmpty()) {
            return new Moments(0, null, null, null);
        }
        if (values.size() > MAX_SAMPLES) {
            throw new IllegalArgumentException("Duration sample budget exceeded");
        }
        var sorted = new ArrayList<BigInteger>();
        BigInteger sum = BigInteger.ZERO;
        for (var value : values) {
            if (value == null || value.signum() < 0 || value.toString().length() > 40) {
                throw new IllegalArgumentException("Explicit nonnegative durations required");
            }
            sorted.add(value);
            sum = sum.add(value);
        }
        sorted.sort(BigInteger::compareTo);
        int count = sorted.size();
        BigDecimal median =
            count % 2 == 1
                ? new BigDecimal(sorted.get(count / 2))
                : new BigDecimal(sorted.get(count / 2 - 1).add(sorted.get(count / 2))).divide(BigDecimal.valueOf(2));
        return new Moments(
            count,
            new BigDecimal(sum).divide(BigDecimal.valueOf(count), MathContext.DECIMAL128),
            median,
            sorted.get((95 * count + 99) / 100 - 1)
        );
    }

    /** Existing millisecond consumers retain their nonnegative half-up display rounding. */
    public static Long seconds(BigDecimal milliseconds) {
        return milliseconds == null
            ? null
            : milliseconds.divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public static Long secondsFromNanos(BigDecimal nanoseconds) {
        return nanoseconds == null
            ? null
            : nanoseconds.divide(new BigDecimal(NANOS_PER_SECOND), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
