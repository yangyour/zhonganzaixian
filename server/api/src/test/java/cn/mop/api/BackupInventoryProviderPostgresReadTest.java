package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresBackupVersionRepository.Access;
import cn.mop.persistence.PostgresBackupVersionRepository.InventoryScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual configured provider and designated PostgreSQL. Reads existing data only; no synthetic tenant or version writes. */
@EnabledIfEnvironmentVariable(named = "MOP_BACKUP_DISCOVERY_READ_ONLY", matches = "true")
class BackupInventoryProviderPostgresReadTest {
    @TempDir
    Path directory;

    @Test
    void configuredProviderQueriesAndHonorsFileRevocationAndCurrentDatabaseIdentity() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("MOP_BACKUP_READ_JDBC"));
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("MOP_BACKUP_READ_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(5000);
        config.addDataSourceProperty("socketTimeout", "15");
        try (var source = new HikariDataSource(config)) {
            Access access;
            InventoryScope scope;
            try (var connection = source.getConnection();
                    var query = connection.prepareStatement("""
                            SELECT m.tenant_id,m.user_id,m.permission_version,a.id
                            FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
                            JOIN public.mop_asset a ON a.tenant_id=m.tenant_id
                            WHERE m.active AND u.active AND m.role_code IN ('padmin','msp')
                            ORDER BY m.tenant_id,m.user_id,a.id LIMIT 1
                            """); var rows = query.executeQuery()) {
                assertTrue(rows.next());
                access = new Access(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getLong(3));
                scope = new InventoryScope(access.tenantId(), rows.getObject(4, UUID.class));
                System.out.println("BACKUP_INVENTORY_PROVIDER postgres=" + connection.getMetaData().getDatabaseProductVersion());
            }
            Path file = directory.resolve("grants.json");
            var mapper = new ObjectMapper();
            var grant = Map.of("binding_reference", "backup-task-binding:read-verification", "tenant_id", access.tenantId().toString(),
                    "asset_id", scope.assetId().toString(), "actor_id", access.actorId().toString(),
                    "permission_version", access.permissionVersion(), "expires_at", Instant.now().plusSeconds(120).toString(),
                    "operations", List.of("VERSION_INVENTORY", "VERSION_READ", "VERSION_DOWNLOAD"));
            mapper.writeValue(file.toFile(), Map.of("schema_version", 1, "grants", List.of(grant)));
            var reader = BackupVersionInventoryConfiguration.configured(source, Map.of(
                    "MOP_BACKUP_ACCESS_GRANTS_FILE", file.toString(),
                    "MOP_BACKUP_VERSION_INVENTORY_BINDING", "backup-task-binding:read-verification")::get);
            var page = reader.inventory(access, scope, null, 5);
            assertTrue(page.items().size() <= 5);
            assertTrue(page.items().stream().noneMatch(item -> item.downloadAuthorized()));
            var authority = new cn.mop.persistence.PostgresBackupTaskAuthorization("backup-task-binding:read-verification",
                    new cn.mop.persistence.BackupGrantFile(file), java.time.Clock.systemUTC());
            var versions = cn.mop.persistence.PostgresBackupVersionRepository.readOnly(source, authority.versionAuthorizer());
            cn.mop.persistence.PostgresBackupVersionRepository.InventoryAuthorizer download = (connection, member, target) ->
                    authority.require(connection, new cn.mop.persistence.PostgresBackupArchiveRepository.Access(
                            member.tenantId(), member.actorId(), member.permissionVersion()), target.tenantId(), target.assetId(),
                            cn.mop.persistence.BackupTaskGrant.Operation.VERSION_DOWNLOAD);
            assertTrue(versions.findVersion(access, scope, UUID.randomUUID(), download).isEmpty());
            assertEquals(404, assertThrows(DomainException.class, () -> versions.findVersion(access,
                    new InventoryScope(access.tenantId(), UUID.randomUUID()), UUID.randomUUID(), download)).status);
            var foreign = new InventoryScope(UUID.randomUUID(), scope.assetId());
            assertEquals(404, assertThrows(DomainException.class, () -> reader.inventory(access, foreign, null, 5)).status);
            var onlyVersionRead = new java.util.HashMap<>(grant);
            onlyVersionRead.put("operations", List.of("VERSION_READ"));
            mapper.writeValue(file.toFile(), Map.of("schema_version", 1, "grants", List.of(onlyVersionRead)));
            assertEquals(403, assertThrows(DomainException.class, () -> reader.inventory(access, scope, null, 5)).status);
            var stale = new java.util.HashMap<>(grant);
            stale.put("permission_version", access.permissionVersion() + 1);
            mapper.writeValue(file.toFile(), Map.of("schema_version", 1, "grants", List.of(stale)));
            var staleAccess = new Access(access.tenantId(), access.actorId(), access.permissionVersion() + 1);
            assertEquals(403, assertThrows(DomainException.class, () -> reader.inventory(staleAccess, scope, null, 5)).status);
            mapper.writeValue(file.toFile(), Map.of("schema_version", 1, "grants", List.of()));
            assertEquals(403, assertThrows(DomainException.class, () -> reader.inventory(access, scope, null, 5)).status);
            Files.delete(file);
            assertEquals("BACKUP_VERSION_INVENTORY_NOT_CONFIGURED",
                    assertThrows(DomainException.class, () -> reader.inventory(access, scope, null, 5)).code);
        }
    }
}
