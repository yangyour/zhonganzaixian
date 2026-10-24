package cn.mop.api;

import cn.mop.adapters.ClamdAttachmentScanner;
import cn.mop.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Candidate protocol/policy tests. No engine, tunnel, signature freshness or production approval is verified. */
class ClamdAttachmentScannerTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final byte[] BYTES = "SYNTHETIC_SCAN_BYTES".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERSION = "ClamAV 1.4.3/12345/SYNTHETIC_DATE".getBytes(StandardCharsets.US_ASCII);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private ClamdAttachmentScanner.Policy policy() {
        return new ClamdAttachmentScanner.Policy(19310, "1.4.3", "12345", NOW.plusSeconds(600), 120);
    }

    private String sha() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
    }

    @Test
    void missingEnablementNeverReadsScannerSettings() {
        assertTrue(ClamdAttachmentScanner.Policy.configured(key -> {
            assertEquals("MOP_ATTACHMENT_SCAN_ENABLED", key); return null;
        }).isEmpty());
        assertTrue(ClamdAttachmentScanner.Policy.configured(Map.of("MOP_ATTACHMENT_SCAN_ENABLED", "false")::get).isEmpty());
    }

    @Test
    void trueEnablementRequiresEveryPolicyValueWithoutDefaults() {
        assertThrows(IllegalStateException.class,
            () -> ClamdAttachmentScanner.Policy.configured(Map.of("MOP_ATTACHMENT_SCAN_ENABLED", "true")::get));
        assertThrows(IllegalStateException.class,
            () -> ClamdAttachmentScanner.Policy.configured(Map.of("MOP_ATTACHMENT_SCAN_ENABLED", "yes")::get));
    }

    @Test
    void cleanResultBindsBytesRequestPolicyAndFiniteLifetime() throws Exception {
        List<byte[]> replies = new ArrayList<>(List.of(VERSION, "stream: OK".getBytes(StandardCharsets.US_ASCII), VERSION));
        var scanner = new ClamdAttachmentScanner(policy(), CLOCK, (command, content, deadline) -> replies.remove(0));
        var result = scanner.scan("unit-request", sha(), BYTES);
        assertEquals("NO_THREAT_REPORTED", result.outcome());
        assertEquals("unit-request", result.scanRequestId());
        assertEquals(sha(), result.contentSha256());
        assertEquals(policy().sha256(), result.policySha256());
        assertEquals(NOW.plusSeconds(120), result.validUntil());
    }

    @Test
    void policyExpiryAndDigestMismatchNeverSendContent() throws Exception {
        var expired = new ClamdAttachmentScanner.Policy(19310, "1.4.3", "12345", NOW, 120);
        var scanner = new ClamdAttachmentScanner(expired, CLOCK, (command, content, deadline) -> { throw new AssertionError(); });
        assertEquals("UNKNOWN", scanner.scan("unit", sha(), BYTES).outcome());
        assertThrows(DomainException.class, () -> scanner.scan("unit", "f".repeat(64), BYTES));
    }

    @Test
    void changedVersionOrUnpinnedSignatureCannotProduceCleanEvidence() throws Exception {
        List<byte[]> replies = new ArrayList<>(List.of(VERSION, "stream: OK".getBytes(StandardCharsets.US_ASCII),
            "ClamAV 1.4.3/12346/CHANGED".getBytes(StandardCharsets.US_ASCII)));
        var scanner = new ClamdAttachmentScanner(policy(), CLOCK, (command, content, deadline) -> replies.remove(0));
        assertEquals("UNKNOWN", scanner.scan("unit", sha(), BYTES).outcome());
        var mismatch = new ClamdAttachmentScanner(policy(), CLOCK, (command, content, deadline) -> "ClamAV 1.4.3/999/OTHER".getBytes(StandardCharsets.US_ASCII));
        assertEquals("UNKNOWN", mismatch.scan("unit", sha(), BYTES).outcome());
    }

    @Test
    void threatsAndUnavailableScannersRemainNonReleasableAndRedacted() throws Exception {
        List<byte[]> replies = new ArrayList<>(List.of(VERSION, "stream: PRIVATE_NAME FOUND".getBytes(StandardCharsets.US_ASCII), VERSION));
        var scanner = new ClamdAttachmentScanner(policy(), CLOCK, (command, content, deadline) -> replies.remove(0));
        var result = scanner.scan("unit", sha(), BYTES);
        assertEquals("THREAT_REPORTED", result.outcome());
        assertNull(result.validUntil()); assertNull(result.engineVersion());
        var failure = new ClamdAttachmentScanner(policy(), CLOCK, (command, content, deadline) -> { throw new Exception("PRIVATE_DIAGNOSTIC"); });
        assertEquals("UNKNOWN", failure.scan("unit", sha(), BYTES).outcome());
    }
}
