package cn.mop.domain.ticket;

import cn.mop.domain.DomainException;
import cn.mop.domain.TicketCustomerActionRules;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Immutable candidate M5-15 evidence. Identity references must be obtained by the
 * future tenant-authorized repository, not trusted from HTTP request fields.
 * Hashing establishes byte integrity only, not identity verification or approval.
 */
public record TicketResolutionSnapshot(UUID tenantId, UUID ticketId, long lifecycleVersion,
                                       UUID engineerId, Instant resolvedAt,
                                       String ticketSummary, String solution) {
    public static final String FORMAT = "mop-ticket-resolution-v1";
    /** Technical document budget, not a customer-approved business text limit. */
    public static final int MAX_DOCUMENT_BYTES = 65_536;
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999Z");

    public TicketResolutionSnapshot {
        if (tenantId == null || ticketId == null || engineerId == null || lifecycleVersion < 1
                || resolvedAt == null || resolvedAt.isBefore(MIN) || resolvedAt.isAfter(MAX)
                || resolvedAt.getNano() % 1_000_000 != 0) throw invalid();
        TicketCustomerActionRules.requireUnresolvedReason(ticketSummary);
        TicketCustomerActionRules.requireSolution(solution);
        // Reject oversize text before allocating its UTF-8 encoding.
        if (ticketSummary.length() > MAX_DOCUMENT_BYTES || solution.length() > MAX_DOCUMENT_BYTES) throw tooLarge();
        long bytes = ticketSummary.getBytes(StandardCharsets.UTF_8).length
                + (long) solution.getBytes(StandardCharsets.UTF_8).length
                + FORMAT.length() + 3L * 36 + resolvedAt.toString().length() + 8 + 7 * 4;
        if (bytes > MAX_DOCUMENT_BYTES) throw tooLarge();
    }

    /** Versioned, length-prefixed UTF-8 fields and a big-endian int64 version. */
    public byte[] canonicalBytes() {
        try {
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            field(out, FORMAT);
            field(out, tenantId.toString());
            field(out, ticketId.toString());
            out.writeLong(lifecycleVersion);
            field(out, engineerId.toString());
            field(out, resolvedAt.toString());
            field(out, ticketSummary);
            field(out, solution);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory encoding failed", impossible);
        }
    }

    public String sha256() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes())); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    public void requireExpectedHash(String expected) {
        if (expected == null || !expected.matches("[0-9a-f]{64}") || !sha256().equals(expected))
            throw new DomainException(409, "SOLUTION_SNAPSHOT_CONFLICT", "解决快照不一致，请重新读取");
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "解决快照需要明确身份、版本和有效UTC毫秒时间"); }
    private static DomainException tooLarge() { return new DomainException(413, "CONTENT_TOO_LARGE", "解决快照超过技术文档预算"); }
}
