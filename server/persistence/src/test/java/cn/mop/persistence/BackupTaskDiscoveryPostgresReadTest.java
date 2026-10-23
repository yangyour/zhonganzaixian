package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupTaskDiscovery.AssetScope;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Cursor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual designated PostgreSQL reads only. Never creates tenants, users, assets, jobs or proofs. */
@EnabledIfEnvironmentVariable(named = "MOP_BACKUP_DISCOVERY_READ_ONLY", matches = "true")
class BackupTaskDiscoveryPostgresReadTest {
    @Test
    void actualIdentityLocksAndBoundedQueryUseExistingDataWithoutBusinessWrites() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("MOP_BACKUP_READ_JDBC"));
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("MOP_BACKUP_READ_PASSWORD"));
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5000);
        config.addDataSourceProperty("socketTimeout", "15");
        try (var source = new HikariDataSource(config); var connection = source.getConnection()) {
            System.out.println("BACKUP_DISCOVERY_READ_CHECK postgres=" + connection.getMetaData().getDatabaseProductVersion());
            Access access;
            AssetScope scope;
            try (var query = connection.prepareStatement("""
                    SELECT m.tenant_id,m.user_id,m.permission_version,a.id
                    FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
                    JOIN public.mop_asset a ON a.tenant_id=m.tenant_id
                    WHERE m.active AND u.active AND m.role_code IN ('padmin','msp')
                    ORDER BY m.tenant_id,m.user_id,a.id LIMIT 1
                    """); var rows = query.executeQuery()) {
                assertTrue(rows.next(), "Existing active identity and asset required; never synthesize them");
                access = new Access(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getLong(3));
                scope = new AssetScope(access.tenantId(), rows.getObject(4, UUID.class));
            }
            // Release the initial read connection before the single-connection repository call.
            connection.close();
            String binding = "backup-task-binding:explicit-read-only-verification";
            var grant = new BackupTaskGrant(binding, scope.tenantId(), scope.assetId(), access.actorId(),
                    access.permissionVersion(), Instant.now().plusSeconds(60), Set.of(Operation.DISCOVER));
            var authorization = new PostgresBackupTaskAuthorization(binding, () -> List.of(grant), Clock.systemUTC());
            var discovery = new PostgresBackupTaskDiscovery(source, authorization);
            var first = discovery.discover(access, scope, null, 5);
            assertTrue(first.items().size() <= 5);
            assertTrue(first.items().stream().allMatch(task -> !task.executionAuthorized()));
            var empty = discovery.discover(access, scope,
                    new Cursor(scope, Instant.parse("9999-12-31T23:59:59.999Z"),
                            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")), 5);
            assertTrue(empty.items().isEmpty());
            assertNull(empty.nextAfter());
            var revoked = new Access(access.tenantId(), access.actorId(), access.permissionVersion() + 1);
            var staleGrant = new BackupTaskGrant(binding, scope.tenantId(), scope.assetId(), access.actorId(),
                    revoked.permissionVersion(), Instant.now().plusSeconds(60), Set.of(Operation.DISCOVER));
            var stale = new PostgresBackupTaskDiscovery(source,
                    new PostgresBackupTaskAuthorization(binding, () -> List.of(staleGrant), Clock.systemUTC()));
            assertEquals(403, assertThrows(DomainException.class,
                    () -> stale.discover(revoked, scope, null, 5)).status);
        }
    }
}
