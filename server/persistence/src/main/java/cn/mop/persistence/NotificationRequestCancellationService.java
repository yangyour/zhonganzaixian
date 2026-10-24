package cn.mop.persistence;

import static cn.mop.persistence.NotificationControlStore.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import cn.mop.persistence.NotificationControlStore.Access;
import cn.mop.persistence.NotificationControlStore.Entry;
import cn.mop.persistence.NotificationControlStore.Kind;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/** Cancels only pre-dispatch routing requests and their unsent routes, with an atomic audit and original receipt. */
public final class NotificationRequestCancellationService {

    /** Immutable control revisions and current command authorization. */
    private final NotificationControlStore store;
    /** Existing project audit chain joins the same owned database transaction. */
    private final PostgresPlatformRepository platform;

    public NotificationRequestCancellationService(
        NotificationControlStore store,
        PostgresPlatformRepository platform
    ) {
        this.store = java.util.Objects.requireNonNull(store);
        this.platform = java.util.Objects.requireNonNull(platform);
    }

    /** Serialize against dispatch registration, preserve exact original-command receipts, and roll back if audit fails. */
    public Entry cancel(Access access, UUID requestId, long expectedVersion, UUID command, String reason) {
        if (
            requestId == null ||
            command == null ||
            expectedVersion < 0 ||
            expectedVersion >= 100000 ||
            reason == null ||
            reason.isBlank() ||
            reason.length() > 1000 ||
            reason.codePoints().anyMatch(value -> value < 32 && value != 10 && value != 13 && value != 9)
        ) {
            throw invalid();
        }
        String digest = hash(requestId + "\n" + expectedVersion + "\n" + reason);
        return store.transaction(access, "configurations:write", connection -> {
            authorize(connection, access, "ticket_notes:read_internal");
            authorize(connection, access, "audit:read");
            NotificationControlSchemaVerifier.verify(connection);
            DeliveryReceiptSchemaVerifier.verify(connection);
            var replay = store.replay(
                connection,
                access,
                Kind.REQUEST,
                requestId,
                expectedVersion,
                command,
                "CANCEL_REQUEST",
                digest
            );
            if (replay.isPresent()) {
                return replay.get();
            }
            var request = store
                .current(connection, access.tenantId(), Kind.REQUEST, requestId, true)
                .orElseThrow(NotificationRequestCancellationService::missing);
            var committedWhileWaiting = store.replay(
                connection,
                access,
                Kind.REQUEST,
                requestId,
                expectedVersion,
                command,
                "CANCEL_REQUEST",
                digest
            );
            if (committedWhileWaiting.isPresent()) {
                return committedWhileWaiting.get();
            }
            if (request.version() != expectedVersion) {
                throw conflict();
            }
            ObjectNode state = object(request);
            if (!Set.of("WAITING_CONFIGURATION", "ROUTED").contains(state.path("status").asText())) {
                throw blocked();
            }
            UUID intent = uuid(state.path("intent_id").asText());
            UUID receiver = uuid(state.path("receiver_id").asText());
            String phase = state.path("phase").asText();
            if (
                !requestId.toString().equals(state.path("message_id").asText()) ||
                !phase.matches("[A-Z][A-Z0-9_]{0,63}")
            ) {
                throw unavailable();
            }
            NotificationCancellationGuard.lock(connection, access.tenantId(), intent, receiver, phase);
            // A queued V50 root is already a dispatch registration; cancellation cannot pretend to withdraw it.
            try (
                var query = query(
                    connection,
                    "SELECT 1 FROM public.mop_delivery_receipt_journal WHERE tenant_id=? AND event_id=? " +
                        "AND receiver_id=? AND stage=? AND version=0 LIMIT 1"
                )
            ) {
                query.setObject(1, access.tenantId());
                query.setObject(2, intent);
                query.setObject(3, receiver);
                query.setString(4, phase);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        throw blocked();
                    }
                }
            }
            var routes = new ArrayList<Entry>();
            for (var channel : Channel.values()) {
                UUID routeId = identity(
                    "notification-route|" + access.tenantId() + "|" + requestId + "|" + channel
                );
                var route = store
                    .current(connection, access.tenantId(), Kind.ROUTE, routeId, true)
                    .orElse(null);
                if (route == null) {
                    continue;
                }
                ObjectNode values = object(route);
                if (
                    !requestId.toString().equals(values.path("message_id").asText()) ||
                    !intent.toString().equals(values.path("intent_id").asText()) ||
                    !receiver.toString().equals(values.path("receiver_id").asText()) ||
                    !phase.equals(values.path("phase").asText()) ||
                    !channel.name().equals(values.path("channel").asText()) ||
                    !values.path("attempt").isIntegralNumber() ||
                    !values.path("attempt").canConvertToLong()
                ) {
                    throw unavailable();
                }
                if (
                    values.path("attempt").asLong() != 0 ||
                    !Set.of("QUEUED", "WAITING_CONFIGURATION").contains(values.path("status").asText())
                ) {
                    throw blocked();
                }
                routes.add(route);
            }
            String cancelledAt = now(connection).toString();
            for (var route : routes) {
                ObjectNode cancelled = cancelled(object(route), access, reason, cancelledAt);
                cancelled.put("request_sha256", hash(digest + "\n" + route.id()));
                store.append(
                    connection,
                    access,
                    Kind.ROUTE,
                    route.id(),
                    route.version(),
                    identity("notification-cancel-route|" + command + "|" + route.id()),
                    "CANCEL_ROUTE",
                    cancelled
                );
            }
            state = cancelled(state, access, reason, cancelledAt);
            state.put("cancelled_route_count", routes.size());
            state.put("request_sha256", digest);
            var result = store.append(
                connection,
                access,
                Kind.REQUEST,
                requestId,
                expectedVersion,
                command,
                "CANCEL_REQUEST",
                state
            );
            platform.auditInTransaction(
                connection,
                access.actorId().toString(),
                access.tenantId().toString(),
                "notification.request.cancel",
                "notification_request",
                requestId.toString(),
                reason,
                access.trace()
            );
            return result;
        });
    }

    private static ObjectNode cancelled(ObjectNode state, Access access, String reason, String time) {
        state.put("status", "CANCELLED");
        state.put("reason", "MANUALLY_CANCELLED_BEFORE_DISPATCH");
        state.put("cancellation_reason", reason);
        state.put("cancelled_by", access.actorId().toString());
        state.put("cancelled_at", time);
        state.put("external_dispatch_enabled", false);
        return state;
    }

    private static ObjectNode object(Entry entry) {
        if (!(entry.state() instanceof ObjectNode object)) {
            throw unavailable();
        }
        return object;
    }

    private static UUID uuid(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw unavailable();
        }
        return UUID.fromString(value);
    }

    private static java.time.Instant now(Connection connection) throws SQLException {
        try (
            var query = query(connection, "SELECT date_trunc('milliseconds',clock_timestamp())");
            var row = query.executeQuery()
        ) {
            if (!row.next()) {
                throw unavailable();
            }
            return row.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static DomainException blocked() {
        return new DomainException(
            409,
            "NOTIFICATION_CANCELLATION_BLOCKED",
            "请求已取消、已有投递登记或路由已开始处理，请核对原请求"
        );
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "取消通知需要当前版本和明确的取消原因");
    }

    private static DomainException missing() {
        return new DomainException(404, "NOT_FOUND", "当前租户未找到此通知请求");
    }
}
