package cn.mop.worker.backup;

import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupArchiveRepository.Phase;
import cn.mop.persistence.PostgresBackupRunRepository.LeaseClaim;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Task;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Frozen synthetic compatibility vector: a future deployment must not recreate durable commands with new IDs. */
class BackupTaskCommandIdentityTest {
    @Test
    void v1IdentitiesRemainCompatibleAcrossWorkerUpgrades() {
        var access = new Access(id(1), id(2), 7);
        var scope = new Scope(id(1), id(3), id(4), 1, id(5));
        var lease = new LeaseClaim(id(7), "synthetic-lease", 1);
        var at = Instant.parse("2026-10-23T00:00:00Z");
        var staged = new Task(scope, id(6), 1, at, Phase.STAGED, 0, null, null, 0, lease);
        String proof = "sha256:" + "a".repeat(64);
        var archive = BackupTaskCommands.archive("synthetic-vector", access, staged, proof);
        assertEquals(UUID.fromString("ea110b80-f480-368d-8ad0-1665afb23e49"), archive.dispatch().id());
        assertEquals(UUID.fromString("7d077a50-7478-3852-ac18-7756407ab75a"), archive.startCommand());
        assertEquals(UUID.fromString("b1c7c32e-d9a5-350d-aaf2-c4e249765a7e"), archive.unconfirmedCommand());
        assertEquals(UUID.fromString("82180a92-ba2b-3d77-9be1-256ed8b8ede3"), archive.verifyCommand());
        var verified = new Task(scope, id(6), 1, at, Phase.VERIFIED, 2,
                archive.dispatch().id(), archive.dispatch().owner(), 1, lease);
        var registration = BackupTaskCommands.registration("synthetic-vector", access, verified,
                new BackupTaskCommands.RegistrationProofs(proof, proof));
        assertEquals(UUID.fromString("61cc9f1f-e8c0-3842-9fc2-9e7b23571f4c"), registration.commandId());
    }

    private UUID id(int number) {
        return UUID.fromString("81000000-0000-4000-8000-" + String.format("%012d", number));
    }
}
