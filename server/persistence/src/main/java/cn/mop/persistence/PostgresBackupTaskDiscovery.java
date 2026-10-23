package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupArchiveRepository.Phase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Read-only, bounded discovery over V48/V49 durable facts. A discovered task is not execution permission. */
public final class PostgresBackupTaskDiscovery {
    public record AssetScope(UUID tenantId, UUID assetId) {
        public AssetScope {
            if (tenantId == null || assetId == null) {
                throw invalid();
            }
        }
    }

    public record Cursor(AssetScope scope, Instant stagedAt, UUID versionId) {
        public Cursor {
            if (scope == null || versionId == null || !validInstant(stagedAt)) {
                throw invalid();
            }
        }
    }

    /** Immutable source identity allows stable commands to survive process restarts. No proof is inferred. */
    public record Task(Scope scope, UUID submissionId, long stagedRunVersion, Instant stagedAt,
            Phase phase, long archiveEventVersion, UUID dispatchId, String dispatchOwner, long dispatchCount) {
        public Task {
            if (scope == null || submissionId == null || stagedRunVersion < 1
                    || stagedRunVersion > 9007199254740991L || !validInstant(stagedAt)
                    || phase == null || archiveEventVersion < 0 || archiveEventVersion > 9007199254740991L
                    || dispatchCount < 0 || dispatchCount > 9007199254740991L
                    || (dispatchCount == 0 ? dispatchId != null || dispatchOwner != null
                            : dispatchId == null || dispatchOwner == null
                                    || !dispatchOwner.matches("[A-Za-z0-9._:-]{1,128}"))) {
                throw invalid();
            }
        }

        public boolean requiresReconciliation() {
            return phase == Phase.STORE_STARTED || phase == Phase.VERIFY_REQUIRED;
        }

        public boolean executionAuthorized() {
            return false;
        }
    }

    public record Page(List<Task> items, Cursor nextAfter) {
        public Page {
            items = List.copyOf(items);
        }
    }

    @FunctionalInterface
    interface Authorizer {
        void authorize(Connection connection, Access access, AssetScope scope) throws SQLException;
    }

    private final DataSource source;
    private final Authorizer authorizer;

    /** Public assembly requires the concrete current-grant/database authorization boundary. */
    public PostgresBackupTaskDiscovery(DataSource source, PostgresBackupTaskAuthorization authorization) {
        this(source, authorization(Objects.requireNonNull(authorization)));
    }

    PostgresBackupTaskDiscovery(DataSource source, Authorizer authorizer) {
        this.source = Objects.requireNonNull(source);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    public Page discover(Access access, AssetScope scope, Cursor after, int limit) {
        if (access == null || scope == null || limit < 1 || limit > 50) {
            throw invalid();
        }
        if (!access.tenantId().equals(scope.tenantId()) || after != null && !after.scope().equals(scope)) {
            throw missing();
        }
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout='5s'");
                    statement.execute("SET LOCAL statement_timeout='10s'");
                }
                authorizer.authorize(connection, access, scope);
                requireAsset(connection, scope);
                var tasks = new ArrayList<Task>();
                String sql = """
                        SELECT s.tenant_id,s.asset_id,s.run_id,s.attempt_number,s.version_id,
                               s.submission_id,s.run_version,s.recorded_at,
                               e.phase,e.event_version,e.dispatch_id,e.dispatch_owner,e.dispatch_count
                        FROM public.mop_backup_archive_submission s
                        JOIN LATERAL (
                            SELECT phase,event_version,dispatch_id,dispatch_owner,dispatch_count
                            FROM public.mop_backup_archive_event
                            WHERE tenant_id=s.tenant_id AND version_id=s.version_id
                            ORDER BY event_version DESC LIMIT 1
                        ) e ON true
                        WHERE s.tenant_id=? AND s.asset_id=?
                          AND NOT EXISTS (SELECT 1 FROM public.mop_backup_version_registration v
                                          WHERE v.tenant_id=s.tenant_id AND v.version_id=s.version_id)
                        """ + (after == null ? "" : " AND (s.recorded_at,s.version_id)>(?,?)")
                        + " ORDER BY s.recorded_at,s.version_id LIMIT ?";
                try (var query = connection.prepareStatement(sql)) {
                    query.setQueryTimeout(12);
                    query.setObject(1, scope.tenantId());
                    query.setObject(2, scope.assetId());
                    int index = 3;
                    if (after != null) {
                        query.setObject(index++, after.stagedAt().atOffset(ZoneOffset.UTC));
                        query.setObject(index++, after.versionId());
                    }
                    query.setInt(index, limit + 1);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            Task task = task(rows);
                            if (!task.scope().tenantId().equals(scope.tenantId())
                                    || !task.scope().assetId().equals(scope.assetId())) {
                                throw unavailable();
                            }
                            tasks.add(task);
                        }
                    }
                }
                authorizer.authorize(connection, access, scope);
                boolean more = tasks.size() > limit;
                if (more) {
                    tasks.remove(tasks.size() - 1);
                }
                Task last = tasks.isEmpty() ? null : tasks.get(tasks.size() - 1);
                Cursor next = more ? new Cursor(scope, last.stagedAt(), last.scope().versionId()) : null;
                connection.commit();
                return new Page(tasks, next);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // A read result is never returned after an uncertain transaction outcome.
                }
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            if (failure instanceof DomainException denied && (denied.status == 403 || denied.status == 404)) {
                throw new DomainException(denied.status, "BACKUP_TASK_ACCESS_DENIED", "当前备份任务范围不可访问");
            }
            throw unavailable();
        }
    }

    private static Authorizer authorization(PostgresBackupTaskAuthorization authorization) {
        return (connection, access, scope) -> authorization.require(connection, access,
                scope.tenantId(), scope.assetId(), Operation.DISCOVER);
    }

    private static void requireAsset(Connection connection, AssetScope scope) throws SQLException {
        try (var query = connection.prepareStatement("SELECT id FROM public.mop_asset WHERE tenant_id=? AND id=? FOR SHARE")) {
            query.setQueryTimeout(5);
            query.setObject(1, scope.tenantId());
            query.setObject(2, scope.assetId());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw missing();
                }
            }
        }
    }

    private static Task task(ResultSet row) throws SQLException {
        Scope scope = new Scope(row.getObject("tenant_id", UUID.class), row.getObject("asset_id", UUID.class),
                row.getObject("run_id", UUID.class), row.getInt("attempt_number"), row.getObject("version_id", UUID.class));
        return new Task(scope, row.getObject("submission_id", UUID.class), row.getLong("run_version"),
                row.getObject("recorded_at", OffsetDateTime.class).toInstant(), Phase.valueOf(row.getString("phase")),
                row.getLong("event_version"), row.getObject("dispatch_id", UUID.class),
                row.getString("dispatch_owner"), row.getLong("dispatch_count"));
    }

    private static boolean validInstant(Instant instant) {
        return instant != null && instant.getNano() % 1_000_000 == 0
                && !instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                && !instant.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"));
    }

    private static DomainException invalid() {
        return new DomainException(400, "INVALID_BACKUP_TASK_QUERY", "备份任务查询范围、游标或条数无效");
    }

    private static DomainException missing() {
        return new DomainException(404, "BACKUP_TASK_NOT_FOUND", "当前范围没有备份任务");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "BACKUP_TASK_DISCOVERY_UNAVAILABLE", "无法核对持久备份任务；不能据此执行或重试");
    }
}
