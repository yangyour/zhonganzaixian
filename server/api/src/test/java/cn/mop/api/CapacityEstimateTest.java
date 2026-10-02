package cn.mop.api;

import cn.mop.domain.capacity.CapacityEstimate;
import cn.mop.domain.capacity.CapacityEstimate.Inputs;
import cn.mop.domain.capacity.CapacityEstimate.Model;
import cn.mop.domain.capacity.CapacityEstimate.Status;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic model parameters only; no server measurements or procurement baseline. */
class CapacityEstimateTest {
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static Inputs input() { return new Inputs(BigInteger.TEN, BigInteger.valueOf(100), decimal("10"), 2, decimal("80"), decimal("0.5"), 3, decimal("0.25")); }
    private static Model model() { return new Model("SYNTHETIC-ONLY-v1", decimal("128"), decimal("60")); }
    private static void same(String expected, BigDecimal actual) { assertEquals(0, decimal(expected).compareTo(actual)); }

    @Test void allNineOutputsMatchIndependentlyCalculatedValues() {
        var result = CapacityEstimate.calculate(input(), model()); var output = result.outputs();
        assertEquals(Status.ESTIMATED, result.status()); assertTrue(result.estimateOnly()); assertEquals("DECIMAL128", result.decimalPrecision());
        same("100", output.nvps()); same("691200000", output.dailyBytes()); same("1382400000", output.historyBytes());
        same("4608000", output.trendBytes()); same("1733760000", output.singleNodeBytes()); same("3467520000", output.twoNodeBytes()); same("200", output.peakNvps());
        assertEquals(BigInteger.valueOf(1000), output.totalItems()); assertEquals(BigInteger.TWO, output.pools());
    }
    @Test void missingModelDoesNotInjectPrototypeCoefficients() {
        var missing = CapacityEstimate.calculate(input(), null);
        assertEquals(Status.UNRESOLVED, missing.status()); assertNull(missing.outputs()); assertEquals(List.of("model"), missing.missingInputs());
        var partial = CapacityEstimate.calculate(input(), new Model(null, null, null));
        assertNull(partial.outputs()); assertEquals(List.of("model.version", "model.bytes_per_trend_row", "model.pool_nvps_limit"), partial.missingInputs());
    }
    @Test void everyMissingUserInputIsExplicitlyReported() {
        var result = CapacityEstimate.calculate(new Inputs(null, null, null, null, null, null, null, null), model());
        assertEquals(Status.UNRESOLVED, result.status()); assertNull(result.outputs()); assertEquals(8, result.missingInputs().size());
        assertThrows(UnsupportedOperationException.class, () -> result.missingInputs().clear());
        assertNull(CapacityEstimate.calculate(null, model()).outputs());
    }
    @Test void denominatorsAndSizesCannotBeZeroOrNegative() {
        for (var value : List.of(BigDecimal.ZERO, decimal("-1"))) {
            assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.ONE, BigInteger.ONE, value, 1, BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ZERO), model()));
            assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.ONE, BigInteger.ONE, BigDecimal.ONE, 1, value, BigDecimal.ONE, 1, BigDecimal.ZERO), model()));
            assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(input(), new Model("test", value, BigDecimal.ONE)));
            assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(input(), new Model("test", BigDecimal.ONE, value)));
        }
    }
    @Test void countsDaysAndRatioBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.valueOf(-1), BigInteger.ONE, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ZERO), model()));
        assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.ONE, BigInteger.valueOf(-1), BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ZERO), model()));
        assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.ONE, BigInteger.ONE, BigDecimal.ONE, -1, BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ZERO), model()));
        assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.ONE, BigInteger.ONE, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE, -1, BigDecimal.ZERO), model()));
        for (var ratio : List.of(decimal("-0.01"), decimal("1.01"))) {
            assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.ONE, BigInteger.ONE, BigDecimal.ONE, 1, BigDecimal.ONE, ratio, 1, BigDecimal.ZERO), model()));
            assertThrows(IllegalArgumentException.class, () -> CapacityEstimate.calculate(new Inputs(BigInteger.ONE, BigInteger.ONE, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE, 1, ratio), model()));
        }
    }
    @Test void explicitZeroCountsAreValidButDoNotHideMissingParameters() {
        var zero = new Inputs(BigInteger.ZERO, BigInteger.ONE, BigDecimal.ONE, 0, BigDecimal.ONE, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        var result = CapacityEstimate.calculate(zero, model());
        same("0", result.outputs().nvps()); same("0", result.outputs().twoNodeBytes()); assertEquals(BigInteger.ZERO, result.outputs().pools());
        assertEquals(Status.UNRESOLVED, CapacityEstimate.calculate(zero, null).status());
    }
    @Test void decimalIntervalsUseDeclaredPrecisionWithoutBinaryFloatingPoint() {
        var input = new Inputs(BigInteger.ONE, BigInteger.ONE, decimal("3"), 1, BigDecimal.ONE, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        var result = CapacityEstimate.calculate(input, new Model("test", BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(BigDecimal.ONE.divide(decimal("3"), MathContext.DECIMAL128), result.outputs().nvps());
        same("28800", result.outputs().dailyBytes()); same("28800", result.outputs().singleNodeBytes());
    }
    @Test void poolCeilingUsesUnroundedInputsAtTheIntegerBoundary() {
        var input = new Inputs(BigInteger.ONE, BigInteger.ONE, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        var justBelowOne = decimal("0.9999999999999999999999999999999999999999");
        assertEquals(BigInteger.TWO, CapacityEstimate.calculate(input, new Model("test", BigDecimal.ONE, justBelowOne)).outputs().pools());
        assertEquals(BigInteger.ONE, CapacityEstimate.calculate(input, new Model("test", BigDecimal.ONE, BigDecimal.ONE)).outputs().pools());
    }
    @Test void largeItemCountsDoNotOverflowFixedWidthIntegers() {
        var hosts = BigInteger.TEN.pow(30);
        var input = new Inputs(hosts, BigInteger.TEN, decimal("10"), 1, BigDecimal.ONE, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        var result = CapacityEstimate.calculate(input, new Model("test", BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(BigInteger.TEN.pow(31), result.outputs().totalItems()); assertEquals(hosts, result.outputs().pools());
    }
    @Test void resultsCannotMixPendingAndEstimatedStates() {
        assertThrows(IllegalArgumentException.class, () -> new CapacityEstimate.Result(Status.ESTIMATED, "test", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapacityEstimate.Result(Status.UNRESOLVED, null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapacityEstimate.Result(Status.UNRESOLVED, "test", CapacityEstimate.calculate(input(), model()).outputs(), List.of("missing")));
    }
}
