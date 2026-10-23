package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresBackupVersionRepository.Access;
import cn.mop.persistence.PostgresBackupVersionRepository.InventoryScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupVersionInventoryConfigurationTest {
    @TempDir
    Path directory;
    final UUID tenant = UUID.randomUUID();
    final Access access = new Access(tenant, UUID.randomUUID(), 7);
    final InventoryScope scope = new InventoryScope(tenant, UUID.randomUUID());
    final DataSource source = mock(DataSource.class);

    @Test
    void missingSettingsAndMissingPrivateFileReturn503BeforeDatabase() {
        for (Map<String, String> settings : List.of(Map.<String, String>of(),
                Map.of("MOP_BACKUP_ACCESS_GRANTS_FILE", directory.resolve("missing.json").toString(),
                        "MOP_BACKUP_VERSION_INVENTORY_BINDING", "backup-task-binding:unit-test"))) {
            var query = BackupVersionInventoryConfiguration.configured(source, settings::get);
            var failure = assertThrows(DomainException.class, () -> query.inventory(access, scope, null, 15));
            assertEquals(503, failure.status);
            assertEquals("BACKUP_VERSION_INVENTORY_NOT_CONFIGURED", failure.code);
        }
        verifyNoInteractions(source);
    }

    @Test
    void invalidBindingAndMalformedConfigurationDoNotLeakPathOrAcquireConnection() throws Exception {
        Path file = directory.resolve("private-secret-name.json");
        new ObjectMapper().writeValue(file.toFile(), Map.of("schema_version", 1, "grants", List.of()));
        var query = BackupVersionInventoryConfiguration.configured(source, Map.of(
                "MOP_BACKUP_ACCESS_GRANTS_FILE", file.toString(),
                "MOP_BACKUP_VERSION_INVENTORY_BINDING", "https://not-an-authority.invalid")::get);
        var failure = assertThrows(DomainException.class, () -> query.inventory(access, scope, null, 15));
        assertEquals(503, failure.status);
        assertFalse(failure.getMessage().contains("private-secret-name"));
        assertNull(failure.getCause());
        verifyNoInteractions(source);
    }
}
