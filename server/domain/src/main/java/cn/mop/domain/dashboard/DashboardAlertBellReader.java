package cn.mop.domain.dashboard;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Current-tenant PROBLEM/NEW events; notification delivery and suppression policy are independent facts. */
public interface DashboardAlertBellReader {
    int ITEM_LIMIT = 5;
    long MAX_SAFE_INTEGER = 9_007_199_254_740_990L;
    String METRIC_VERSION = "MOP_UNACKNOWLEDGED_PROBLEM_EVENTS_V1";

    record Access(String actorId, String tenantId, long permissionVersion) { }

    /** Only identifiers and event facts needed to open the already-authorized event detail. */
    record Item(UUID eventId, UUID assetId, String ruleName, String severity, String monitorState,
                String handlingState, String suppressionState, int occurrenceCount,
                Instant lastOccurredAt, long version) {
        public Item {
            if (eventId == null || assetId == null || ruleName == null || ruleName.isBlank()
                    || ruleName.codePointCount(0, ruleName.length()) > 128
                    || severity == null || !Set.of("INFO", "WARNING", "AVERAGE", "HIGH", "DISASTER").contains(severity)
                    || !"PROBLEM".equals(monitorState) || !"NEW".equals(handlingState)
                    || suppressionState == null || !Set.of("NONE", "FLAP_SILENCED", "DEPENDENCY_SUPPRESSED", "MAINTENANCE_SILENCED", "INFO_DEFAULT_SILENT")
                            .contains(suppressionState)
                    || occurrenceCount < 1 || lastOccurredAt == null || version < 1 || version > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("Invalid alert bell item");
            }
        }
    }

    /** Count and top five must describe one database snapshot; missing rows are never replaced with zero. */
    record Snapshot(UUID tenantId, UUID actorId, long permissionVersion, Instant observedAt,
                    long total, List<Item> items) {
        public Snapshot {
            if (tenantId == null || actorId == null || permissionVersion < 1 || permissionVersion > MAX_SAFE_INTEGER
                    || observedAt == null || total < 0 || total > MAX_SAFE_INTEGER || items == null) {
                throw new IllegalArgumentException("Invalid alert bell snapshot");
            }
            items = List.copyOf(items);
            if (items.size() != Math.min(ITEM_LIMIT, total)) {
                throw new IllegalArgumentException("Incomplete alert bell snapshot");
            }
            var seen = new HashSet<UUID>();
            Item previous = null;
            for (Item item : items) {
                if (!seen.add(item.eventId()) || item.lastOccurredAt().isAfter(observedAt)
                        || previous != null && (item.lastOccurredAt().isAfter(previous.lastOccurredAt())
                        || item.lastOccurredAt().equals(previous.lastOccurredAt())
                        && item.eventId().toString().compareTo(previous.eventId().toString()) >= 0)) {
                    throw new IllegalArgumentException("Invalid alert bell ordering");
                }
                previous = item;
            }
        }
    }

    Snapshot read(Access access);
}
