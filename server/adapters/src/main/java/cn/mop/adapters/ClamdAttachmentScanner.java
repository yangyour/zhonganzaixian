package cn.mop.adapters;

import cn.mop.domain.AttachmentSecurityRepository.ScanObservation;
import cn.mop.domain.PhotoRules;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Candidate ClamD adapter. Only an explicit pinned engine/signature policy can produce releasable evidence. */
public final class ClamdAttachmentScanner {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final byte[] VERSION = "zVERSION\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SCAN = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mop-attachment-scan-deadline");
        thread.setDaemon(true);
        return thread;
    });

    /** Explicit technical scanning policy; no guessed freshness or publication lifetime defaults. */
    public record Policy(int port, String engineVersion, String signatureVersion, Instant signatureValidUntil, int evidenceSeconds) {
        public Policy {
            if (port < 1 || port > 65535 || engineVersion == null || !engineVersion.matches("[A-Za-z0-9._-]{1,64}")
                || signatureVersion == null || !signatureVersion.matches("[0-9]{1,16}") || signatureValidUntil == null
                || evidenceSeconds < 1 || evidenceSeconds > 86400) {
                throw new IllegalArgumentException("Invalid attachment scanner policy");
            }
        }

        public String sha256() {
            try {
                String canonical = engineVersion + "\n" + signatureVersion + "\n" + signatureValidUntil + "\n" + evidenceSeconds;
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public static Optional<Policy> configured(Function<String, String> setting) {
            String enabled = setting.apply("MOP_ATTACHMENT_SCAN_ENABLED");
            if (enabled == null || "false".equals(enabled)) {
                return Optional.empty();
            }
            if (!"true".equals(enabled)) {
                throw new IllegalStateException("FAIL_CLOSED: attachment scanner requires an explicit boolean enablement");
            }
            try {
                return Optional.of(new Policy(Integer.parseInt(setting.apply("MOP_ATTACHMENT_SCANNER_PORT")),
                    setting.apply("MOP_ATTACHMENT_SCANNER_ENGINE_VERSION"), setting.apply("MOP_ATTACHMENT_SCANNER_SIGNATURE_VERSION"),
                    Instant.parse(setting.apply("MOP_ATTACHMENT_SCANNER_SIGNATURE_VALID_UNTIL")),
                    Integer.parseInt(setting.apply("MOP_ATTACHMENT_SCAN_EVIDENCE_SECONDS"))));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("FAIL_CLOSED: attachment scanner policy is incomplete or invalid");
            }
        }
    }

    /** Transport seam for protocol unit tests, never a default synthetic scanner. */
    @FunctionalInterface
    public interface Transport {
        byte[] exchange(byte[] command, byte[] content, long deadlineNanos) throws Exception;
    }

    private final Policy policy;
    private final Clock clock;
    private final Transport transport;

    public ClamdAttachmentScanner(Policy policy) {
        this(policy, Clock.systemUTC(), (command, content, deadline) -> exchange(policy.port(), command, content, deadline));
    }

    public ClamdAttachmentScanner(Policy policy, Clock clock, Transport transport) {
        this.policy = java.util.Objects.requireNonNull(policy);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.transport = java.util.Objects.requireNonNull(transport);
    }

    public ScanObservation scan(String requestId, String expectedSha, byte[] bytes) {
        PhotoRules.validateStoredContent(bytes, expectedSha);
        ScanObservation unknown = new ScanObservation(requestId, expectedSha, "UNKNOWN", null, null, null, null, null);
        if (!policy.signatureValidUntil().isAfter(clock.instant())) {
            return unknown;
        }
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        try {
            byte[] before = transport.exchange(VERSION.clone(), null, deadline);
            String version = new String(before, StandardCharsets.US_ASCII);
            String prefix = "ClamAV " + policy.engineVersion() + "/" + policy.signatureVersion() + "/";
            if (!version.startsWith(prefix) || !version.substring(prefix.length()).matches("[\\x20-\\x7e]{1,128}")) {
                return unknown;
            }
            byte[] result = transport.exchange(SCAN.clone(), bytes.clone(), deadline);
            if (!Arrays.equals(before, transport.exchange(VERSION.clone(), null, deadline))) {
                return unknown;
            }
            Instant now = clock.instant();
            if (!policy.signatureValidUntil().isAfter(now)) {
                return unknown;
            }
            String reply = new String(result, StandardCharsets.US_ASCII);
            if ("stream: OK".equals(reply)) {
                Instant until = now.plusSeconds(policy.evidenceSeconds());
                if (until.isAfter(policy.signatureValidUntil())) { until = policy.signatureValidUntil(); }
                return new ScanObservation(requestId, expectedSha, "NO_THREAT_REPORTED", policy.engineVersion(),
                    policy.signatureVersion(), policy.sha256(), now, until);
            }
            if (reply.matches("stream: [\\x20-\\x7e]{1,512} FOUND")) {
                return new ScanObservation(requestId, expectedSha, "THREAT_REPORTED", null, null, null, null, null);
            }
            return unknown;
        } catch (Exception unavailable) {
            return unknown;
        }
    }

    private static byte[] exchange(int port, byte[] command, byte[] content, long deadline) throws Exception {
        try (Socket socket = new Socket()) {
            var watchdog = DEADLINES.schedule(() -> {
                try { socket.close(); } catch (java.io.IOException ignored) { }
            }, remainingMillis(deadline), TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), remainingMillis(deadline));
                socket.setSoTimeout(remainingMillis(deadline));
                var output = new DataOutputStream(socket.getOutputStream());
                output.write(command);
                if (content != null) {
                    for (int offset = 0; offset < content.length; offset += 65536) {
                        remainingMillis(deadline);
                        int size = Math.min(65536, content.length - offset);
                        output.writeInt(size);
                        output.write(content, offset, size);
                    }
                    output.writeInt(0);
                }
                output.flush();
                var reply = new ByteArrayOutputStream();
                var input = socket.getInputStream();
                int value;
                do {
                    socket.setSoTimeout(remainingMillis(deadline));
                    value = input.read();
                    if (value >= 0) { reply.write(value); }
                    if (reply.size() > 4096) { throw new java.io.IOException("Scanner response limit"); }
                } while (value >= 0);
                byte[] bytes = reply.toByteArray();
                if (bytes.length < 1 || bytes[bytes.length - 1] != 0) { throw new java.io.IOException("Incomplete scanner response"); }
                for (int i = 0; i < bytes.length - 1; i++) {
                    if (bytes[i] == 0) { throw new java.io.IOException("Multiple scanner responses"); }
                }
                return Arrays.copyOf(bytes, bytes.length - 1);
            } finally {
                watchdog.cancel(false);
            }
        }
    }

    private static int remainingMillis(long deadline) throws java.net.SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new java.net.SocketTimeoutException("Scanner deadline"); }
        return (int) Math.max(1, Math.min(15000, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
}
