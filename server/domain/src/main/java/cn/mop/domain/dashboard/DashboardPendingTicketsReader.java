package cn.mop.domain.dashboard;

import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.WorkflowLifecycle;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A single authorized database snapshot of visible registrations and their current workflow heads. */
public interface DashboardPendingTicketsReader {
    String METRIC_VERSION = "MOP_VISIBLE_NONTERMINAL_TICKETS_V1";
    int ITEM_LIMIT = 5;

    record Access(String actorId, String tenantId, long permissionVersion) { }

    /** State belongs to the indicated persisted model; no SLA, priority or assignee inference. */
    record Item(UUID ticketId, String receiptNo, String title, Instant registeredAt,
                String workflowModel, String state, long version) {
        public Item {
            if (ticketId == null || receiptNo == null || receiptNo.isBlank() || title == null || title.isBlank()
                    || registeredAt == null || version < 1 || version > 9_007_199_254_740_990L
                    || !pending(workflowModel, state)) {
                throw new IllegalArgumentException("Invalid pending ticket projection");
            }
        }
    }

    /** Missing workflow state makes the full pending count unknown, while exact known counts remain available. */
    record Snapshot(UUID tenantId, UUID actorId, long permissionVersion, Instant observedAt, String scope,
                    long registrations, long knownPending, long terminal, long unconfigured, List<Item> items) {
        public Snapshot {
            if (tenantId == null || actorId == null || permissionVersion < 1
                    || permissionVersion > 9_007_199_254_740_990L || observedAt == null
                    || !Set.of("CURRENT_TENANT_INTERNAL", "REPORTER_ONLY").contains(scope)
                    || registrations < 0 || registrations > 9_007_199_254_740_990L
                    || knownPending < 0 || terminal < 0 || unconfigured < 0
                    || Math.addExact(Math.addExact(knownPending, terminal), unconfigured) != registrations) {
                throw new IllegalArgumentException("Invalid pending ticket counts");
            }
            items = List.copyOf(items);
            if (items.size() != Math.min(ITEM_LIMIT, knownPending)
                    || new HashSet<>(items.stream().map(Item::ticketId).toList()).size() != items.size()
                    || items.stream().anyMatch(item -> item.registeredAt().isAfter(observedAt))) {
                throw new IllegalArgumentException("Incomplete pending ticket page");
            }
        }

        public Long pendingTickets() {
            return unconfigured == 0 ? knownPending : null;
        }
    }

    /** Reject unknown models/states rather than counting them as resolved or silently ignoring them. */
    static boolean pending(String model, String state) {
        return switch (model) {
            case "WORKFLOW_V33" -> !Set.of(WorkflowLifecycle.State.CLOSED, WorkflowLifecycle.State.CANCELLED)
                    .contains(WorkflowLifecycle.State.valueOf(state));
            case "LIFECYCLE_V42" -> TicketLifecycle.State.valueOf(state) != TicketLifecycle.State.CLOSED;
            default -> throw new IllegalArgumentException("Unknown persisted workflow model");
        };
    }

    Snapshot read(Access access);
}
