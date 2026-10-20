package cn.mop.domain.ticket;

import cn.mop.domain.DomainException;
import cn.mop.domain.TicketCustomerActionRules;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Arrays;

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

    /** Strict persisted-document readback; accepts only the exact canonical representation. */
    public static TicketResolutionSnapshot read(byte[] document, String expectedHash) {
        if (document == null || document.length > MAX_DOCUMENT_BYTES) throw invalid();
        byte[] owned = document.clone();
        try {
            var input = ByteBuffer.wrap(owned);
            if (!FORMAT.equals(field(input))) throw invalid();
            UUID tenant = UUID.fromString(field(input));
            UUID ticket = UUID.fromString(field(input));
            long version = input.getLong();
            UUID engineer = UUID.fromString(field(input));
            Instant time = Instant.parse(field(input));
            var snapshot = new TicketResolutionSnapshot(tenant, ticket, version, engineer, time, field(input), field(input));
            if (input.hasRemaining() || !Arrays.equals(owned, snapshot.canonicalBytes())) throw invalid();
            snapshot.requireExpectedHash(expectedHash);
            return snapshot;
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException | java.time.DateTimeException e) {
            throw invalid();
        }
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
    private static String field(ByteBuffer input) {
        int size = input.getInt();
        if (size < 0 || size > input.remaining() || size > MAX_DOCUMENT_BYTES) throw invalid();
        var bytes = input.slice();
        bytes.limit(size);
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
            input.position(input.position() + size);
            return value;
        } catch (CharacterCodingException malformed) {
            throw invalid();
        }
    }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "解决快照需要明确身份、版本和有效UTC毫秒时间"); }
    private static DomainException tooLarge() { return new DomainException(413, "CONTENT_TOO_LARGE", "解决快照超过技术文档预算"); }
}
