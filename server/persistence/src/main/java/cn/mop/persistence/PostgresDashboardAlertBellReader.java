package cn.mop.persistence;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.DashboardAlertBellReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** M1-8 count and latest five, with the contract's exact state predicate in one read-only snapshot. */
public final class PostgresDashboardAlertBellReader implements DashboardAlertBellReader {
    private static final String FILTER = " FROM public.mop_monitoring_event "
            + "WHERE tenant_id=?::uuid AND monitor_state='PROBLEM' AND handling_state='NEW'";

    /** Existing restricted project connection; no fixture or unscoped query fallback. */
    private final DataSource source;

    /** Prevents a read projection from accidentally borrowing an idempotent command transaction. */
    private final PostgresPlatformRepository platform;

    public PostgresDashboardAlertBellReader(DataSource source, PostgresPlatformRepository platform) {
        this.source = Objects.requireNonNull(source);
        this.platform = Objects.requireNonNull(platform);
    }

    @Override
    public Snapshot read(Access access) {
        platform.requireStandaloneRead();
        if (access == null || !uuid(access.tenantId()) || !uuid(access.actorId())
                || access.permissionVersion() < 1 || access.permissionVersion() > MAX_SAFE_INTEGER) {
            throw expired();
        }
        try (var connection = source.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                PostgresAuthorization.require(connection, access.actorId(), access.tenantId(), access.permissionVersion(), "dashboard:read");
                PostgresAuthorization.require(connection, access.actorId(), access.tenantId(), access.permissionVersion(), "tickets:read");
                try (var statement = connection.prepareStatement("SELECT m.role_code FROM public.mop_membership m "
                        + "JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid "
                        + "AND m.active AND u.active AND m.permission_version=?")) {
                    statement.setQueryTimeout(10);
                    statement.setString(1, access.tenantId());
                    statement.setString(2, access.actorId());
                    statement.setLong(3, access.permissionVersion());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw expired();
                        }
                        if (!Capabilities.internal(rows.getString(1))) {
                            throw new DomainException(403, "ACTION_FORBIDDEN", "当前角色不可读取内部未确认告警");
                        }
                    }
                }
                long total;
                try (var statement = query(connection, access, "SELECT count(*)" + FILTER);
                     var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw unavailable();
                    }
                    total = rows.getLong(1);
                }
                var items = new ArrayList<Item>();
                try (var statement = query(connection, access,
                        "SELECT id,asset_id,rule_name,severity,monitor_state,handling_state,suppression_state,"
                                + "occurrence_count,last_occurred_at,version" + FILTER
                                + " ORDER BY last_occurred_at DESC,id DESC LIMIT 5");
                     var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        items.add(new Item(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("asset_id")),
                                rows.getString("rule_name"), rows.getString("severity"), rows.getString("monitor_state"),
                                rows.getString("handling_state"), rows.getString("suppression_state"), rows.getInt("occurrence_count"),
                                rows.getObject("last_occurred_at", OffsetDateTime.class).toInstant(), rows.getLong("version")));
                    }
                }
                Instant observed;
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (var rows = statement.executeQuery("SELECT clock_timestamp()::timestamptz(3)")) {
                        if (!rows.next()) {
                            throw unavailable();
                        }
                        observed = rows.getObject(1, OffsetDateTime.class).toInstant();
                    }
                }
                var snapshot = new Snapshot(UUID.fromString(access.tenantId()), UUID.fromString(access.actorId()),
                        access.permissionVersion(), observed, total, items);
                connection.commit();
                return snapshot;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof DomainException domain) {
                    throw domain;
                }
                throw unavailable();
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private static PreparedStatement query(Connection connection, Access access, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(10);
        statement.setString(1, access.tenantId());
        return statement;
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前成员或权限版本已失效");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "UPSTREAM_UNAVAILABLE", "未确认告警来源不可用，未返回替代数量");
    }
}
