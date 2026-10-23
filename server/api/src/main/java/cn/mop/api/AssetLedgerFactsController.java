package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.assetmetadata.AssetLedgerFactsRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.List;
import static cn.mop.api.ApiResponse.*;

/** A public-field allowlist for the authorized ledger; never exposes the internal metadata revision body. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/assets/ledger-facts")
public final class AssetLedgerFactsController {
    private final PlatformRepository platform;
    private final AssetLedgerFactsRepository facts;

    public AssetLedgerFactsController(PlatformRepository platform, AssetLedgerFactsRepository facts) {
        this.platform = platform;
        this.facts = facts;
    }

    @GetMapping
    Object read(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getUserPrincipal() == null || request.getSession(false) == null
                || !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)) {
            throw expired();
        }
        String actor = request.getUserPrincipal().getName();
        var member = platform.membership(actor, tenant).orElseThrow(AssetLedgerFactsController::expired);
        if (!member.active() || !actor.equals(member.userId()) || !tenant.equals(member.tenantId())
                || member.permissionVersion() < 1 || member.permissionVersion() > 9007199254740991L) {
            throw expired();
        }
        if (!member.capabilities().contains("assets:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员没有资产读取权限");
        }
        String[] ids = request.getParameterValues("ids");
        if (request.getParameterMap().size() != 1 || ids == null || ids.length != 1
                || ids[0].length() > 3699 || ids[0].isEmpty()) {
            throw new DomainException(400, "VALIDATION_ERROR", "只允许单个ids参数，不接受租户或字段覆盖");
        }
        List<String> selected = Arrays.asList(ids[0].split(",", -1));
        var result = facts.read(new AssetLedgerFactsRepository.Access(actor, tenant, member.permissionVersion()), selected);
        return success(map("tenant_id", result.tenantId(), "observed_at", result.observedAt(), "items", result.items(),
                "source", "postgresql", "scope", "CURRENT_TENANT_PUBLIC_ASSET_FIELDS", "business_relationship_verified", false,
                "proxy_verified", false), request);
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员关系已失效");
    }
}
