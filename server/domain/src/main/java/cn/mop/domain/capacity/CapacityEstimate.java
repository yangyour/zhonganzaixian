package cn.mop.domain.capacity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** M10-7 arithmetic only. Every business/model coefficient is explicit; output is not observed capacity. */
public final class CapacityEstimate {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal DAY_SECONDS = BigDecimal.valueOf(86_400), HOURS_PER_DAY = BigDecimal.valueOf(24), TWO = BigDecimal.valueOf(2);
    private CapacityEstimate() {}
    public enum Status { ESTIMATED, UNRESOLVED }
    public record Inputs(BigInteger hosts, BigInteger itemsPerHost, BigDecimal intervalSeconds,
                         Integer historyDays, BigDecimal bytesPerValue, BigDecimal trendRatio,
                         Integer trendDays, BigDecimal headroomRatio) {}
    /** These supplied coefficients are not proof of customer approval or a measured pool limit. */
    public record Model(String version, BigDecimal bytesPerTrendRow, BigDecimal poolNvpsLimit) {}
    public record Outputs(BigDecimal nvps, BigDecimal dailyBytes, BigDecimal historyBytes, BigDecimal trendBytes,
                          BigDecimal singleNodeBytes, BigDecimal twoNodeBytes, BigDecimal peakNvps,
                          BigInteger totalItems, BigInteger pools) {}
    public record Result(Status status, String modelVersion, Outputs outputs, List<String> missingInputs) {
        public Result {
            missingInputs = List.copyOf(missingInputs);
            if (status == null || status == Status.ESTIMATED && (outputs == null || modelVersion == null || modelVersion.isBlank() || !missingInputs.isEmpty())
                    || status == Status.UNRESOLVED && (outputs != null || missingInputs.isEmpty()))
                throw new IllegalArgumentException("Estimate state and inputs must be consistent");
        }
        public boolean estimateOnly() { return true; }
        public String decimalPrecision() { return "DECIMAL128"; }
    }

    public static Result calculate(Inputs input, Model model) {
        var missing = new ArrayList<String>();
        if (input == null) missing.add("inputs");
        else {
            nonnegative(input.hosts(), "hosts"); nonnegative(input.itemsPerHost(), "items_per_host");
            positive(input.intervalSeconds(), "interval_seconds"); positive(input.bytesPerValue(), "bytes_per_value");
            nonnegativeDays(input.historyDays(), "history_days"); nonnegativeDays(input.trendDays(), "trend_days");
            ratio(input.trendRatio(), "trend_ratio"); ratio(input.headroomRatio(), "headroom_ratio");
            absent(missing, "hosts", input.hosts()); absent(missing, "items_per_host", input.itemsPerHost());
            absent(missing, "interval_seconds", input.intervalSeconds()); absent(missing, "history_days", input.historyDays());
            absent(missing, "bytes_per_value", input.bytesPerValue()); absent(missing, "trend_ratio", input.trendRatio());
            absent(missing, "trend_days", input.trendDays()); absent(missing, "headroom_ratio", input.headroomRatio());
        }
        if (model == null) missing.add("model");
        else {
            if (model.version() == null || model.version().isBlank()) missing.add("model.version");
            positive(model.bytesPerTrendRow(), "model.bytes_per_trend_row"); positive(model.poolNvpsLimit(), "model.pool_nvps_limit");
            absent(missing, "model.bytes_per_trend_row", model.bytesPerTrendRow()); absent(missing, "model.pool_nvps_limit", model.poolNvpsLimit());
        }
        if (!missing.isEmpty()) return new Result(Status.UNRESOLVED, model == null ? null : model.version(), null, missing);
        BigInteger totalItems = input.hosts().multiply(input.itemsPerHost());
        BigDecimal items = new BigDecimal(totalItems), interval = input.intervalSeconds();
        // Keep numerators exact and round each decimal output once. Pool count uses the exact ratio.
        BigDecimal dailyNumerator = items.multiply(DAY_SECONDS).multiply(input.bytesPerValue());
        BigDecimal historyNumerator = dailyNumerator.multiply(BigDecimal.valueOf(input.historyDays()));
        BigDecimal trend = items.multiply(input.trendRatio()).multiply(HOURS_PER_DAY)
                .multiply(BigDecimal.valueOf(input.trendDays())).multiply(model.bytesPerTrendRow());
        BigDecimal singleNumerator = historyNumerator.add(trend.multiply(interval)).multiply(BigDecimal.ONE.add(input.headroomRatio()));
        BigInteger pools = items.divide(interval.multiply(model.poolNvpsLimit()), 0, RoundingMode.CEILING).toBigIntegerExact();
        var outputs = new Outputs(items.divide(interval, PRECISION), dailyNumerator.divide(interval, PRECISION),
                historyNumerator.divide(interval, PRECISION), trend.round(PRECISION), singleNumerator.divide(interval, PRECISION),
                singleNumerator.multiply(TWO).divide(interval, PRECISION), items.multiply(TWO).divide(interval, PRECISION), totalItems, pools);
        return new Result(Status.ESTIMATED, model.version(), outputs, List.of());
    }
    private static void absent(List<String> missing, String name, Object value) { if (value == null) missing.add(name); }
    private static void nonnegative(BigInteger value, String name) { if (value != null && value.signum() < 0) throw new IllegalArgumentException(name + " must be nonnegative"); }
    private static void nonnegativeDays(Integer value, String name) { if (value != null && value < 0) throw new IllegalArgumentException(name + " must be nonnegative"); }
    private static void positive(BigDecimal value, String name) { if (value != null && value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive"); }
    private static void ratio(BigDecimal value, String name) { if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) throw new IllegalArgumentException(name + " must be a ratio from zero to one"); }
}
