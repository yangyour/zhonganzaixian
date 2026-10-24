package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.SlaComplianceScenario.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.time.IntervalSets;
import cn.mop.domain.time.IntervalSets.Interval;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pure server-side SLA availability, MTTA/MTTR percentiles, and monthly compliance/compensation evaluation
 * (M5-9, M5-10, M5-19). Unknown intervals are isolated and never counted as available (Q05);
 * official compensation amount remains null while Q26 formula/rounding is unconfirmed.
 */
public final class SlaAvailabilityAndCompensation {
    public static final String FORMULA_VERSION = "customer-20261020-availability-v2";
    private SlaAvailabilityAndCompensation() {}

    public enum ServiceStatus { MET, NOT_MET, INSUFFICIENT_SAMPLES, UNKNOWN_INTERVAL_PENDING }

    public record ExplainedInterval(String kind, Instant start, Instant end, long durationSec, String explanation) {
        public ExplainedInterval {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(explanation, "explanation");
            if (!start.isBefore(end) || durationSec <= 0) throw invalid();
        }
    }

    /** Monitoring projections retain source/unknown counts; legacy frozen results keep their original shape. */
    public record LatencyStats(int sampleCount, int unrecoveredCount, Long meanSec, Long p50Sec, Long p95Sec,
                               String sourceVersion, Integer unknownCount) {
        public LatencyStats(int sampleCount, int unrecoveredCount, Long meanSec, Long p50Sec, Long p95Sec) {
            this(sampleCount, unrecoveredCount, meanSec, p50Sec, p95Sec, null, null);
        }
    }

    public record ServiceRow(
        String serviceId,
        String serviceName,
        BigDecimal targetPct,
        Long agreedServiceSec,
        Long approvedExclusionSec,
        Long effectiveServiceSec,
        Long unavailableUnionSec,
        Long unknownIntervalSec,
        Long knownAvailableSec,
        BigDecimal verifiedAvailabilityPct,
        BigDecimal upperBoundAvailabilityPct,
        int incidentCount,
        LatencyStats mtta,
        LatencyStats mttr,
         int unrecoveredCount,
        ServiceStatus status,
        String unknownPolicyStatus,
        List<ExplainedInterval> intervalDetails
    ) {
        public ServiceRow {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(serviceName, "serviceName");
            Objects.requireNonNull(mtta, "mtta");
            Objects.requireNonNull(mttr, "mttr");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(unknownPolicyStatus, "unknownPolicyStatus");
            intervalDetails = List.copyOf(intervalDetails);
        }
    }

