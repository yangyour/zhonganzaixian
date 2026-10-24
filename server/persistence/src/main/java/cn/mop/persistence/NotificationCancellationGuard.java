package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Serializes pre-dispatch cancellation with V50 queue creation for the same immutable notification source. */
final class NotificationCancellationGuard {

    private NotificationCancellationGuard() {}

    static void lock(Connection connection, UUID tenant, UUID intent, UUID receiver, String phase)
        throws SQLException {
        // A waiter must see the winner's committed cancellation after acquiring the source lock.
        if (
            connection.getAutoCommit() ||
            connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED
        ) {
            throw new SQLException("Notification cancellation requires a current READ_COMMITTED transaction");
        }
        try (
            var statement = NotificationControlStore.query(
                connection,
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))"
            )
        ) {
            statement.setString(
                1,
                "notification-source-cancel|" + tenant + "|" + intent + "|" + receiver + "|" + phase
            );
            statement.executeQuery().close();
        }
    }

    /** Old V50-only databases have no routing source; a present V51 journal must pass its frozen guard. */
    static void beforeQueue(Connection connection, Scope scope, NotificationControlStore store)
        throws SQLException {
        try (
            var statement = NotificationControlStore.query(
                connection,
                "SELECT to_regclass('public.mop_notification_control') IS NOT NULL"
            );
            var row = statement.executeQuery()
        ) {
            if (!row.next()) {
                throw new SQLException("Notification routing catalogue unavailable");
            }
            if (!row.getBoolean(1)) {
                return;
            }
        }
        var key = scope.key();
        lock(connection, scope.tenantId(), key.eventId(), key.receiverId(), key.stage());
        NotificationControlSchemaVerifier.verify(connection);
        try (
            var statement = NotificationControlStore.query(
                connection,
                "SELECT h.* FROM public.mop_notification_control r CROSS JOIN LATERAL " +
                    "(SELECT * FROM public.mop_notification_control h WHERE h.tenant_id=r.tenant_id AND h.kind=r.kind " +
                    "AND h.object_id=r.object_id ORDER BY version DESC LIMIT 1) h " +
                    "WHERE r.tenant_id=? AND r.kind='REQUEST' AND r.version=0 AND r.state->>'intent_id'=? " +
                    "AND r.state->>'receiver_id'=? AND r.state->>'phase'=?"
            )
        ) {
            statement.setObject(1, scope.tenantId());
            statement.setString(2, key.eventId().toString());
            statement.setString(3, key.receiverId().toString());
            statement.setString(4, key.stage());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var state = store.entry(rows).state();
                    if (
                        !key.eventId().toString().equals(state.path("intent_id").asText()) ||
                        !key.receiverId().toString().equals(state.path("receiver_id").asText()) ||
                        !key.stage().equals(state.path("phase").asText()) ||
                        !state.path("status").isTextual()
                    ) {
                        throw new SQLException("Notification cancellation source scope mismatch");
                    }
                    if ("CANCELLED".equals(state.path("status").asText())) {
                        throw new DomainException(
                            409,
                            "DELIVERY_STATE_CONFLICT",
                            "通知来源已取消，不能登记新的投递"
                        );
                    }
                }
            }
        }
    }
}
