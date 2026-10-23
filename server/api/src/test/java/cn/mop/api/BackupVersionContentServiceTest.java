package cn.mop.api;

import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupRunRepository.LeaseClaim;
import cn.mop.persistence.PostgresBackupVersionRepository.Candidate;
import cn.mop.persistence.PostgresBackupVersionRepository.InventoryScope;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real JCE and diff code with synthetic metadata/storage only; never a real backup or KMS acceptance claim. */
class BackupVersionContentServiceTest {
    final UUID tenant = UUID.randomUUID(), asset = UUID.randomUUID(), actor = UUID.randomUUID();
    final Access access = new Access(tenant, actor, 7);
    final InventoryScope scope = new InventoryScope(tenant, asset);
    final Fixture fixture = new Fixture();
    final BackupVersionContentService service = new BackupVersionContentService(a -> fixture);

    @Test
    void envelopeReadDoesNotRequestKmsAndContentIsDisposedAfterWriting() throws Exception {
        UUID version = fixture.add("SYNTHETIC configuration");
        try (var content = service.download(access, scope, version, false, "trace", () -> false)) {
            var output = new ByteArrayOutputStream();
            content.writeTo(output);
            assertArrayEquals(fixture.envelopes.get(version).encoded(), output.toByteArray());
            assertTrue(content.filename().endsWith(".envelope"));
            assertFalse(content.toString().contains("configuration"));
            assertEquals(0, fixture.decrypts);
            assertEquals("ENVELOPE_READ_AUTHORIZED", fixture.auditAction);
        }
        assertTrue(allZero(fixture.readBytes));
        assertTrue(fixture.closed);
    }

    @Test
    void plaintextRequiresIndependentGrantAndAuthenticatesBeforeAudit() throws Exception {
        UUID version = fixture.add("SYNTHETIC private configuration");
        fixture.denied.add(Operation.VERSION_DECRYPT);
        assertEquals(403, assertThrows(DomainException.class,
                () -> service.download(access, scope, version, true, "trace", () -> false)).status);
        assertEquals(0, fixture.opens);
        fixture.denied.clear();
        try (var content = service.download(access, scope, version, true, "trace", () -> false)) {
            var bytes = new ByteArrayOutputStream();
            content.writeTo(bytes);
            assertEquals("SYNTHETIC private configuration", bytes.toString(StandardCharsets.UTF_8));
        }
        assertEquals("PLAINTEXT_READ_AUTHORIZED", fixture.auditAction);
        assertTrue(allZero(fixture.plaintext));
    }

    @Test
    void listOrReadPermissionAloneCannotDownload() {
        UUID version = fixture.add("SYNTHETIC");
        fixture.denied.add(Operation.VERSION_DOWNLOAD);
        assertEquals(403, assertThrows(DomainException.class,
                () -> service.download(access, scope, version, false, "trace", () -> false)).status);
        assertEquals(0, fixture.opens);
    }

    @Test
    void compareRequiresBothVersionReadsButNeverReturnsTextOrNeedsDownloadGrant() {
        UUID before = fixture.add("SYNTHETIC old value\n"), after = fixture.add("SYNTHETIC new value\n");
        fixture.denied.add(Operation.VERSION_DOWNLOAD);
        var summary = service.compare(access, scope, before, after, "trace", () -> false);
        assertEquals(Boolean.TRUE, summary.changed());
        assertFalse(summary.toString().contains("old value"));
        assertFalse(summary.toString().contains("new value"));
        assertEquals("REDACTED_DIFF_AUTHORIZED", fixture.auditAction);
        assertTrue(allZero(fixture.plaintext));
    }

    @Test
    void wrongScopeOrMissingRegistrationCannotAccessStorage() {
        UUID version = fixture.add("SYNTHETIC");
        assertEquals(404, assertThrows(DomainException.class,
                () -> service.download(access, new InventoryScope(UUID.randomUUID(), asset), version, false, "trace", () -> false)).status);
        assertEquals(404, assertThrows(DomainException.class,
                () -> service.download(access, scope, UUID.randomUUID(), false, "trace", () -> false)).status);
        fixture.wrongAsset = true;
        assertEquals(404, assertThrows(DomainException.class,
                () -> service.download(access, scope, version, false, "trace", () -> false)).status);
        assertEquals(0, fixture.opens);
    }

    @Test
    void corruptCiphertextIs503AndNeverBecomesDownloadOrAuditSuccess() {
        UUID version = fixture.add("SYNTHETIC");
        fixture.corrupt = true;
        assertEquals(503, assertThrows(DomainException.class,
                () -> service.download(access, scope, version, true, "trace", () -> false)).status);
        assertNull(fixture.auditAction);
        assertTrue(allZero(fixture.readBytes));
        assertEquals(0, fixture.decrypts);
    }

    @Test
    void revokedReturnAndAuditFailureSuppressAndWipePlaintext() {
        for (boolean auditFailure : new boolean[]{false, true}) {
            UUID version = fixture.add("SYNTHETIC secret");
            fixture.auditFails = auditFailure;
            fixture.revokeAfterDecrypt = !auditFailure;
            fixture.denied.clear();
            assertThrows(DomainException.class, () -> service.download(access, scope, version, true, "trace", () -> false));
            assertTrue(allZero(fixture.plaintext));
        }
    }