    public record TicketSample(
        String ticketId,
        Priority priority,
        Tier tierSnapshot,
        Exclusion exclusion,
        Reference exclusionApproval,
        Instant createdAt,
        Instant responseDueAt,
        Instant responseActualAt,
        Instant recoveryDueAt,
        Instant recoveryActualAt
    ) {
        public TicketSample {
            if (!SlaComplianceScenario.validReferenceText(ticketId)) throw invalid();
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(tierSnapshot, "tierSnapshot");
            Objects.requireNonNull(exclusion, "exclusion");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record TicketJudgment(
        String ticketId,
        String priority,
        String tierSnapshot,
        Boolean applicable,
        String outcome,
        String responseCheck,
        String recoveryCheck,
        Instant responseDueAt,
        Instant responseActualAt,
        Instant recoveryDueAt,
        Instant recoveryActualAt,
        List<String> reasons
    ) {
        public TicketJudgment {
            reasons = List.copyOf(reasons);
        }
    }

    public record MonthlyEvaluation(
        Tier tierSnapshot,
        int totalTicketCount,
        int applicableCount,
        int metCount,
        int notMetCount,
        int unresolvedCount,
        int notApplicableCount,
        Integer achievementNumerator,
        Integer achievementDenominator,
        BigDecimal achievementRatePct,
        BigDecimal compensationRatio,
        Long monthlyFeeFen,
        Long compensationAmountFen,
        String compensationStatus,
        Long candidateFixedRatioFen,
        Long candidateShortfallWeightedFen,
        String formulaVersion,
        List<TicketJudgment> items
    ) {
        public MonthlyEvaluation {
            if ((tierSnapshot == null) != (compensationRatio == null)) throw invalid();
            Objects.requireNonNull(compensationStatus, "compensationStatus");
            Objects.requireNonNull(formulaVersion, "formulaVersion");
            items = List.copyOf(items);
        }
    }

    public static ServiceRow evaluateService(
        String serviceId,
        String serviceName,
        BigDecimal targetPct,
        Interval agreedWindow,
        Collection<Interval> approvedExclusions,
        Collection<Interval> unavailableIntervals,
        Collection<Interval> unknownIntervals,
        List<TicketSample> tickets
    ) {
        return evaluateService(serviceId,serviceName,targetPct,agreedWindow,approvedExclusions,
            unavailableIntervals,unknownIntervals,tickets,List.of());
    }

    /** Coverage must come from observed sampling intervals, never from the absence of tickets. */
    public static ServiceRow evaluateService(String serviceId,String serviceName,BigDecimal targetPct,Interval agreedWindow,
        Collection<Interval> approvedExclusions,Collection<Interval> unavailableIntervals,Collection<Interval> unknownIntervals,
        List<TicketSample> tickets,Collection<Interval> observedCoverage) {
        if (serviceId == null || serviceId.isBlank() || serviceName == null || serviceName.isBlank() || agreedWindow == null)
            throw invalid();
        List<Interval> clippedExclusions = IntervalSets.clip(approvedExclusions == null ? List.of() : approvedExclusions, agreedWindow);
        List<Interval> effectiveWindows = IntervalSets.subtract(List.of(agreedWindow), clippedExclusions);
        var coverage = new ArrayList<Interval>(observedCoverage == null ? List.of() : observedCoverage);
        // A confirmed PROBLEM interval is known unavailable evidence, not evidence that any other time was healthy.
        coverage.addAll(unavailableIntervals == null ? List.of() : unavailableIntervals);
        var unknown = new ArrayList<Interval>(unknownIntervals == null ? List.of() : unknownIntervals);
        unknown.addAll(IntervalSets.subtract(effectiveWindows,coverage));
        List<Interval> clippedUnknown = IntervalSets.intersection(unknown,effectiveWindows);
        List<Interval> knownWindows = IntervalSets.subtract(effectiveWindows,clippedUnknown);
        List<Interval> clippedUnavailable = IntervalSets.intersection(unavailableIntervals == null ? List.of() : unavailableIntervals,knownWindows);
        List<Interval> knownAvailable = IntervalSets.subtract(knownWindows,clippedUnavailable);

        long agreedSec = IntervalSets.duration(List.of(agreedWindow)).toSeconds();
        long exclusionSec = IntervalSets.duration(clippedExclusions).toSeconds();
        long effectiveSec = IntervalSets.duration(knownWindows).toSeconds();
        long unavailableSec = IntervalSets.duration(clippedUnavailable).toSeconds();
        long unknownSec = IntervalSets.duration(clippedUnknown).toSeconds();
        long knownAvailableSec = IntervalSets.duration(knownAvailable).toSeconds();

        BigDecimal verifiedPct = null;
        BigDecimal upperBoundPct = null;
        long serviceMillis=IntervalSets.duration(effectiveWindows).toMillis();
        long effectiveMillis=IntervalSets.duration(knownWindows).toMillis();
        long availableMillis=IntervalSets.duration(knownAvailable).toMillis();
        boolean sufficientCoverage = serviceMillis > 0 && BigDecimal.valueOf(effectiveMillis).multiply(BigDecimal.TEN)
            .compareTo(BigDecimal.valueOf(serviceMillis).multiply(BigDecimal.valueOf(9))) >= 0;
        if (effectiveMillis > 0 && sufficientCoverage) {
            verifiedPct = BigDecimal.valueOf(availableMillis).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(effectiveMillis),4,RoundingMode.HALF_UP);
            upperBoundPct = clippedUnknown.isEmpty() ? verifiedPct : null;
        }

        List<Long> mttaSamples = new ArrayList<>();
        List<Long> mttrSamples = new ArrayList<>();
        int unrecovered = 0;
        List<TicketSample> safeTickets = tickets == null ? List.of() : tickets;
        for (TicketSample t : safeTickets) {
            if (t.responseActualAt() != null && !t.responseActualAt().isBefore(t.createdAt())) {
                mttaSamples.add(Duration.between(t.createdAt(), t.responseActualAt()).toSeconds());
            }
            if (t.recoveryActualAt() != null && !t.recoveryActualAt().isBefore(t.createdAt())) {
                mttrSamples.add(Duration.between(t.createdAt(), t.recoveryActualAt()).toSeconds());
            } else {
                unrecovered++;
            }
        }
        LatencyStats mtta = computeLatency(mttaSamples, 0);
        LatencyStats mttr = computeLatency(mttrSamples, unrecovered);

        ServiceStatus status;
        if (effectiveMillis <= 0 || !sufficientCoverage) {
            status = ServiceStatus.INSUFFICIENT_SAMPLES;
        } else if (targetPct != null && verifiedPct != null) {
            status = verifiedPct.compareTo(targetPct) >= 0 ? ServiceStatus.MET : ServiceStatus.NOT_MET;
        } else {
            status = ServiceStatus.INSUFFICIENT_SAMPLES;
        }

        List<ExplainedInterval> details = new ArrayList<>();
        for (Interval i : clippedExclusions) {
            long sec = Duration.between(i.start(), i.end()).toSeconds();
            if (sec > 0) details.add(new ExplainedInterval("APPROVED_EXCLUSION", i.start(), i.end(), sec,
                "经批准排除区间（从约定服务时段中扣除，不计入有效服务时间分母）"));
        }
        for (Interval i : clippedUnavailable) {
            long sec = Duration.between(i.start(), i.end()).toSeconds();
            if (sec > 0) details.add(new ExplainedInterval("UNAVAILABLE_UNION", i.start(), i.end(), sec,
                "不可用时间并集（重叠故障已合并去重，从有效服务时间中扣除）"));
        }
        for (Interval i : clippedUnknown) {
            long sec = Duration.between(i.start(), i.end()).toSeconds();
            if (sec > 0) details.add(new ExplainedInterval("UNKNOWN_DATA_GAP", i.start(), i.end(), sec,
                "无数据/未知区间（不默认视为可用，同时从可用率分子和分母剔除）"));
        }
        for (Interval i : knownAvailable) {
            long sec = Duration.between(i.start(), i.end()).toSeconds();
            if (sec > 0) details.add(new ExplainedInterval("KNOWN_AVAILABLE", i.start(), i.end(), sec,
                "已核实可用区间（有效服务时段扣除不可用并集与未知区间后的实际可用时段）"));
        }

        return new ServiceRow(
            serviceId,
            serviceName,
            targetPct,
            agreedSec,
            exclusionSec,
            effectiveSec,
            unavailableSec,
            unknownSec,
            knownAvailableSec,
            verifiedPct,
            upperBoundPct,
            safeTickets.size(),
            mtta,
            mttr,
            unrecovered,
            status,
            !clippedUnknown.isEmpty() ? "UNKNOWN_EXCLUDED_FROM_NUMERATOR_AND_DENOMINATOR" : "NO_UNKNOWN_GAPS",
            details
        );
    }

    public static LatencyStats computeLatency(List<Long> samplesSec, int unrecoveredCount) {
        if (samplesSec == null || samplesSec.isEmpty()) {
            return new LatencyStats(0, Math.max(0, unrecoveredCount), null, null, null);
        }
        var values=samplesSec.stream().map(v->java.math.BigInteger.valueOf(v).multiply(java.math.BigInteger.valueOf(1000))).toList();
        var result=cn.mop.domain.operations.OperationalLatencyStatistics.calculate(values);
        return new LatencyStats(result.sampleCount(),Math.max(0,unrecoveredCount),
                cn.mop.domain.operations.OperationalLatencyStatistics.seconds(result.meanMillis()),
                cn.mop.domain.operations.OperationalLatencyStatistics.seconds(result.medianMillis()),
                result.p95Millis().divide(java.math.BigInteger.valueOf(1000)).longValueExact());
    }



    public static MonthlyEvaluation evaluateMonthly(Tier tierSnapshot, Long monthlyFeeFen, List<TicketSample> samples) {
        if (samples == null || samples.size() > SlaComplianceScenario.MAX_TICKETS) {
            throw invalid();
        }
        BigDecimal ratio =
            tierSnapshot == null
                ? null
                : switch (tierSnapshot) {
                      case BASIC -> new BigDecimal("0.10");
                      case STANDARD -> new BigDecimal("0.20");
                      case ADVANCED -> new BigDecimal("0.30");
                  };
        List<SlaComplianceScenario.Decision> decisions = new ArrayList<>(samples.size());
        List<TicketJudgment> items = new ArrayList<>(samples.size());
        Set<String> ids = new HashSet<>();
        for (TicketSample s : samples) {
            if (!ids.add(s.ticketId())) {
                throw invalid();
            }
            var decision = SlaComplianceScenario.evaluateSource(
                new SlaComplianceScenario.SourceEvidence(
                    s.priority(),
                    s.exclusion(),
                    s.exclusionApproval(),
                    new Reference("tier-" + s.tierSnapshot().name(), 1L),
                    s.responseDueAt(),
                    s.recoveryDueAt(),
                    s.responseActualAt(),
                    s.recoveryActualAt()
                )
            );
            decisions.add(decision);
            items.add(
                new TicketJudgment(
                    s.ticketId(),
                    s.priority().name(),
                    s.tierSnapshot().name(),
                    decision.applicable(),
                    decision.outcome().name(),
                    decision.response().name(),
                    decision.recovery().name(),
                    s.responseDueAt(),
                    s.responseActualAt(),
                    s.recoveryDueAt(),
                    s.recoveryActualAt(),
                    decision.reasons().stream().map(Enum::name).toList()
                )
            );
        }
        int applicable = (int) decisions
            .stream()
            .filter(value -> Boolean.TRUE.equals(value.applicable()))
            .count();
        int met = (int) decisions
            .stream()
            .filter(value -> value.outcome() == Outcome.MET)
            .count();
        int notMet = (int) decisions
            .stream()
            .filter(value -> value.outcome() == Outcome.NOT_MET)
            .count();
        int unresolved = (int) decisions
            .stream()
            .filter(value -> value.outcome() == Outcome.UNRESOLVED)
            .count();
        int notApplicable = (int) decisions
            .stream()
            .filter(value -> value.outcome() == Outcome.NOT_APPLICABLE)
            .count();
        Integer num = applicable > 0 ? met : null;
        Integer den = applicable > 0 ? applicable : null;
        BigDecimal ratePct =
            applicable > 0
                ? BigDecimal.valueOf(met * 100.0).divide(BigDecimal.valueOf(applicable), 2, RoundingMode.HALF_UP)
                : null;

        Long fixedCandidate = null;
        Long shortfallCandidate = null;
        if (ratio != null && monthlyFeeFen != null && monthlyFeeFen > 0 && applicable > 0 && notMet > 0) {
            fixedCandidate = BigDecimal.valueOf(monthlyFeeFen)
                .multiply(ratio)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .longValueExact();
            // Customer specification 5.5 explicitly rejects shortfall-weighted compensation.
        }
        return new MonthlyEvaluation(
            tierSnapshot,
            samples.size(),
            applicable,
            met,
            notMet,
            unresolved,
            notApplicable,
            num,
            den,
            ratePct,
            ratio,
            monthlyFeeFen,
            null,
            "CONFIG_PENDING_Q26",
            fixedCandidate,
            shortfallCandidate,
            FORMULA_VERSION,
            items
        );
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "SLA报表或月度补偿计算输入无效");
    }
}
