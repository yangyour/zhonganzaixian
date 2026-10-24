package cn.mop.domain.dashboard;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Contract M1-6 population: visible configured tickets that are neither closed, cancelled nor resolved. */
public interface DashboardActionableTicketsReader {
    String METRIC_VERSION = "MOP_ACTIONABLE_TICKETS_V1";
    String VIEW_VERSION = "MOP_ACTIONABLE_TICKETS_SLA_V1";
    List<String> EXCLUDED_STATES = List.of("CLOSED", "CANCELLED", "RESOLVED");
    long MAX_SAFE_INTEGER = 9_007_199_254_740_990L;

    /** Pagination changes only the returned rows, never the population used by the count or SLA totals. */
    record Page(long number, int size) {
        public Page {
            if (number < 1 || size < 1 || size > 100 || number > MAX_SAFE_INTEGER / size) {
                throw new IllegalArgumentException("Invalid actionable ticket page");
            }
        }

        public long offset() {
            return (number - 1) * size;
        }
    }

    /** Full-population SLA totals and one complete page share the same authorized database snapshot. */
    record Snapshot(UUID tenantId, UUID actorId, long permissionVersion, Instant observedAt, String scope,
                    long registrations, long knownPending, long excluded, long unconfigured,
                    long knownOverdue, long unknownEvaluated, Page page,
                    List<DashboardPendingTicketSlaReader.Item> items) {
        public Snapshot {
            if (tenantId == null || actorId == null || permissionVersion < 1
                    || permissionVersion > MAX_SAFE_INTEGER || observedAt == null || page == null
                    || !Set.of("CURRENT_TENANT_INTERNAL", "REPORTER_ONLY").contains(scope)
                    || registrations < 0 || registrations > MAX_SAFE_INTEGER
                    || knownPending < 0 || excluded < 0 || unconfigured < 0
                    || Math.addExact(Math.addExact(knownPending, excluded), unconfigured) != registrations
                    || knownOverdue < 0 || unknownEvaluated < 0
                    || Math.addExact(knownOverdue, unknownEvaluated) > knownPending) {
                throw new IllegalArgumentException("Invalid actionable ticket totals");
            }
            items = List.copyOf(items);
            long expectedSize = Math.min(page.size(), Math.max(0, knownPending - page.offset()));
            if (items.size() != expectedSize
                    || new HashSet<>(items.stream().map(item -> item.ticket().ticketId()).toList()).size() != items.size()
                    || items.stream().anyMatch(item -> !actionable(item.ticket().workflowModel(), item.ticket().state())
                            || item.ticket().registeredAt().isAfter(observedAt))
                    || items.stream().filter(item -> Boolean.TRUE.equals(item.sla().overdue())).count() > knownOverdue
                    || items.stream().filter(item -> item.sla().overdue() == null).count() > unknownEvaluated) {
                throw new IllegalArgumentException("Incomplete actionable ticket page or SLA totals");
            }
        }

        public Long pendingTickets() {
            return unconfigured == 0 ? knownPending : null;
        }

        public long unknownSla() {
            return unconfigured + unknownEvaluated;
        }

        public Long overdueSla() {
            return unknownSla() == 0 ? knownOverdue : null;
        }

        public long totalPages() {
            return knownPending / page.size() + (knownPending % page.size() == 0 ? 0 : 1);
        }
    }

    /** First validate the persisted model/state, retaining the old V1/V2 semantics for their consumers. */
    static boolean actionable(String model, String state) {
        return DashboardPendingTicketsReader.pending(model, state) && !"RESOLVED".equals(state);
    }

    Snapshot read(DashboardPendingTicketsReader.Access access, Page page);
}
