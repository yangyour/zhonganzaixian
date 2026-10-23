package cn.mop.worker.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.domain.backup.BackupRunState;
import cn.mop.persistence.PostgresBackupArchiveRepository;
import cn.mop.persistence.PostgresBackupRunRepository.LeaseClaim;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Task;
import cn.mop.persistence.PostgresBackupVersionRepository.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary synthetic signing keys and files only. These tests are not real collection, KMS, OSS or database evidence. */
class SignedBackupCollectionProofsTest {
    @TempDir
    Path directory;
    final Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    final LeaseClaim lease = new LeaseClaim(UUID.randomUUID(), "synthetic-lease", 7);
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    final ObjectMapper mapper = new ObjectMapper();
    KeyPair key;
    Path publicKey;
    Task task;

    @BeforeEach
    void setup() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKey = directory.resolve("collector-public.der");
        Files.write(publicKey, key.getPublic().getEncoded());
        task = new Task(scope, UUID.randomUUID(), 1, now, PostgresBackupArchiveRepository.Phase.STAGED,
                0, null, null, 0, lease);
    }

    @Test
    void signedOriginalIsReloadableAcrossRestartAndVerifiedInsideCompletionBoundary() throws Exception {
        sign(payload(), key);
        var first = verifier();
        String reference = first.archiveReference(task);
        assertEquals(reference, verifier().registrationReferences(task).executionReference());
        var prior = BackupRunState.queued(scope.tenantId(), scope.assetId(), now.minusSeconds(30)).start(0, now.minusSeconds(20));
        first.completionVerifier().verify(null, new Access(scope.tenantId(), UUID.randomUUID(), 1), scope, prior, lease, reference);
        assertThrows(DomainException.class, () -> first.completionVerifier().verify(null,
                new Access(scope.tenantId(), UUID.randomUUID(), 1), scope, prior,
                new LeaseClaim(UUID.randomUUID(), lease.owner(), lease.fencingToken()), reference));
    }

    @Test
    void unsignedWrongKeyAndTamperedBytesAreNeverProof() throws Exception {
        sign(payload(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertThrows(DomainException.class, () -> verifier().archiveReference(task));
        sign(payload(), key);
        var document = mapper.readTree(file().toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("signature", Base64.getEncoder().encodeToString(new byte[64]));
        mapper.writeValue(file().toFile(), document);
        assertThrows(DomainException.class, () -> verifier().archiveReference(task));
    }

    @Test
    void signedWrongScopeLeaseRunAndSourceAreRejected() throws Exception {
        for (var change : Map.<String, Object>of("tenant_id", UUID.randomUUID().toString(), "run_version", 2,
                "fencing_token", 9, "source_kind", "SYNTHETIC", "collector_binding", "backup-collector:other").entrySet()) {
            var payload = payload();
            payload.put(change.getKey(), change.getValue());
            sign(payload, key);
            assertThrows(DomainException.class, () -> verifier().archiveReference(task));
        }
    }

    @Test
    void expiredFutureAndInvalidDigestAreRejected() throws Exception {
        for (var change : Map.<String, Object>of("expires_at", now.toString(), "collected_at", now.plusSeconds(1).toString(),
                "envelope_sha256", "invalid", "envelope_bytes", 0).entrySet()) {
            var payload = payload();
            payload.put(change.getKey(), change.getValue());
            sign(payload, key);
            assertThrows(DomainException.class, () -> verifier().archiveReference(task));
        }
    }

    @Test
    void cacheNeverAuthorizesUnknownReferenceAndNoDispatchCertificationIsForbidden() throws Exception {
        sign(payload(), key);
        var proof = verifier();
        proof.archiveReference(task);
        var prior = BackupRunState.queued(scope.tenantId(), scope.assetId(), now.minusSeconds(30)).start(0, now.minusSeconds(20));
        assertThrows(DomainException.class, () -> proof.completionVerifier().verify(null,
                new Access(scope.tenantId(), UUID.randomUUID(), 1), scope, prior, lease, "sha256:" + "0".repeat(64)));
        assertEquals(403, assertThrows(DomainException.class, () -> proof.archiveVerifier(null).verify(null, null, null,
                PostgresBackupArchiveRepository.Action.CONFIRM_NO_DISPATCH, "sha256:" + "0".repeat(64))).status);
    }

    @Test
    void missingOversizedOrUnknownFieldsFailWithoutDetails() throws Exception {
        assertThrows(DomainException.class, () -> verifier().archiveReference(task));
        Files.writeString(file(), " ".repeat(32769));
        assertThrows(DomainException.class, () -> verifier().archiveReference(task));
        var payload = payload();
        payload.put("password", "SYNTHETIC_PRIVATE_DETAIL");
        sign(payload, key);
        var failure = assertThrows(DomainException.class, () -> verifier().archiveReference(task));
        assertFalse(failure.getMessage().contains("SYNTHETIC_PRIVATE_DETAIL"));
        assertNull(failure.getCause());
    }

    private SignedBackupCollectionProofs verifier() {
        return new SignedBackupCollectionProofs(directory, publicKey, "backup-collector:synthetic-unit",
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private HashMap<String, Object> payload() {
        var value = new HashMap<String, Object>();
        value.put("schema_version", 1);
        value.put("source_kind", "DEVICE_COLLECTION");
        value.put("collector_binding", "backup-collector:synthetic-unit");
        value.put("tenant_id", scope.tenantId().toString());
        value.put("asset_id", scope.assetId().toString());
        value.put("run_id", scope.runId().toString());
        value.put("attempt_number", 1);
        value.put("version_id", scope.versionId().toString());
        value.put("run_version", 1);
        value.put("envelope_sha256", "a".repeat(64));
        value.put("envelope_bytes", 300);
        value.put("lease_request_id", lease.requestId().toString());
        value.put("lease_owner", lease.owner());
        value.put("fencing_token", lease.fencingToken());
        value.put("collected_at", now.minusSeconds(10).toString());
        value.put("expires_at", now.plusSeconds(60).toString());
        return value;
    }

    private Path file() {
        return directory.resolve(scope.versionId() + ".json");
    }

    private void sign(Map<String, Object> data, KeyPair signer) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(data);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(payload);
        mapper.writeValue(file().toFile(), Map.of("payload", Base64.getEncoder().encodeToString(payload),
                "signature", Base64.getEncoder().encodeToString(signature.sign())));
    }
}
