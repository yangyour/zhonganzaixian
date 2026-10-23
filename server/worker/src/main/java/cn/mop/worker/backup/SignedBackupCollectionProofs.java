package cn.mop.worker.backup;

import cn.mop.adapters.backup.BackupEnvelopeArchive;
import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.PostgresBackupArchiveRepository;
import cn.mop.persistence.PostgresBackupRunRepository.LeaseClaim;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Task;
import cn.mop.persistence.PostgresBackupVersionRepository;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies durable collector-signed evidence. The trusted collector must establish actual device collection.
 * No unsigned boolean, in-memory test proof, caller URL or self-supplied verification key is accepted.
 * Files are read before consumer SQL transactions; the bounded cache holds signed bytes, never an allow flag.
 */
public final class SignedBackupCollectionProofs implements BackupTaskScheduler.ProofSource {
    private static final Set<String> FIELDS = Set.of("schema_version", "source_kind", "collector_binding", "tenant_id",
            "asset_id", "run_id", "attempt_number", "version_id", "run_version", "envelope_sha256", "envelope_bytes",
            "lease_request_id", "lease_owner", "fencing_token", "collected_at", "expires_at");
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path directory;
    private final PublicKey trustedKey;
    private final String collectorBinding;
    private final Clock clock;
    private final LinkedHashMap<UUID, Proof> prepared = new LinkedHashMap<>(32, .75f, true);

    private record Proof(Scope scope, long runVersion, String hash, long bytes, LeaseClaim lease,
            Instant collectedAt, Instant expiresAt, String reference, byte[] payload, byte[] signature) {}

