package cn.mop.persistence;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Tenant/reporter filtering, totals and top five are evaluated in one read-only repeatable-read transaction. */
public final class PostgresDashboardPendingTicketsReader implements DashboardPendingTicketsReader {
    private static final String VISIBLE = """
            WITH visible AS (
              SELECT t.id,t.receipt_no,t.title,t.created_at,
                     CASE WHEN w.version IS NOT NULL AND l.version IS NOT NULL THEN 'CONFLICT'
                          WHEN w.version IS NOT NULL THEN 'WORKFLOW_V33'
                          WHEN l.version IS NOT NULL THEN 'LIFECYCLE_V42'
                          ELSE 'UNCONFIGURED' END AS model,
                     CASE WHEN w.version IS NOT NULL THEN w.snapshot->>'state' ELSE l.state END AS state,
                     COALESCE(w.version,l.version) AS version,w.snapshot AS workflow_snapshot
                FROM public.mop_ticket t
                LEFT JOIN LATERAL (
                  SELECT version,snapshot FROM public.mop_ticket_workflow_event
                   WHERE tenant_id=t.tenant_id AND ticket_id=t.id ORDER BY version DESC LIMIT 1
                ) w ON true
                LEFT JOIN LATERAL (
                  SELECT version,state FROM public.mop_ticket_lifecycle_revision
                   WHERE tenant_id=t.tenant_id AND ticket_id=t.id ORDER BY version DESC LIMIT 1
                ) l ON true
               WHERE t.tenant_id=?::uuid AND (? OR t.reporter_id=?::uuid)
            )
            """;

    /** Existing project database; failure never falls back to an empty successful page. */
    private final DataSource source;

    /** Guards against accidentally joining a command transaction. */
    private final PostgresPlatformRepository platform;

    public PostgresDashboardPendingTicketsReader(DataSource source, PostgresPlatformRepository platform) {
        this.source = Objects.requireNonNull(source);
        this.platform = Objects.requireNonNull(platform);
    }

    @Override
    public Snapshot read(Access access) {
        return readSnapshot(access, (connection, snapshot) -> snapshot);
    }

    /** Additional projections share the authorized read-only transaction and its observed timestamp. */
    @FunctionalInterface
    interface SnapshotProjection<T> {
        T project(Connection connection, Snapshot snapshot) throws SQLException;
    }

    <T> T readSnapshot(Access access, SnapshotProjection<T> projection) {
        platform.requireStandaloneRead();
        if (access == null) {
            throw expired();
        }
        try (var connection = source.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                PostgresAuthorization.require(connection, access.actorId(), access.tenantId(),
                        access.permissionVersion(), "dashboard:read");
                PostgresAuthorization.require(connection, access.actorId(), access.tenantId(),
                        access.permissionVersion(), "tickets:read");
                String role;
                try (var statement = connection.prepareStatement("SELECT m.role_code "
                        + "FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id "
                        + "WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=?")) {
                    statement.setQueryTimeout(10);
                    statement.setString(1, access.tenantId());
                    statement.setString(2, access.actorId());
                    statement.setLong(3, access.permissionVersion());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw expired();
                        }
                        role = rows.getString(1);
                    }
                }
                boolean internal = Capabilities.internal(role);
                if (!internal && !Capabilities.customer(role)) {
                    throw unavailable();
                }
                long knownPending = 0;
                long terminal = 0;
                long unconfigured = 0;
                try (var statement = query(connection, access, internal,
                        "SELECT model,state,count(*) AS count FROM visible GROUP BY model,state")) {
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            String model = rows.getString("model");
                            String state = rows.getString("state");
                            long count = rows.getLong("count");
                            if (count < 1) {
                                throw unavailable();
                            }
                            if ("UNCONFIGURED".equals(model) && state == null) {
                                unconfigured = Math.addExact(unconfigured, count);
                            } else if (DashboardPendingTicketsReader.pending(model, state)) {
                                knownPending = Math.addExact(knownPending, count);
                            } else {
                                terminal = Math.addExact(terminal, count);
                            }
                        }
                    }
                }
                var items = new ArrayList<Item>();
                try (var statement = query(connection, access, internal,
                        "SELECT id,receipt_no,title,created_at,model,state,version FROM visible "
                                + "WHERE model<>'UNCONFIGURED' AND state NOT IN ('CLOSED','CANCELLED') "
                                + "ORDER BY created_at DESC,id DESC LIMIT 5")) {
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            items.add(new Item(UUID.fromString(rows.getString("id")), rows.getString("receipt_no"),
                                    rows.getString("title"), rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                                    rows.getString("model"), rows.getString("state"), rows.getLong("version")));
                        }
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
                        access.permissionVersion(), observed, internal ? "CURRENT_TENANT_INTERNAL" : "REPORTER_ONLY",
                        Math.addExact(Math.addExact(knownPending, terminal), unconfigured),
                        knownPending, terminal, unconfigured, items);
                T result = projection.project(connection, snapshot);
                connection.commit();
                return result;
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

    static PreparedStatement query(Connection connection, Access access, boolean internal, String sql)
            throws SQLException {
        var statement = connection.prepareStatement(VISIBLE + sql);
        statement.setQueryTimeout(10);
        statement.setString(1, access.tenantId());
        statement.setBoolean(2, internal);
        statement.setString(3, access.actorId());
        return statement;
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前成员或权限版本已失效");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "UPSTREAM_UNAVAILABLE", "待办工单来源或状态不一致，未返回替代数量");
    }
}
