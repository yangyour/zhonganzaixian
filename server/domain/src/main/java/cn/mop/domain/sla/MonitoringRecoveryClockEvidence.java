package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import java.time.Instant;
import java.util.UUID;

/** Shared structural gate for SLA reads and durable clock processing; it does not authenticate recovery samples. */
public final class MonitoringRecoveryClockEvidence {
    private MonitoringRecoveryClockEvidence() {}

    /**
     * Exact time for the schema2 source-evidence path. Persistence must verify the immutable proof,
     * source binding and raw seconds/nanos before supplying this value; it is never an HTTP input.
     */
    public record SourceTime(Instant recoveredAt, UUID proofId) {
        public SourceTime {
            if (recoveredAt == null || proofId == null
                    || recoveredAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || recoveredAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) {
                throw invalid();
            }
        }
    }

    /** Explicit source path permits preserved nanoseconds without weakening the existing millisecond contract. */
    public static void requireConsistentSource(Instant startedAt, Instant observedAt,
                                                boolean hasMonitoringEvents, SourceTime recovery) {
        if (recovery != null && (!hasMonitoringEvents || startedAt == null || observedAt == null
                || recovery.recoveredAt().isBefore(startedAt) || recovery.recoveredAt().isAfter(observedAt))) {
            throw invalid();
        }
    }

    /** A missing t2 keeps a linked monitor's clock running; an invalid supplied t2 must fail the transaction. */
    public static void requireConsistent(Instant startedAt, Instant observedAt,
                                         boolean hasMonitoringEvents, Instant confirmedRecoveryAt) {
        if (confirmedRecoveryAt == null) {
            return;
        }
        if (!hasMonitoringEvents || startedAt == null || observedAt == null
                || confirmedRecoveryAt.isBefore(startedAt) || confirmedRecoveryAt.isAfter(observedAt)) {
            throw invalid();
        }
        try {
            TicketSlaSnapshot.time(confirmedRecoveryAt);
        } catch (DomainException failure) {
            throw invalid();
        }
    }

    private static DomainException invalid() {
        return new DomainException(503, "RECOVERY_EVIDENCE_INVALID",
            "监控恢复时点缺少事件关联、时间精度无效或不在工单有效时间范围");
    }
}
