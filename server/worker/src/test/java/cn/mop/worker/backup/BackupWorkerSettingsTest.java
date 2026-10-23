package cn.mop.worker.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BackupWorkerSettingsTest {
    @TempDir
    Path directory;

    @Test
    void explicitEnablementIdentityAllowlistAndTechnicalBoundsAreRequired() throws Exception {
        var settings = valid();
        var parsed = BackupWorkerSettings.from(settings::get);
        assertEquals(1, parsed.schedule().targets().size());
        assertFalse(parsed.toString().contains("synthetic-password"));
        for (var value : Map.of("MOP_BACKUP_WORKER_ENABLED", "false", "SPRING_DATASOURCE_USERNAME", "postgres",
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://example.invalid/mop_test",
                "MOP_BACKUP_WORKER_TENANTS", UUID.randomUUID().toString(),
                "MOP_BACKUP_WORKER_ACTOR", UUID.randomUUID().toString(),
                "MOP_BACKUP_WORKER_PAGE_SIZE", "51", "MOP_BACKUP_WORKER_POLL_MILLIS", "0").entrySet()) {
            var changed = new HashMap<>(settings);
            changed.put(value.getKey(), value.getValue());
            assertThrows(RuntimeException.class, () -> BackupWorkerSettings.from(changed::get));
        }
    }

    @Test
    void noDefaultScheduleOrCredentialCanBeInferred() throws Exception {
        var original = valid();
        for (String key : original.keySet()) {
            var changed = new HashMap<>(original);
            changed.remove(key);
            assertThrows(RuntimeException.class, () -> BackupWorkerSettings.from(changed::get), key);
        }
    }


    @Test void reconcileModeRequiresExplicitEnablementAndDoesNotRequireWriteGrants() throws Exception {
        var settings=valid();assertThrows(RuntimeException.class,()->BackupWorkerSettings.from(settings::get,BackupTaskScheduler.Mode.RECONCILE_ONLY));
        settings.put("MOP_BACKUP_RECONCILE_ONLY_ENABLED","true");
        var mapper=new ObjectMapper();var file=Path.of(settings.get("MOP_BACKUP_ACCESS_GRANTS_FILE"));var tree=mapper.readTree(file.toFile());
        var grant=(com.fasterxml.jackson.databind.node.ObjectNode)tree.get("grants").get(0);var ops=grant.putArray("operations");
        BackupWorkerSettings.requiredOperations(BackupTaskScheduler.Mode.RECONCILE_ONLY).forEach(operation->ops.add(operation.name()));mapper.writeValue(file.toFile(),tree);
        assertEquals(1,BackupWorkerSettings.from(settings::get,BackupTaskScheduler.Mode.RECONCILE_ONLY).schedule().targets().size());
        assertFalse(BackupWorkerSettings.requiredOperations(BackupTaskScheduler.Mode.RECONCILE_ONLY).contains(cn.mop.persistence.BackupTaskGrant.Operation.ARCHIVE_START));
        assertFalse(BackupWorkerSettings.requiredOperations(BackupTaskScheduler.Mode.RECONCILE_ONLY).contains(cn.mop.persistence.BackupTaskGrant.Operation.VERSION_REGISTER));
        assertThrows(RuntimeException.class,()->BackupWorkerSettings.from(settings::get));
        settings.put("MOP_BACKUP_RECONCILE_ONLY_ENABLED","false");assertThrows(RuntimeException.class,()->BackupWorkerSettings.from(settings::get,BackupTaskScheduler.Mode.RECONCILE_ONLY));
    }
    private HashMap<String, String> valid() throws Exception {
        String tenant = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
        Path file = directory.resolve("grants.json");
        new ObjectMapper().writeValue(file.toFile(), Map.of("schema_version", 1, "grants", List.of(Map.of(
                "binding_reference", "backup-task-binding:unit", "tenant_id", tenant, "asset_id", UUID.randomUUID().toString(),
                "actor_id", actor, "permission_version", 1, "expires_at", Instant.now().plusSeconds(60).toString(),
                "operations", BackupWorkerSettings.REQUIRED_OPERATIONS.stream().map(Enum::name).toList()))));
        var settings = new HashMap<String, String>();
        settings.put("SPRING_PROFILES_ACTIVE", "remote_test");
        settings.put("SPRING_DATASOURCE_USERNAME", "mop_test_runtime");
        settings.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:19214/mop_test");
        settings.put("SPRING_DATASOURCE_PASSWORD", "synthetic-password");
        settings.put("MOP_BACKUP_WORKER_ENABLED", "true");
        settings.put("MOP_BACKUP_TASK_BINDING", "unit");
        settings.put("MOP_BACKUP_WORKER_ACTOR", actor);
        settings.put("MOP_BACKUP_WORKER_TENANTS", tenant);
        settings.put("MOP_BACKUP_ACCESS_GRANTS_FILE", file.toString());
        settings.put("MOP_BACKUP_COLLECTION_PROOFS_DIRECTORY", directory.toString());
        settings.put("MOP_BACKUP_COLLECTOR_PUBLIC_KEY", directory.resolve("public.der").toString());
        settings.put("MOP_BACKUP_COLLECTOR_BINDING", "backup-collector:unit");
        settings.put("MOP_BACKUP_WORKER_PAGE_SIZE", "10");
        settings.put("MOP_BACKUP_WORKER_POLL_MILLIS", "1000");
        return settings;
    }
}