    @Test
    void cancelledBeforeOrAfterObjectReadReturnsNoContent() {
        UUID version = fixture.add("SYNTHETIC");
        assertEquals(409, assertThrows(DomainException.class,
                () -> service.download(access, scope, version, true, "trace", () -> true)).status);
        assertEquals(0, fixture.opens);
        var stopped = new AtomicBoolean();
        fixture.onRead = () -> stopped.set(true);
        assertEquals(409, assertThrows(DomainException.class,
                () -> service.download(access, scope, version, true, "trace", stopped::get)).status);
        assertTrue(allZero(fixture.readBytes));
        assertEquals(0, fixture.decrypts);
    }

    @Test
    void oversizedDiffDoesNotTruncateOrDeclareUnchanged() {
        UUID before = fixture.add("x".repeat(131073)), after = fixture.add("SYNTHETIC");
        assertEquals(413, assertThrows(DomainException.class,
                () -> service.compare(access, scope, before, after, "trace", () -> false)).status);
        assertNull(fixture.auditAction);
        assertTrue(allZero(fixture.plaintext));
    }

    @Test
    void unsupportedTextEncodingCannotBeReportedAsIdentical() {
        UUID before = fixture.add("SYNTHETIC"), after = fixture.add("SYNTHETIC");
        var original = fixture.envelopes.get(before);
        fixture.envelopes.put(before, fixture.crypto.seal(new BackupEnvelopeEncryption.Access(tenant, actor, 7),
                original.scope(), new byte[]{(byte) 0xff}));
        var failure = assertThrows(DomainException.class, () -> service.compare(access, scope, before, after, "trace", () -> false));
        assertEquals(422, failure.status);
        assertEquals("BACKUP_DIFF_TEXT_UNSUPPORTED", failure.code);
        assertNull(fixture.auditAction);
    }

    private static boolean allZero(byte[] value) {
        if (value == null) return false;
        for (byte element : value) if (element != 0) return false;
        return true;
    }

    final class Fixture implements BackupVersionContentService.Boundary, BackupVersionContentService.Resources {
        final Map<UUID, BackupEnvelopeEncryption.Envelope> envelopes = new HashMap<>();
        final Set<Operation> denied = new HashSet<>();
        final BackupEnvelopeEncryption crypto = new BackupEnvelopeEncryption(new BackupEnvelopeEncryption.DataKeyProvider() {
            public BackupEnvelopeEncryption.KeyMaterial create(BackupEnvelopeEncryption.Access a, BackupEnvelopeEncryption.Scope s) {
                return material();
            }
            public BackupEnvelopeEncryption.KeyMaterial unwrap(BackupEnvelopeEncryption.Access a, BackupEnvelopeEncryption.Scope s,
                    String reference, byte[] wrapped) { return material(); }
            private BackupEnvelopeEncryption.KeyMaterial material() {
                return new BackupEnvelopeEncryption.KeyMaterial("kms-key-ref:synthetic", new byte[]{1}, new byte[32]);
            }
        }, (a, s, op) -> true, BackupEnvelopeEncryption.MAX_PLAINTEXT_BYTES);
        int opens, decrypts;
        boolean closed, corrupt, wrongAsset, auditFails, revokeAfterDecrypt;
        byte[] readBytes, plaintext;
        String auditAction;
        Runnable onRead = () -> {};

        UUID add(String text) {
            UUID version = UUID.randomUUID();
            var value = new BackupEnvelopeEncryption.Scope(tenant, asset, UUID.randomUUID(), 1, version);
            envelopes.put(version, crypto.seal(new BackupEnvelopeEncryption.Access(tenant, actor, 7), value,
                    text.getBytes(StandardCharsets.UTF_8)));
            return version;
        }

        public Candidate metadata(Access a, InventoryScope s, UUID version, Operation operation) {
            require(a, s.assetId(), operation);
            var envelope = envelopes.get(version);
            if (envelope == null) throw new DomainException(404, "NOT_FOUND", "synthetic missing registration");
            var value = envelope.scope();
            return new Candidate(new Scope(tenant, wrongAsset ? UUID.randomUUID() : asset, value.runId(), 1, version),
                    1, 2, envelope.sha256(), envelope.encoded().length, envelope.keyReference(), "sha256:" + "a".repeat(64),
                    Instant.parse("2026-10-23T00:00:00Z"), new LeaseClaim(UUID.randomUUID(), "synthetic", 1));
        }
        public void require(Access a, UUID id, Operation operation) {
            if (denied.contains(operation)) throw new DomainException(403, "DENIED", "synthetic denied");
        }
        public BackupVersionContentService.Resources open(boolean decrypt) { opens++; return this; }
        public byte[] read(Candidate original) {
            readBytes = envelopes.get(original.scope().versionId()).encoded();
            if (corrupt) readBytes[readBytes.length - 1] ^= 1;
            onRead.run();
            return readBytes;
        }
        public byte[] decrypt(Access a, Candidate original, byte[] encoded) {
            decrypts++;
            var value = envelopes.get(original.scope().versionId());
            plaintext = crypto.open(new BackupEnvelopeEncryption.Access(tenant, actor, 7), value.scope(),
                    crypto.decode(encoded, original.encryptedSha256(), value.scope()));
            if (revokeAfterDecrypt) denied.add(Operation.VERSION_DOWNLOAD);
            return plaintext;
        }
        public void audit(Access a, UUID id, UUID version, String action, String trace) {
            if (auditFails) throw new DomainException(503, "AUDIT_UNAVAILABLE", "synthetic audit failure");
            auditAction = action;
        }
        public void close() { closed = true; }
    }
}
