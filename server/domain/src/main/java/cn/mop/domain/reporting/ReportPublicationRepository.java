package cn.mop.domain.reporting;

import cn.mop.domain.reporting.ReportPublication.*;
import java.time.Instant;
import java.util.List;

/** Real immutable publication storage. Rendering is an internal trusted operation, never caller-provided bytes. */
public interface ReportPublicationRepository {
    record Access(String actorId, String tenantId, long permissionVersion, String traceId) {}

    record Stored(String label, State state, byte[] payload) {
        public Stored {
            payload = payload.clone();
        }

        public byte[] payload() {
            return payload.clone();
        }
    }

    record Content(Format format, String sha256, byte[] bytes) {
        public Content {
            bytes = bytes.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record Rendered(Artifact html, byte[] htmlBytes, Artifact pdf, byte[] pdfBytes, RenderFailure failure) {
        public Rendered {
            htmlBytes = htmlBytes.clone();
            pdfBytes = pdfBytes == null ? null : pdfBytes.clone();
        }

        public byte[] htmlBytes() {
            return htmlBytes.clone();
        }

        public byte[] pdfBytes() {
            return pdfBytes == null ? null : pdfBytes.clone();
        }
    }

    @FunctionalInterface
    interface Renderer {
        Rendered render(Document document, byte[] payload, Instant now);
    }

    void authorize(Access access, String id, boolean write);
    Stored prepare(Access access, String sourceId, String expectedSourceSha256);
    /** Capture a new frozen source as a child revision without replacing the parent or its delivery history. */
    Stored revise(
        Access access,
        String id,
        long expectedVersion,
        String sourceId,
        String expectedSourceSha256,
        String reason
    );
    Stored get(Access access, String id);
    List<Stored> list(Access access, int limit, Instant before, String beforeId);
    State command(Access access, String id, long expectedVersion, String action, String reason);
    ReadReceipt read(Access access, String id, long expectedVersion);
    /** Read-only management view of frozen recipients and explicit first-read receipts. */
    ReportReadership.Page readership(Access access, String id, ReportReadership.Filter filter, java.util.UUID after);
    Content content(Access access, String id, Format format);
}
