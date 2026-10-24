package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.ServiceAvailabilityAggregate;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.ServiceEvaluation;
import cn.mop.domain.time.IntervalSets.Interval;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exact-duration arithmetic and coverage gates; no fabricated monitoring-source availability claim. */
class ServiceAvailabilityAggregateTest {

    final Instant start = Instant.parse("2026-10-01T00:00:00Z");

    ServiceEvaluation service(
        String id,
        long millis,
        List<Interval> excluded,
        List<Interval> unavailable,
        List<Interval> unknown,
        boolean observed
    ) {
        var window = new Interval(start, start.plusMillis(millis));
        return SlaAvailabilityAndCompensation.evaluateServiceEvidence(
            id,
            id,
            new BigDecimal("99.9"),
            window,
            excluded,
            unavailable,
            unknown,
            List.of(),
            observed ? List.of(window) : List.of()
        );
    }

    @Test
    void weightsDifferentEffectiveDurationsInsteadOfAveragingPercentages() {
        var healthy = service("A", 1_000_000, List.of(), List.of(), List.of(), true);
        var down = service(
            "B",
            100_000,
            List.of(),
            List.of(new Interval(start, start.plusSeconds(100))),
            List.of(),
            true
        );
        var result = ServiceAvailabilityAggregate.calculate(List.of(healthy, down));
        assertEquals(new BigDecimal("90.9091"), result.percentage());
        assertEquals(1_000_000, result.knownAvailableMillis());
        assertEquals(1_100_000, result.effectiveServiceMillis());
        assertEquals("ALL_SERVICES_QUALIFIED", result.coverageStatus());
        assertEquals(result, ServiceAvailabilityAggregate.calculate(List.of(down, healthy)));
    }

    @Test
    void approvedExclusionsUseTheSameEffectiveDenominator() {
        var healthy = service("A", 1_000_000, List.of(), List.of(), List.of(), true);
        var excluded = new Interval(start.plusSeconds(100), start.plusSeconds(1000));
        var down = service(
            "B",
            1_000_000,
            List.of(excluded),
            List.of(new Interval(start, start.plusSeconds(100))),
            List.of(),
            true
        );
        assertEquals(100_000, down.effectiveServiceMillis());
        assertEquals(
            new BigDecimal("90.9091"),
            ServiceAvailabilityAggregate.calculate(List.of(healthy, down)).percentage()
        );
    }

    @Test
    void unknownTimeIsExcludedFromBothSidesWithoutInventingHealth() {
        var unknown = new Interval(start, start.plusSeconds(10));
        var down = new Interval(start.plusSeconds(10), start.plusSeconds(15));
        var result = service("A", 100_000, List.of(), List.of(down), List.of(unknown), true);
        assertEquals(90_000, result.effectiveServiceMillis());
        assertEquals(85_000, result.knownAvailableMillis());
        assertEquals(new BigDecimal("94.4444"), ServiceAvailabilityAggregate.calculate(List.of(result)).percentage());
    }

    @Test
    void oneInsufficientServiceKeepsFleetPercentageUnknown() {
        var healthy = service("A", 1_000_000, List.of(), List.of(), List.of(), true);
        var missing = service("B", 100_000, List.of(), List.of(), List.of(), false);
        var result = ServiceAvailabilityAggregate.calculate(List.of(healthy, missing));
        assertNull(result.percentage());
        assertEquals(2, result.serviceCount());
        assertEquals(1, result.qualifiedServiceCount());
        assertEquals("INSUFFICIENT_COVERAGE", result.coverageStatus());
    }

    @Test
    void preservesSubsecondMeasuresBeforeAnyAggregateRounding() {
        var first = service("A", 1000, List.of(), List.of(new Interval(start, start.plusMillis(900))), List.of(), true);
        var second = service("B", 100, List.of(), List.of(), List.of(), true);
        assertEquals(0, first.service().knownAvailableSec());
        var result = ServiceAvailabilityAggregate.calculate(List.of(first, second));
        assertEquals(200, result.knownAvailableMillis());
        assertEquals(1100, result.effectiveServiceMillis());
        assertEquals(new BigDecimal("18.1818"), result.percentage());
    }

    @Test
    void noServicesAndNoEffectiveTimeHaveNoPercentage() {
        var empty = ServiceAvailabilityAggregate.calculate(List.of());
        assertNull(empty.percentage());
        assertEquals("NO_SERVICES", empty.coverageStatus());
        var excluded = service(
            "A",
            1000,
            List.of(new Interval(start, start.plusSeconds(1))),
            List.of(),
            List.of(),
            true
        );
        assertNull(ServiceAvailabilityAggregate.calculate(List.of(excluded)).percentage());
    }

    @Test
    void completelyUnavailableKnownServiceIsRealZero() {
        var down = service("A", 1000, List.of(), List.of(new Interval(start, start.plusSeconds(1))), List.of(), false);
        assertEquals(new BigDecimal("0.0000"), ServiceAvailabilityAggregate.calculate(List.of(down)).percentage());
    }

    @Test
    void rejectsDuplicateServiceIdentityInsteadOfDoubleCounting() {
        var row = service("A", 1000, List.of(), List.of(), List.of(), true);
        assertEquals(
            "AVAILABILITY_AGGREGATE_INVALID",
            assertThrows(DomainException.class, () -> ServiceAvailabilityAggregate.calculate(List.of(row, row))).code
        );
    }

    @Test
    void rejectsInconsistentExactMeasuresAndUnsafeProtocolIntegers() {
        var row = service("A", 1000, List.of(), List.of(), List.of(), true);
        assertThrows(DomainException.class, () -> new ServiceEvaluation(row.service(), 1000, 999));
        var enormous = service("A", 9_007_199_254_740_992L, List.of(), List.of(), List.of(), true);
        assertEquals(
            "AVAILABILITY_AGGREGATE_INVALID",
            assertThrows(DomainException.class, () -> ServiceAvailabilityAggregate.calculate(List.of(enormous))).code
        );
    }
}
