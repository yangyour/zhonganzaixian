package cn.mop.domain.sla;

import cn.mop.domain.operations.EventOperationalMetrics;
import cn.mop.domain.operations.EventOperationalMetricsReader.Sample;
import cn.mop.domain.operations.OperationalLatencyStatistics;
import cn.mop.domain.sla.MonitoringRecoveryClockEvidence.SourceTime;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.LatencyStats;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.ServiceRow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Same problem-time cohort as event metrics; recovery uses authenticated t2, never ticket resolution. */
public final class MonitoringSlaLatency {

    public static final String RECOVERY_VERSION = "mop-monitoring-recovery-t2-v1";

    private MonitoringSlaLatency() {}

    /** Persistence authenticates the optional current-cycle proof before constructing this projection. */
    public record Event(Sample sample, SourceTime recovery) {
        public Event {
            Objects.requireNonNull(sample);
        }
    }

    public record Result(int incidentCount, LatencyStats mtta, LatencyStats mttr) {}

    public static Result calculate(UUID tenant, Instant from, Instant to, Instant observedAt, List<Event> events) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(events);
        if (!from.isBefore(to) || events.size() > OperationalLatencyStatistics.MAX_SAMPLES) {
            throw new IllegalArgumentException("Explicit complete bounded monitoring window required");
        }
        var acknowledgement = EventOperationalMetrics.calculate(
            tenant,
            from,
            to,
            events.size(),
            events.stream().map(Event::sample).toList(),
            observedAt
        );
        var ackStats = acknowledgement.statistics();
        var mtta = new LatencyStats(
            acknowledgement.sampleCount().intValue(),
            0,
            ackStats == null ? null : OperationalLatencyStatistics.seconds(ackStats.meanMillis()),
            ackStats == null ? null : OperationalLatencyStatistics.seconds(ackStats.medianMillis()),
            ackStats == null || ackStats.p95Millis() == null
                ? null
                : OperationalLatencyStatistics.seconds(new BigDecimal(ackStats.p95Millis())),
            EventOperationalMetrics.VERSION,
            acknowledgement.invalidCount().intValue()
        );
        var durations = new ArrayList<Duration>();
        int unrecovered = 0;
        int unknown = 0;
        for (var event : events) {
            var sample = event.sample();
            if (event.recovery() == null) {
                if ("RECOVERED".equals(sample.monitorState())) {
                    unknown++;
                } else {
                    unrecovered++;
                }
                continue;
            }
            Instant recoveredAt = event.recovery().recoveredAt();
            if (
                !"RECOVERED".equals(sample.monitorState()) ||
                recoveredAt.isBefore(sample.problemAt()) ||
                recoveredAt.isAfter(observedAt)
            ) {
                unknown++;
                continue;
            }
            durations.add(Duration.between(sample.problemAt(), recoveredAt));
        }
        var recoveryStats = OperationalLatencyStatistics.calculateDurations(durations);
        var mttr = new LatencyStats(
            recoveryStats.sampleCount(),
            unrecovered,
            unknown > 0 ? null : OperationalLatencyStatistics.secondsFromNanos(recoveryStats.meanNanos()),
            unknown > 0 ? null : OperationalLatencyStatistics.secondsFromNanos(recoveryStats.medianNanos()),
            unknown > 0 || recoveryStats.p95Nanos() == null
                ? null
                : OperationalLatencyStatistics.secondsFromNanos(new BigDecimal(recoveryStats.p95Nanos())),
            RECOVERY_VERSION,
            unknown
        );
        return new Result(events.size(), mtta, mttr);
    }

    /** Keep availability and its coverage gates unchanged when replacing unrelated ticket-duration projections. */
    public static ServiceRow project(ServiceRow availability, Result monitoring) {
        return new ServiceRow(
            availability.serviceId(),
            availability.serviceName(),
            availability.targetPct(),
            availability.agreedServiceSec(),
            availability.approvedExclusionSec(),
            availability.effectiveServiceSec(),
            availability.unavailableUnionSec(),
            availability.unknownIntervalSec(),
            availability.knownAvailableSec(),
            availability.verifiedAvailabilityPct(),
            availability.upperBoundAvailabilityPct(),
            monitoring.incidentCount(),
            monitoring.mtta(),
            monitoring.mttr(),
            monitoring.mttr().unrecoveredCount(),
            availability.status(),
            availability.unknownPolicyStatus(),
            availability.intervalDetails()
        );
    }
}
