package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import java.time.Instant;

/** Shared structural gate for SLA reads and durable clock processing; it does not authenticate recovery samples. */
public final class MonitoringRecoveryClockEvidence {
    private MonitoringRecoveryClockEvidence() {}

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
