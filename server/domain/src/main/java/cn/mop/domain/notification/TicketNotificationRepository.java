package cn.mop.domain.notification;

import java.time.Instant;
import java.util.*;

/** Durable in-app ticket notification records. External channel delivery is intentionally absent. */
public interface TicketNotificationRepository {
    record Access(String tenantId, String actorId, long permissionVersion, String traceId) {}
    record Message(String id, String tenantId, String intentId, String ticketId, String recipientId, String audience,
                   String eventType, String title, String body, Instant createdAt, long version, Instant readAt) {}
    record Page(List<Message> items, boolean hasMore, long unreadCount, Instant observedAt) {
        public Page { items = List.copyOf(items); }
    }
    record Intent(String id, String ticketId, String receiptNo, String eventType, String phase, String priority,
                  String recipientStatus, int recipientCount, List<String> pendingReasons, String externalDispatch,
                  List<String> externalBlockers, Instant createdAt) {
        public Intent { pendingReasons = List.copyOf(pendingReasons); externalBlockers = List.copyOf(externalBlockers); }
    }
    record IntentPage(List<Intent> items, boolean hasMore, Instant observedAt) {
        public IntentPage { items = List.copyOf(items); }
    }
    /** Converts committed workflow outbox rows of one tenant into intents and per-recipient in-app records. */
    int relay(String tenantId, int limit);
    Page messages(Access access, int limit, Instant before, String beforeId, boolean unreadOnly);
    void authorizeRead(Access access, String messageId);
    Message markRead(Access access, String messageId, long expectedVersion);
    IntentPage intents(Access access, int limit, Instant before, String beforeId);
    List<String> readinessProblems();
}
