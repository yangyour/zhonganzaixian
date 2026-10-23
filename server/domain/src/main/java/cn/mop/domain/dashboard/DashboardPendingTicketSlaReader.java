package cn.mop.domain.dashboard;

import java.util.List;

/** Versioned, additive SLA projection. The V1 count/list contract remains unchanged. */
public interface DashboardPendingTicketSlaReader {
    String VIEW_VERSION = "MOP_PENDING_TICKETS_SLA_V2";
    int EVALUATION_LIMIT = 100;

    record Item(DashboardPendingTicketsReader.Item ticket, DashboardTicketSla.Assessment sla) {
        public Item {
            if (ticket == null || sla == null || ticket.version() != sla.version()) {
                throw new IllegalArgumentException("SLA and ticket must share an exact persisted version");
            }
        }
    }

    record Snapshot(DashboardPendingTicketsReader.Snapshot base, long evaluatedPending, long knownOverdue,
                    long unknownEvaluated, List<Item> items) {
        public Snapshot {
            items = List.copyOf(items);
            if (base == null || evaluatedPending < 0 || evaluatedPending > EVALUATION_LIMIT
                    || evaluatedPending > base.knownPending() || evaluatedPending < base.items().size()
                    || knownOverdue < 0 || unknownEvaluated < 0 || knownOverdue > evaluatedPending || unknownEvaluated > evaluatedPending
                    || knownOverdue + unknownEvaluated > evaluatedPending
                    || !items.stream().map(Item::ticket).toList().equals(base.items())
                    || knownOverdue < items.stream().filter(item -> Boolean.TRUE.equals(item.sla().overdue())).count()
                    || unknownEvaluated < items.stream().filter(item -> item.sla().overdue() == null).count()) {
                throw new IllegalArgumentException("Incomplete SLA snapshot");
            }
        }

        public long unevaluatedPending() {
            return base.knownPending() - evaluatedPending;
        }

        public long unknownSla() {
            return base.unconfigured() + unknownEvaluated + unevaluatedPending();
        }

        public Long overdueSla() {
            return unknownSla() == 0 ? knownOverdue : null;
        }
    }

    Snapshot read(DashboardPendingTicketsReader.Access access);
}
