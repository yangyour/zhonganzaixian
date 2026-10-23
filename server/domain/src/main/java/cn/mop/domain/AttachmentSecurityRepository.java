package cn.mop.domain;

import java.time.Instant;

/** Durable photo quarantine and publication decisions, separate from successful object storage. */
public interface AttachmentSecurityRepository {
    enum Action { REQUEST_SCAN, CANCEL_SCAN, RELEASE, QUARANTINE }

    /** Current session scope; the implementation rechecks membership and file visibility. */
    record Access(String tenantId, String actorId, long permissionVersion) {}

    record Snapshot(String fileId, String tenantId, long version, String state, String scanRequestId,
                    String contentSha256, Instant scannedAt, Instant validUntil, Instant recordedAt,
                    boolean releaseAllowed, boolean contentAllowed, boolean canManageRelease, boolean canRequestScan,
                    String scannerStatus) {}

    /** Accepted only from the internal scanner adapter, never from an HTTP request body. */
    record ScanObservation(String scanRequestId, String contentSha256, String outcome,
                           String engineVersion, String signatureVersion, String policySha256,
                           Instant scannedAt, Instant validUntil) {}

    Snapshot current(Access access, String fileId);

    Snapshot change(Access access, String fileId, long expectedVersion, Action action, String traceId);

    Snapshot recordScan(Access access, String fileId, long expectedVersion, ScanObservation observation, String traceId);

    /** Checks the current release again for every grant/download; old grants do not bypass revocation. */
    default long requireReleased(Access access, String fileId) {
        Snapshot snapshot = current(access, fileId);
        if (!snapshot.contentAllowed()) {
            throw new DomainException(422, "ATTACHMENT_QUARANTINED", "附件尚未通过有效扫描与发布，原图保持隔离");
        }
        return snapshot.version();
    }
}
