package cn.mop.persistence;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BackupGrantFileTest {
    @TempDir
    Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsExactIndependentOperationsAndReloadsRevocation() throws Exception {
        Path path = write(List.of(grant()));
        var provider = new BackupGrantFile(path);
        var first = provider.get();
        assertEquals(1, first.size());
        assertEquals(2, first.get(0).operations().size());
        assertTrue(first.get(0).operations().contains(BackupTaskGrant.Operation.VERSION_INVENTORY));
        write(List.of());
        assertTrue(provider.get().isEmpty());
        assertEquals(1, first.size());
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
    }

    @Test
    void absentDeletedAndPartialConfigurationFailWithoutCachedAllow() throws Exception {
        Path path = write(List.of(grant()));
        var provider = new BackupGrantFile(path);
        assertFalse(provider.get().isEmpty());
        Files.delete(path);
        assertEquals("BACKUP_ACCESS_NOT_CONFIGURED", assertThrows(DomainException.class, provider::get).code);
        Files.writeString(path, "{\"schema_version\":1,");
        assertEquals(503, assertThrows(DomainException.class, provider::get).status);
    }

    @Test
    void unknownFieldsWildcardsAndDuplicateIdentitiesAreRejected() throws Exception {
        var duplicate = grant();
        var cases = List.of(List.of(duplicate, duplicate), List.of(with(duplicate, "tenant_id", "*")),
                List.of(with(duplicate, "operations", List.of("VERSION_INVENTORY", "VERSION_INVENTORY"))),
                List.of(with(duplicate, "operations", List.of("admin"))),
                List.of(with(duplicate, "permission_version", "7")),
                List.of(with(duplicate, "password", "SYNTHETIC_PRIVATE_DETAIL")));
        for (var values : cases) {
            var failure = assertThrows(DomainException.class, new BackupGrantFile(write(values))::get);
            assertEquals(503, failure.status);
            assertFalse(failure.getMessage().contains("SYNTHETIC_PRIVATE_DETAIL"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void duplicateKeysTrailingDocumentAndOverflowSchemaAreRejected() throws Exception {
        Path path = directory.resolve("grants.json");
        for (String json : List.of("{\"schema_version\":1,\"grants\":[],\"grants\":[]}",
                "{\"schema_version\":1,\"grants\":[]} {}", "{\"schema_version\":4294967297,\"grants\":[]}")) {
            Files.writeString(path, json);
            assertThrows(DomainException.class, new BackupGrantFile(path)::get);
        }
    }

    @Test
    void boundedInputAndAbsolutePathAreRequired() throws Exception {
        assertThrows(DomainException.class, () -> new BackupGrantFile(Path.of("relative.json")));
        Path path = directory.resolve("grants.json");
        Files.writeString(path, " ".repeat(262145));
        assertThrows(DomainException.class, new BackupGrantFile(path)::get);
    }

    private Path write(List<? extends Map<String, ?>> grants) throws Exception {
        Path path = directory.resolve("grants.json");
        mapper.writeValue(path.toFile(), Map.of("schema_version", 1, "grants", grants));
        return path;
    }

    private Map<String, Object> grant() {
        return Map.of("binding_reference", "backup-task-binding:unit-test", "tenant_id", UUID.randomUUID().toString(),
                "asset_id", UUID.randomUUID().toString(), "actor_id", UUID.randomUUID().toString(),
                "permission_version", 7, "expires_at", Instant.now().plusSeconds(60).toString(),
                "operations", List.of("VERSION_INVENTORY", "VERSION_READ"));
    }

    private Map<String, Object> with(Map<String, Object> original, String key, Object value) {
        var changed = new java.util.HashMap<>(original);
        changed.put(key, value);
        return changed;
    }
}
