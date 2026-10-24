package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.ServiceEvaluation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;

/** Aggregates exact known-service durations; partial coverage never becomes a successful fleet percentage. */
public final class ServiceAvailabilityAggregate {

    public static final String SOURCE_VERSION = "mop-availability-effective-milliseconds-v1";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ServiceAvailabilityAggregate() {}

    public record Result(
        BigDecimal percentage,
        long knownAvailableMillis,
        long effectiveServiceMillis,
        int serviceCount,
        int qualifiedServiceCount,
        String coverageStatus
    ) {}

    public static Result calculate(List<ServiceEvaluation> services) {
        if (services == null || services.size() > 1000) {
            throw invalid();
        }
        long available = 0;
        long effective = 0;
        int qualified = 0;
        var identities = new HashSet<String>();
        try {
            for (var evaluation : services) {
                if (evaluation == null || !identities.add(evaluation.service().serviceId())) {
                    throw invalid();
                }
                available = Math.addExact(available, evaluation.knownAvailableMillis());
                effective = Math.addExact(effective, evaluation.effectiveServiceMillis());
                if (available > MAX_SAFE_INTEGER || effective > MAX_SAFE_INTEGER) {
                    throw invalid();
                }
                if (evaluation.service().verifiedAvailabilityPct() != null) {
                    qualified++;
                }
            }
        } catch (ArithmeticException failure) {
            throw invalid();
        }
        boolean sufficient = !services.isEmpty() && qualified == services.size() && effective > 0;
        BigDecimal percentage = sufficient
            ? BigDecimal.valueOf(available)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(effective), 4, RoundingMode.HALF_UP)
            : null;
        return new Result(
            percentage,
            available,
            effective,
            services.size(),
            qualified,
            services.isEmpty() ? "NO_SERVICES" : sufficient ? "ALL_SERVICES_QUALIFIED" : "INSUFFICIENT_COVERAGE"
        );
    }

    private static DomainException invalid() {
        return new DomainException(
            503,
            "AVAILABILITY_AGGREGATE_INVALID",
            "服务可用性来源、去重或有效时长范围不一致，未返回部分汇总"
        );
    }
}
