package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupVersionRepository.InventoryScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static cn.mop.api.ApiResponse.*;

/** Sensitive immutable-version reads; customer roles cannot request content even if a deployment grant is misconfigured. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/assets/{assetId}/backup-versions")
public final class BackupVersionContentController {
    /** Current session, asset visibility and audit authority, independent of backup-specific grants. */
    private final PlatformRepository platform;
    /** Actual registered-version and storage/key verification before any content is returned. */
    private final BackupVersionContentService service;

    private record Session(Access access, Membership membership) {}

    public BackupVersionContentController(PlatformRepository platform, BackupVersionContentService service) {
        this.platform = platform;
        this.service = service;
    }

    @ModelAttribute
    void privateHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @GetMapping("/{versionId}/content")
    void download(@PathVariable String assetId, @PathVariable String versionId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        Session session = access(request);
        InventoryScope scope = visible(session, assetId);
        UUID version = uuid(versionId);
        String format = "envelope";
        if (!request.getParameterMap().isEmpty()) {
            String[] values = request.getParameterValues("format");
            if (!request.getParameterMap().keySet().equals(Set.of("format")) || values == null || values.length != 1
                    || !Set.of("envelope", "plaintext").contains(values[0])) {
                throw invalid();
            }
            format = values[0];
        }
        try (var content = service.download(session.access(), scope, version, "plaintext".equals(format), trace(request),
                () -> Thread.currentThread().isInterrupted())) {
            recheck(session, assetId, request);
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + content.filename() + "\"");
            response.setContentLength(content.size());
            content.writeTo(response.getOutputStream());
        }
    }

    @GetMapping("/diff")
    Object compare(@PathVariable String assetId, HttpServletRequest request) {
        Session session = access(request);
        InventoryScope scope = visible(session, assetId);
        if (request.getQueryString() == null || request.getQueryString().length() > 2048
                || !request.getParameterMap().keySet().equals(Set.of("before_version_id", "after_version_id"))) {
            throw invalid();
        }
        UUID before = uuid(single(request, "before_version_id"));
        UUID after = uuid(single(request, "after_version_id"));
        var summary = service.compare(session.access(), scope, before, after, trace(request),
                () -> Thread.currentThread().isInterrupted());
        recheck(session, assetId, request);
        var hunks = summary.hunks().stream().map(hunk -> map("before_start", hunk.beforeStart(), "before_count", hunk.beforeCount(),
                "after_start", hunk.afterStart(), "after_count", hunk.afterCount())).toList();
        return success(map("tenant_id", scope.tenantId().toString(), "asset_id", scope.assetId().toString(),
                "before_version_id", before.toString(), "after_version_id", after.toString(), "status", summary.status().name(),
                "changed", summary.changed(), "before_lines", summary.beforeLines(), "after_lines", summary.afterLines(),
                "hunks", hunks, "redaction", summary.redaction(), "noise_policy", summary.noisePolicy(),
                "change_approved", false, "automatic_ticket_allowed", false, "actual_recovery_verified", false), request);
    }

    private Session access(HttpServletRequest request) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null
                || !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)) {
            throw expired();
        }
        String actor = request.getUserPrincipal().getName();
        Membership member = platform.membership(actor, tenant).orElseThrow(BackupVersionContentController::expired);
        if (!member.active() || !actor.equals(member.userId()) || !tenant.equals(member.tenantId())) {
            throw expired();
        }
        if (!Set.of("padmin", "msp").contains(member.role())
                || !member.capabilities().contains("assets:read")) {
            throw new DomainException(403, "BACKUP_CONTENT_ACCESS_DENIED", "当前身份不能读取备份正文或差异");
        }
        return new Session(new Access(uuid(tenant), uuid(actor), member.permissionVersion()), member);
    }

    private InventoryScope visible(Session session, String assetId) {
        UUID asset = uuid(assetId);
        var access = session.access();
        platform.authorizedAsset(access.tenantId().toString(), access.actorId().toString(), access.permissionVersion(), asset.toString())
                .orElseThrow(() -> new DomainException(404, "BACKUP_VERSION_NOT_FOUND", "当前范围没有设备或备份版本"));
        return new InventoryScope(access.tenantId(), asset);
    }

    private void recheck(Session prior, String asset, HttpServletRequest request) {
        if (!prior.equals(access(request))) {
            throw expired();
        }
        visible(prior, asset);
    }

    private static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid();
        }
        return UUID.fromString(value);
    }

    private static String single(HttpServletRequest request, String key) {
        String[] values = request.getParameterValues(key);
        if (values == null || values.length != 1 || values[0] == null) {
            throw invalid();
        }
        return values[0];
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或授权已变化");
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "备份版本读取或比较参数无效");
    }
}
