package cn.mop.persistence;

import cn.mop.domain.AttachmentSecurityRepository;
import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Append-only file decisions join the platform transaction, idempotency and audit boundary. */
public final class PostgresAttachmentSecurityRepository implements AttachmentSecurityRepository {
    private static final long MAX_VERSION = 9_007_199_254_740_991L;
    private final PostgresPlatformRepository platform;
    private final String table;
    private final String scannerStatus;
    private final String activePolicySha256;

    /** Internal bounded queue descriptor; processing rechecks the submitter and exact current file revision. */
    public record PendingScan(Access access, String fileId, long version, String requestId) {}

    public java.util.Optional<PendingScan> nextQueued(String tenantId) {
        if (!uuid(tenantId)) {
            throw invalid();
        }
        return platform.identityTransaction(connection -> {
            String sql = "SELECT q.file_id,q.actor_id,q.permission_version,q.version,q.scan_request_id FROM " + table
                + " q JOIN public.mop_file f ON f.tenant_id=q.tenant_id AND f.id=q.file_id "
                + "JOIN public.mop_user u ON u.id=q.actor_id AND u.active "
                + "JOIN public.mop_membership m ON m.tenant_id=q.tenant_id AND m.user_id=q.actor_id "
                + "AND m.active AND m.permission_version=q.permission_version "
                + "WHERE q.tenant_id=?::uuid AND q.state='QUEUED' AND f.upload_status='READY' "
                + "AND NOT EXISTS(SELECT 1 FROM " + table + " newer WHERE newer.tenant_id=q.tenant_id "
                + "AND newer.file_id=q.file_id AND newer.version>q.version) ORDER BY q.recorded_at,q.file_id LIMIT 1";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenantId);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return java.util.Optional.empty();
                    }
                    return java.util.Optional.of(new PendingScan(new Access(tenantId, rows.getString("actor_id"),
                        rows.getLong("permission_version")), rows.getString("file_id"), rows.getLong("version"), rows.getString("scan_request_id")));
                }
            } catch (SQLException unavailable) {
                throw new DomainException(503, "ATTACHMENT_SECURITY_UNAVAILABLE", "附件扫描队列尚未就绪");
            }
        });
    }

    public PostgresAttachmentSecurityRepository(PostgresPlatformRepository platform, String schema, String scannerStatus) {
        this(platform, schema, scannerStatus, null);
    }

    public PostgresAttachmentSecurityRepository(PostgresPlatformRepository platform, String schema, String scannerStatus,
                                               String activePolicySha256) {
        this.platform = Objects.requireNonNull(platform);
        if (schema == null || !(schema.equals("public") || schema.matches("mop_security_test_[a-f0-9]{32}"))) {
            throw new IllegalArgumentException("Invalid attachment security namespace");
        }
        if (!java.util.Set.of("NOT_CONFIGURED", "CONFIGURED_NOT_VERIFIED").contains(scannerStatus)) {
            throw new IllegalArgumentException("Invalid scanner configuration state");
        }
        this.table = schema + ".mop_attachment_security_revision";
        this.scannerStatus = scannerStatus;
        if (activePolicySha256 != null && !activePolicySha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid active scanner policy digest");
        }
        this.activePolicySha256 = activePolicySha256;
    }

    @Override
    public Snapshot current(Access access, String fileId) {
        return transaction(access, fileId, false, (connection, file, membership, now) ->
            snapshot(connection, access, fileId, file, membership, now));
    }

    @Override
    public Snapshot change(Access access, String fileId, long expectedVersion, Action action, String traceId) {
        if (action == null || expectedVersion < 0 || expectedVersion >= MAX_VERSION) {
            throw invalid();
        }
        return transaction(access, fileId, true, (connection, file, membership, now) -> {
            Snapshot before = snapshot(connection, access, fileId, file, membership, now);
            if (before.version() != expectedVersion) {
                throw conflict();
            }
            boolean manager = membership.capabilities().contains("configurations:write");
            if ((action == Action.REQUEST_SCAN || action == Action.CANCEL_SCAN) && !before.canRequestScan()) {
                throw forbidden();
            }
            String next;
            String requestId = before.scanRequestId();
            Instant scannedAt = null;
            Instant validUntil = null;
            String engine = null;
            String signatures = null;
            String policy = null;
            switch (action) {
                case REQUEST_SCAN -> {
                    if (!"READY".equals(file.get("upload_status")) || "QUEUED".equals(before.state())) {
                        throw state();
                    }
                    next = "QUEUED";
                    requestId = UUID.randomUUID().toString();
                }
                case CANCEL_SCAN -> {
                    if (!"QUEUED".equals(before.state())) {
                        throw state();
                    }
                    next = "CANCELLED";
                }
                case RELEASE -> {
                    if (!manager) {
                        throw forbidden();
                    }
                    if (!before.releaseAllowed()) {
                        throw state();
                    }
                    next = "RELEASED";
                    scannedAt = before.scannedAt();
                    validUntil = before.validUntil();
                    try (var statement = connection.prepareStatement("SELECT engine_version,signature_version,policy_sha256 FROM "
                        + table + " WHERE tenant_id=?::uuid AND file_id=?::uuid AND version=?")) {
                        statement.setString(1, access.tenantId());
                        statement.setString(2, fileId);
                        statement.setLong(3, before.version());
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next()) { throw conflict(); }
                            engine = rows.getString(1);
                            signatures = rows.getString(2);
                            policy = rows.getString(3);
                        }
                    }
                }
                case QUARANTINE -> {
                    if (!manager) {
                        throw forbidden();
                    }
                    next = "QUARANTINED";
                }
                default -> throw invalid();
            }
            append(connection, access, fileId, before.version() + 1, action.name(), next, requestId,
                before.contentSha256(), scannedAt, validUntil, engine, signatures, policy, traceId);
            platform.audit(access.actorId(), access.tenantId(), "file.security." + action.name().toLowerCase(),
                "file", fileId, "SUCCEEDED", next, traceId);
            return snapshot(connection, access, fileId, file, membership, now);
        });
    }

    @Override
    public Snapshot recordScan(Access access, String fileId, long expectedVersion, ScanObservation observation, String traceId) {
        if (observation == null || !uuid(observation.scanRequestId())
            || !java.util.Set.of("NO_THREAT_REPORTED", "THREAT_REPORTED", "UNKNOWN").contains(observation.outcome())) {
            throw invalid();
        }
        return transaction(access, fileId, true, (connection, file, membership, now) -> {
            Snapshot before = snapshot(connection, access, fileId, file, membership, now);
            if (before.version() != expectedVersion) {
                throw conflict();
            }
            if (!"QUEUED".equals(before.state()) || !Objects.equals(before.scanRequestId(), observation.scanRequestId())
                || !Objects.equals(before.contentSha256(), observation.contentSha256())) {
                throw state();
            }
            String next = switch (observation.outcome()) {
                case "NO_THREAT_REPORTED" -> "CLEAN";
                case "THREAT_REPORTED" -> "REJECTED";
                default -> "UNKNOWN";
            };
            if ("CLEAN".equals(next)) {
                if (activePolicySha256 == null || !activePolicySha256.equals(observation.policySha256())
                    || !token(observation.engineVersion()) || !token(observation.signatureVersion())
                    || observation.policySha256() == null || !observation.policySha256().matches("[a-f0-9]{64}")
                    || observation.scannedAt() == null || observation.validUntil() == null
                    || observation.scannedAt().isAfter(now) || observation.scannedAt().isBefore(before.recordedAt())
                    || !observation.validUntil().isAfter(now)) {
                    throw invalid();
                }
            } else if (observation.validUntil() != null || observation.scannedAt() != null
                || observation.policySha256() != null || observation.engineVersion() != null || observation.signatureVersion() != null) {
                throw invalid();
            }
            append(connection, access, fileId, before.version() + 1, "SCAN_RESULT", next, before.scanRequestId(),
                before.contentSha256(), observation.scannedAt(), observation.validUntil(), observation.engineVersion(),
                observation.signatureVersion(), observation.policySha256(), traceId);
            platform.audit(access.actorId(), access.tenantId(), "file.security.scan_result", "file", fileId,
                "SUCCEEDED", next, traceId);
            return snapshot(connection, access, fileId, file, membership, now);
        });
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection, Map<String, Object> file, Membership membership, Instant now) throws SQLException;
    }

    private <T> T transaction(Access access, String fileId, boolean write, Work<T> work) {
        if (access == null || !uuid(access.actorId()) || !uuid(access.tenantId()) || !uuid(fileId)
            || access.permissionVersion() < 1 || access.permissionVersion() > MAX_VERSION) {
            throw invalid();
        }
        return platform.identityTransaction(connection -> {
            Membership member = platform.membership(access.actorId(), access.tenantId()).orElseThrow(PostgresAttachmentSecurityRepository::missing);
            if (!member.active() || member.permissionVersion() != access.permissionVersion()) {
                throw new DomainException(401, "SESSION_EXPIRED", "成员授权已变化，请刷新会话");
            }
            if (!member.capabilities().contains("tickets:read")) {
                throw forbidden();
            }
            Map<String, Object> file = platform.file(access.tenantId(), access.actorId(), fileId)
                .orElseThrow(PostgresAttachmentSecurityRepository::missing);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM public.mop_file WHERE tenant_id=?::uuid AND id=?::uuid "
                    + (write ? "FOR UPDATE" : "FOR SHARE"))) {
                    lock.setString(1, access.tenantId());
                    lock.setString(2, fileId);
                    try (ResultSet rows = lock.executeQuery()) {
                        if (!rows.next()) {
                            throw missing();
                        }
                    }
                }
                Instant now;
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT clock_timestamp()")) {
                    rows.next();
                    now = rows.getTimestamp(1).toInstant();
                }
                return work.run(connection, file, member, now);
            } catch (SQLException unavailable) {
                throw new DomainException(503, "ATTACHMENT_SECURITY_UNAVAILABLE", "附件隔离结构或受限权限尚未就绪");
            }
        });
    }

    private Snapshot snapshot(Connection connection, Access access, String fileId, Map<String, Object> file,
                              Membership membership, Instant now) throws SQLException {
        boolean manager = membership.capabilities().contains("configurations:write");
        boolean canRequest = manager || access.actorId().equals(file.get("created_by"));
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table
            + " WHERE tenant_id=?::uuid AND file_id=?::uuid ORDER BY version DESC LIMIT 1")) {
            statement.setString(1, access.tenantId());
            statement.setString(2, fileId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new Snapshot(fileId, access.tenantId(), 0, "QUARANTINED", null,
                        (String) file.get("sha256"), null, null, null, false, false, manager, canRequest, scannerStatus);
                }
                String state = rows.getString("state");
                Instant until = time(rows, "valid_until");
                boolean fresh = until != null && until.isAfter(now) && "READY".equals(file.get("upload_status"))
                    && activePolicySha256 != null && activePolicySha256.equals(rows.getString("policy_sha256"))
                    && Objects.equals(file.get("sha256"), rows.getString("content_sha256"));
                return new Snapshot(fileId, access.tenantId(), rows.getLong("version"), state, rows.getString("scan_request_id"),
                    rows.getString("content_sha256"), time(rows, "scanned_at"), until, time(rows, "recorded_at"),
                    manager && fresh && "CLEAN".equals(state), fresh && "RELEASED".equals(state), manager, canRequest, scannerStatus);
            }
        }
    }

    private void append(Connection connection, Access access, String fileId, long version, String action, String state,
                        String requestId, String sha, Instant scannedAt, Instant validUntil, String engine,
                        String signatures, String policy, String traceId) throws SQLException {
        if (traceId == null || !traceId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw invalid();
        }
        String sql = "INSERT INTO " + table + "(tenant_id,file_id,version,action,state,actor_id,permission_version,"
            + "scan_request_id,content_sha256,scanned_at,valid_until,engine_version,signature_version,policy_sha256,trace_id) "
            + "VALUES(?::uuid,?::uuid,?,?,?,?::uuid,?,?::uuid,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, access.tenantId());
            statement.setString(2, fileId);
            statement.setLong(3, version);
            statement.setString(4, action);
            statement.setString(5, state);
            statement.setString(6, access.actorId());
            statement.setLong(7, access.permissionVersion());
            statement.setString(8, requestId);
            statement.setString(9, sha);
            statement.setTimestamp(10, scannedAt == null ? null : java.sql.Timestamp.from(scannedAt));
            statement.setTimestamp(11, validUntil == null ? null : java.sql.Timestamp.from(validUntil));
            statement.setString(12, engine);
            statement.setString(13, signatures);
            statement.setString(14, policy);
            statement.setString(15, traceId);
            statement.executeUpdate();
        }
    }

    private static Instant time(ResultSet rows, String column) throws SQLException {
        var value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,96}");
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "附件安全请求无效");
    }
    private static DomainException missing() {
        return new DomainException(404, "OBJECT_NOT_FOUND", "对象不存在或不可见");
    }
    private static DomainException conflict() {
        return new DomainException(409, "VERSION_CONFLICT", "附件安全状态已变化，请读取当前状态");
    }
    private static DomainException state() {
        return new DomainException(422, "STATE_NOT_ALLOWED", "当前隔离或扫描状态不允许此动作");
    }
    private static DomainException forbidden() {
        return new DomainException(403, "ACTION_FORBIDDEN", "当前成员无附件安全管理权限");
    }
}
