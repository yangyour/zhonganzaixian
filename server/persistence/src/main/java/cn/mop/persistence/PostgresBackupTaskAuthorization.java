package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Combines a fresh, explicit deployment grant with the current database identity.
 * The grant supplier must read controlled current configuration; failures deny access.
 * Membership/user share locks live in the caller's transaction, including final rechecks.
 */
public final class PostgresBackupTaskAuthorization {
    private final String bindingReference;
    private final Supplier<List<BackupTaskGrant>> grants;
    private final Clock clock;

    public PostgresBackupTaskAuthorization(String bindingReference,
            Supplier<List<BackupTaskGrant>> grants, Clock clock) {
        if (bindingReference == null || !bindingReference.matches("backup-task-binding:[A-Za-z0-9._-]{1,128}")) {
            throw denied();
        }
        this.bindingReference = bindingReference;
        this.grants = Objects.requireNonNull(grants);
        this.clock = Objects.requireNonNull(clock);
    }

    public String bindingReference() {
        return bindingReference;
    }

    /** Fresh independent check for an external-I/O boundary; it never keeps a SQL transaction open during provider calls. */
    public void requireCurrent(javax.sql.DataSource source, Access access, UUID asset, Operation operation) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout='5s'");
                statement.execute("SET LOCAL statement_timeout='10s'");
            }
            try {
                require(connection, access, access.tenantId(), asset, operation);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // A failed authorization never becomes permission to continue external IO.
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    public void require(Connection connection, Access access, UUID tenant, UUID asset,
            Operation operation) throws SQLException {
        if (access == null || tenant == null || asset == null || operation == null) {
            throw denied();
        }
        if (!access.tenantId().equals(tenant)) {
            throw missing();
        }
        if (connection.getAutoCommit()) {
            throw unavailable();
        }
        checkGrant(access, asset, operation);
        try (var query = connection.prepareStatement("""
                SELECT m.permission_version
                FROM public.mop_membership m
                JOIN public.mop_user u ON u.id=m.user_id
                WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active
                  AND m.role_code IN ('padmin','msp')
                FOR SHARE OF m,u
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, tenant);
            query.setObject(2, access.actorId());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != access.permissionVersion()) {
                    throw denied();
                }
            }
        }
        checkGrant(access, asset, operation);
    }

    /** V48 staging and no-dispatch certification are deliberately outside this worker's authority. */
    public PostgresBackupArchiveRepository.Authorizer archiveAuthorizer() {
        return (connection, access, scope, action) -> {
            Operation operation = switch (action) {
                case READ -> Operation.ARCHIVE_READ;
                case START_STORE -> Operation.ARCHIVE_START;
                case MARK_UNCONFIRMED, VERIFY_OBJECT -> Operation.ARCHIVE_RECONCILE;
                default -> throw denied();
            };
            require(connection, access, scope.tenantId(), scope.assetId(), operation);
        };
    }

    public PostgresBackupVersionRepository.Authorizer versionAuthorizer() {
        return (connection, access, scope, operation) -> require(connection,
                new Access(access.tenantId(), access.actorId(), access.permissionVersion()),
                scope.tenantId(), scope.assetId(), operation == PostgresBackupVersionRepository.Operation.READ
                        ? Operation.VERSION_READ : Operation.VERSION_REGISTER);
    }

    /** Enumeration is independent from a single version read, even when the result is empty. */
    public PostgresBackupVersionRepository.InventoryAuthorizer inventoryAuthorizer() {
        return (connection, access, scope) -> require(connection,
                new Access(access.tenantId(), access.actorId(), access.permissionVersion()),
                scope.tenantId(), scope.assetId(), Operation.VERSION_INVENTORY);
    }

    private void checkGrant(Access access, UUID asset, Operation operation) {
        try {
            var now = clock.instant();
            List<BackupTaskGrant> current = List.copyOf(grants.get());
            boolean allowed = current.stream().anyMatch(grant -> bindingReference.equals(grant.bindingReference())
                    && access.tenantId().equals(grant.tenantId()) && asset.equals(grant.assetId())
                    && access.actorId().equals(grant.actorId())
                    && access.permissionVersion() == grant.permissionVersion()
                    && now.isBefore(grant.expiresAt()) && grant.operations().contains(operation));
            if (!allowed) {
                throw denied();
            }
        } catch (DomainException failure) {
            if (failure.status == 503) {
                throw unavailable();
            }
            throw denied();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static DomainException denied() {
        return new DomainException(403, "BACKUP_TASK_ACCESS_DENIED", "备份任务未获当前独立配置授权或身份已失效");
    }

    private static DomainException missing() {
        return new DomainException(404, "BACKUP_TASK_NOT_FOUND", "当前范围内没有备份任务");
    }

    private static DomainException unavailable() {
        return new DomainException(503, "BACKUP_TASK_AUTHORIZATION_UNAVAILABLE", "备份任务授权当前无法核对");
    }
}
