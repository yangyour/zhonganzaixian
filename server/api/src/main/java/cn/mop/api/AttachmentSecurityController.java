package cn.mop.api;

import cn.mop.domain.AttachmentSecurityRepository;
import cn.mop.domain.AttachmentSecurityRepository.Access;
import cn.mop.domain.AttachmentSecurityRepository.Action;
import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-authorized quarantine actions. Scanner verdicts have no public write endpoint. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/files/{id}/security")
public class AttachmentSecurityController {
    private final PlatformRepository platform;
    private final AttachmentSecurityRepository security;

    public AttachmentSecurityController(PlatformRepository platform, AttachmentSecurityRepository security) {
        this.platform = platform;
        this.security = security;
    }

    @GetMapping
    Object current(@PathVariable String id, HttpServletRequest request) {
        var subject = subject(request);
        if (!uuid(id)) throw invalid();
        var result = security.current(subject.scope(), id);
        if (result == null || !id.equals(result.fileId()) || !subject.scope().tenantId().equals(result.tenantId())) {
            throw new DomainException(503, "ATTACHMENT_SECURITY_UNAVAILABLE", "附件隔离结果不属于当前范围");
        }
        DashboardReadAuthorization.recheck(request, platform, subject.membership());
        return ApiResponse.success(dto(result), request);
    }

    @PostMapping("/actions")
    Object change(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        Access access = access(request);
        if (!uuid(id)) throw invalid();
        if (body == null || !body.isObject() || body.size() != 2 || !body.has("expected_version") || !body.has("action")
            || !body.get("expected_version").isIntegralNumber() || !body.get("expected_version").canConvertToLong()
            || !body.get("action").isTextual()) {
            throw invalid();
        }
        long expected = body.get("expected_version").asLong();
        if (expected < 0 || expected >= 9_007_199_254_740_990L) {
            throw invalid();
        }
        Action action;
        try {
            action = Action.valueOf(body.get("action").asText());
        } catch (IllegalArgumentException invalidAction) {
            throw invalid();
        }
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")
                || java.util.Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) {
            throw invalid();
        }
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((id + "\n" + action.name() + "\n" + expected).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Runnable authorize = () -> {
            var current = security.current(access, id);
            if (Set.of(Action.RELEASE, Action.QUARANTINE).contains(action) && !current.canManageRelease()) {
                throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员无附件安全管理权限");
            }
            if (Set.of(Action.REQUEST_SCAN, Action.CANCEL_SCAN).contains(action) && !current.canRequestScan()) {
                throw new DomainException(403, "ACTION_FORBIDDEN", "只有上传者或安全管理成员可以申请或取消扫描");
            }
        };
        Object result = platform.idempotentAuthorized(access.actorId(), access.tenantId(), ApiResponse.route(request),
            key, fingerprint, access.permissionVersion(), authorize,
            () -> dto(security.change(access, id, expected, action, ApiResponse.trace(request))));
        return ApiResponse.success(result, request);
    }

    private record Subject(Access scope, cn.mop.domain.Membership membership) {}
    private Access access(HttpServletRequest request) { return subject(request).scope(); }
    private Subject subject(HttpServletRequest request) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw invalid();
        }
        if (request.getUserPrincipal() == null || request.getSession(false) == null) {
            throw new DomainException(401, "SESSION_EXPIRED", "会话不存在或已过期");
        }
        String actor = request.getUserPrincipal().getName();
        if (!(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)
                || !uuid(actor) || !uuid(tenant)) throw invalid();
        var membership = platform.membership(actor, tenant)
            .orElseThrow(() -> new DomainException(401, "SESSION_EXPIRED", "成员关系已失效"));
        if (!membership.active() || !actor.equals(membership.userId()) || !tenant.equals(membership.tenantId())
            || membership.permissionVersion()<1 || membership.permissionVersion()>9007199254740990L || !membership.capabilities().contains("tickets:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员无附件读取权限");
        }
        return new Subject(new Access(tenant, actor, membership.permissionVersion()), membership);
    }

    private static java.util.Map<String,Object> dto(AttachmentSecurityRepository.Snapshot value) {
        return ApiResponse.map("file_id",value.fileId(),"tenant_id",value.tenantId(),"version",value.version(),"state",value.state(),
            "scan_request_id",value.scanRequestId(),"content_sha256",value.contentSha256(),"scanned_at",time(value.scannedAt()),
            "valid_until",time(value.validUntil()),"recorded_at",time(value.recordedAt()),"release_allowed",value.releaseAllowed(),
            "content_allowed",value.contentAllowed(),"can_manage_release",value.canManageRelease(),"can_request_scan",value.canRequestScan(),
            "scanner_status",value.scannerStatus());
    }
    private static String time(java.time.Instant value) { return value == null ? null : value.toString(); }

    private static boolean uuid(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "只接受动作及明确的当前版本，不接受扫描结果或调用者身份");
    }
}