    public SignedBackupCollectionProofs(Path directory, Path publicKey, String collectorBinding, Clock clock) {
        try {
            if (directory == null || !directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(directory) || publicKey == null || !publicKey.isAbsolute()
                    || collectorBinding == null || !collectorBinding.matches("backup-collector:[A-Za-z0-9._-]{1,128}")) {
                throw unavailable();
            }
            this.directory = directory.normalize();
            this.trustedKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(read(publicKey, 8192)));
            this.collectorBinding = collectorBinding;
            this.clock = java.util.Objects.requireNonNull(clock);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    @Override
    public String archiveReference(Task original) {
        Proof proof = load(original);
        return proof.reference();
    }

    @Override
    public BackupTaskCommands.RegistrationProofs registrationReferences(Task original) {
        Proof proof = load(original);
        return new BackupTaskCommands.RegistrationProofs(proof.reference(), proof.reference());
    }

    private synchronized Proof load(Task original) {
        try {
            var document = MAPPER.readTree(read(directory.resolve(original.scope().versionId() + ".json"), 32768));
            exact(document, Set.of("payload", "signature"));
            byte[] payload = Base64.getDecoder().decode(text(document, "payload"));
            byte[] signature = Base64.getDecoder().decode(text(document, "signature"));
            if (payload.length > 16384 || signature.length != 64) {
                throw unavailable();
            }
            authenticate(payload, signature);
            JsonNode value = MAPPER.readTree(payload);
            exact(value, FIELDS);
            if (number(value, "schema_version") != 1 || !"DEVICE_COLLECTION".equals(text(value, "source_kind"))
                    || !collectorBinding.equals(text(value, "collector_binding"))) {
                throw unavailable();
            }
            Scope scope = new Scope(uuid(value, "tenant_id"), uuid(value, "asset_id"), uuid(value, "run_id"),
                    Math.toIntExact(number(value, "attempt_number")), uuid(value, "version_id"));
            String hash = text(value, "envelope_sha256");
            long bytes = number(value, "envelope_bytes");
            if (!hash.matches("[0-9a-f]{64}") || bytes < 247 || bytes > BackupArchivePayload.MAX_ENVELOPE_BYTES) {
                throw unavailable();
            }
            var lease = new LeaseClaim(uuid(value, "lease_request_id"), text(value, "lease_owner"), number(value, "fencing_token"));
            String reference = "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
            var proof = new Proof(scope, number(value, "run_version"), hash, bytes, lease,
                    Instant.parse(text(value, "collected_at")), Instant.parse(text(value, "expires_at")), reference, payload, signature);
            valid(proof);
            if (!scope.equals(original.scope()) || proof.runVersion() != original.stagedRunVersion()
                    || !lease.equals(original.originalLease())) {
                throw unavailable();
            }
            prepared.put(scope.versionId(), proof);
            while (prepared.size() > 256) {
                prepared.remove(prepared.keySet().iterator().next());
            }
            return proof;
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    public PostgresBackupArchiveRepository.EnvelopeVerifier envelopeVerifier(BackupEnvelopeEncryption crypto) {
        return (access, payload) -> {
            var scope = cryptoScope(payload.scope());
            byte[] plaintext = null;
            try {
                plaintext = crypto.open(cryptoAccess(access), scope, crypto.decode(payload.bytes(), payload.sha256(), scope));
            } finally {
                if (plaintext != null) {
                    Arrays.fill(plaintext, (byte) 0);
                }
            }
        };
    }

    public PostgresBackupArchiveRepository.EvidenceVerifier archiveVerifier(BackupEnvelopeArchive archive) {
        return (access, source, prior, action, reference) -> {
            if (action != PostgresBackupArchiveRepository.Action.VERIFY_OBJECT) {
                throw new DomainException(403, "BACKUP_NO_DISPATCH_PROOF_NOT_SUPPORTED", "采集证明不能证明旧派发没有执行");
            }
            Proof proof = require(source.payload().scope(), reference);
            if (proof.runVersion() != source.runVersion() || !proof.hash().equals(source.payload().sha256())
                    || proof.bytes() != source.payload().size()) {
                throw unavailable();
            }
            archive.verifyExisting(cryptoAccess(access), cryptoScope(proof.scope()), proof.bytes(), proof.hash());
        };
    }

    public PostgresBackupVersionRepository.ObjectVerifier objectVerifier(BackupEnvelopeArchive archive) {
        return (access, original, reference) -> {
            Proof proof = require(original.scope(), reference);
            if (!reference.equals(original.archiveVerificationReference()) || proof.runVersion() != original.expectedRunVersion()
                    || !proof.hash().equals(original.encryptedSha256()) || proof.bytes() != original.encryptedBytes()
                    || !proof.lease().equals(original.lease()) || proof.collectedAt().isAfter(original.objectObservedAt())) {
                throw unavailable();
            }
            archive.verifyExisting(new BackupEnvelopeEncryption.Access(access.tenantId(), access.actorId(), access.permissionVersion()),
                    cryptoScope(original.scope()), original.encryptedBytes(), original.encryptedSha256());
        };
    }

    /** Signature and original-run checks only inside SQL. No file, key-service, storage or device IO here. */
    public PostgresBackupVersionRepository.CompletionPrerequisiteVerifier completionVerifier() {
        return (connection, access, scope, prior, lease, reference) -> {
            Proof proof = require(scope, reference);
            if (!scope.tenantId().equals(access.tenantId()) || proof.runVersion() != prior.version()
                    || !proof.lease().equals(lease) || prior.attempts().size() != scope.attemptNumber()
                    || proof.collectedAt().isBefore(prior.attempts().get(scope.attemptNumber() - 1).startedAt())) {
                throw unavailable();
            }
        };
    }

    private synchronized Proof require(Scope scope, String reference) {
        Proof proof = prepared.get(scope.versionId());
        if (proof == null || !proof.scope().equals(scope) || !proof.reference().equals(reference)) {
            throw unavailable();
        }
        valid(proof);
        return proof;
    }

    private void valid(Proof proof) {
        authenticate(proof.payload(), proof.signature());
        Instant now = clock.instant();
        if (proof.runVersion() < 1 || proof.runVersion() > 9007199254740991L
                || proof.collectedAt().getNano() % 1_000_000 != 0 || proof.expiresAt().getNano() % 1_000_000 != 0
                || proof.collectedAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || proof.collectedAt().isAfter(now) || !now.isBefore(proof.expiresAt())) {
            throw unavailable();
        }
    }

    private void authenticate(byte[] payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedKey);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw unavailable();
            }
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static byte[] read(Path path, int limit) throws java.io.IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable();
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw unavailable();
            }
            return bytes;
        }
    }

    private static void exact(JsonNode node, Set<String> fields) {
        var actual = new HashSet<String>();
        if (node == null || !node.isObject()) {
            throw unavailable();
        }
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw unavailable();
        }
    }

    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual()) {
            throw unavailable();
        }
        return node.path(key).textValue();
    }

    private static long number(JsonNode node, String key) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToLong()) {
            throw unavailable();
        }
        return node.path(key).longValue();
    }

    private static UUID uuid(JsonNode node, String key) {
        String raw = text(node, key);
        UUID value = UUID.fromString(raw);
        if (!value.toString().equals(raw)) {
            throw unavailable();
        }
        return value;
    }

    private static BackupEnvelopeEncryption.Scope cryptoScope(Scope scope) {
        return new BackupEnvelopeEncryption.Scope(scope.tenantId(), scope.assetId(), scope.runId(), scope.attemptNumber(), scope.versionId());
    }

    private static BackupEnvelopeEncryption.Access cryptoAccess(PostgresBackupArchiveRepository.Access access) {
        return new BackupEnvelopeEncryption.Access(access.tenantId(), access.actorId(), access.permissionVersion());
    }

    private static DomainException unavailable() {
        return new DomainException(503, "BACKUP_COLLECTION_PROOF_UNAVAILABLE", "受信任采集器证明、原运行租约或密文范围尚未核对");
    }
}
