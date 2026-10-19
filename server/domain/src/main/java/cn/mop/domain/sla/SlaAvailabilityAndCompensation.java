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
    public static final String FORMULA_VERSION = "contract-m5-9-m5-19-v1";
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

    public record LatencyStats(int sampleCount, int unrecoveredCount, Long meanSec, Long p50Sec, Long p95Sec) {}

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
            Objects.requireNonNull(tierSnapshot, "tierSnapshot");
            Objects.requireNonNull(compensationRatio, "compensationRatio");
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
        if (serviceId == null || serviceId.isBlank() || serviceName == null || serviceName.isBlank() || agreedWindow == null)
            throw invalid();
        List<Interval> clippedExclusions = IntervalSets.clip(approvedExclusions == null ? List.of() : approvedExclusions, agreedWindow);
        List<Interval> effectiveWindows = IntervalSets.subtract(List.of(agreedWindow), clippedExclusions);
        List<Interval> clippedUnavailable = IntervalSets.intersection(unavailableIntervals == null ? List.of() : unavailableIntervals, effectiveWindows);
        List<Interval> remainingAfterUnavailable = IntervalSets.subtract(effectiveWindows, clippedUnavailable);
        List<Interval> clippedUnknown = IntervalSets.intersection(unknownIntervals == null ? List.of() : unknownIntervals, remainingAfterUnavailable);
        List<Interval> knownAvailable = IntervalSets.subtract(remainingAfterUnavailable, clippedUnknown);

        long agreedSec = IntervalSets.duration(List.of(agreedWindow)).toSeconds();
        long exclusionSec = IntervalSets.duration(clippedExclusions).toSeconds();
        long effectiveSec = IntervalSets.duration(effectiveWindows).toSeconds();
        long unavailableSec = IntervalSets.duration(clippedUnavailable).toSeconds();
        long unknownSec = IntervalSets.duration(clippedUnknown).toSeconds();
        long knownAvailableSec = IntervalSets.duration(knownAvailable).toSeconds();

        BigDecimal verifiedPct = null;
        BigDecimal upperBoundPct = null;
        if (effectiveSec > 0) {
            // Verified availability strictly excludes both unavailable union AND unknown intervals from the numerator (M5-9).
            verifiedPct = BigDecimal.valueOf(knownAvailableSec * 100.0 / effectiveSec).setScale(4, RoundingMode.HALF_UP);
            upperBoundPct = BigDecimal.valueOf((effectiveSec - unavailableSec) * 100.0 / effectiveSec).setScale(4, RoundingMode.HALF_UP);
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
        if (effectiveSec <= 0 || (safeTickets.isEmpty() && unavailableSec == 0 && unknownSec == 0)) {
            status = ServiceStatus.INSUFFICIENT_SAMPLES;
        } else if (unknownSec > 0) {
            // Unknown intervals are never silently counted as available; if verifiedPct < targetPct, mark NOT_MET, else UNKNOWN_INTERVAL_PENDING.
            if (targetPct != null && verifiedPct != null && verifiedPct.compareTo(targetPct) < 0) {
                status = ServiceStatus.NOT_MET;
            } else {
                status = ServiceStatus.UNKNOWN_INTERVAL_PENDING;
            }
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
                "无数据/未知区间（单独列示，不默认视为可用，已从已核实可用时长中剔除）"));
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
            unknownSec > 0 ? "CONFIG_PENDING_Q05_UNKNOWN_NOT_COUNTED_AVAILABLE" : "NO_UNKNOWN_GAPS",
            details
        );
    }

    public static LatencyStats computeLatency(List<Long> samplesSec, int unrecoveredCount) {
        if (samplesSec == null || samplesSec.isEmpty()) {
            return new LatencyStats(0, Math.max(0, unrecoveredCount), null, null, null);
        }
        List<Long> sorted = new ArrayList<>(samplesSec);
        sorted.sort(Long::compareTo);
        long sum = 0;
        for (long v : sorted) sum += v;
        long mean = Math.round((double) sum / sorted.size());
        long p50 = percentileNearestRank(sorted, 50);
        long p95 = percentileNearestRank(sorted, 95);
        return new LatencyStats(sorted.size(), Math.max(0, unrecoveredCount), mean, p50, p95);
    }

    private static long percentileNearestRank(List<Long> sorted, int percentile) {
        if (percentile == 50 && sorted.size() % 2 == 0) {
            int mid = sorted.size() / 2;
            return Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
        }
        int rank = (int) Math.ceil((percentile / 100.0) * sorted.size());
        int idx = Math.max(0, Math.min(sorted.size() - 1, rank - 1));
        return sorted.get(idx);
    }

    public static MonthlyEvaluation evaluateMonthly(Tier tierSnapshot, Long monthlyFeeFen, List<TicketSample> samples) {
        if (tierSnapshot == null || samples == null || samples.size() > SlaComplianceScenario.MAX_TICKETS) throw invalid();
        BigDecimal ratio = switch (tierSnapshot) {
            case BASIC -> new BigDecimal("0.10");
            case STANDARD -> new BigDecimal("0.20");
            case ADVANCED -> new BigDecimal("0.30");
        };
        List<Evidence> evidences = new ArrayList<>(samples.size());
        Map<String, TicketSample> byId = new LinkedHashMap<>();
        for (TicketSample s : samples) {
            if (byId.put(s.ticketId(), s) != null) throw invalid();
            evidences.add(new Evidence(
                s.ticketId(),
                s.priority(),
                s.exclusion(),
                s.exclusionApproval(),
                new Reference("tier-" + s.tierSnapshot().name(), 1L),
                s.responseDueAt(),
                s.recoveryDueAt(),
                s.responseActualAt(),
                s.recoveryActualAt()
            ));
        }
        Aggregate agg = SlaComplianceScenario.aggregate(evidences, true);
        List<TicketJudgment> items = new ArrayList<>(agg.items().size());
        for (Evaluation eval : agg.items()) {
            Evidence ev = eval.evidence();
            TicketSample orig = byId.get(ev.ticketId());
            items.add(new TicketJudgment(
                ev.ticketId(),
                ev.priority().name(),
                orig.tierSnapshot().name(),
                eval.applicable(),
                eval.outcome().name(),
                eval.response().name(),
                eval.recovery().name(),
                ev.responseDueAt(),
                ev.responseActualAt(),
                ev.recoveryDueAt(),
                ev.recoveryActualAt(),
                eval.reasons().stream().map(Enum::name).toList()
            ));
        }
        int applicable = agg.knownApplicable();
        int met = agg.met();
        int notMet = agg.notMet();
        int unresolved = agg.timingUnresolved() + agg.applicabilityUnknown();
        int notApplicable = agg.notApplicable();
        Integer num = applicable > 0 ? met : null;
        Integer den = applicable > 0 ? applicable : null;
        BigDecimal ratePct = applicable > 0
            ? BigDecimal.valueOf(met * 100.0).divide(BigDecimal.valueOf(applicable), 2, RoundingMode.HALF_UP)
            : null;

        Long fixedCandidate = null;
        Long shortfallCandidate = null;
        if (monthlyFeeFen != null && monthlyFeeFen > 0 && applicable > 0 && notMet > 0) {
            fixedCandidate = BigDecimal.valueOf(monthlyFeeFen).multiply(ratio).setScale(0, RoundingMode.HALF_UP).longValue();
            shortfallCandidate = BigDecimal.valueOf(monthlyFeeFen)
                .multiply(ratio)
                .multiply(BigDecimal.valueOf(applicable - met))
                .divide(BigDecimal.valueOf(applicable), 0, RoundingMode.HALF_UP)
                .longValue();
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
