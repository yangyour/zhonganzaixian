package cn.mop.api;

import cn.mop.domain.Asset;
import cn.mop.domain.PlatformRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

/** Preparation-only endpoints. They cannot register assets, approve addresses or run a probe. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/monitoring-onboarding")
public class MonitoringOnboardingController {
    private final PlatformRepository platform;
    private final MonitoringOnboardingPlanner planner;

    MonitoringOnboardingController(PlatformRepository platform, MonitoringOnboardingPlanner planner) {
        this.platform = platform;
        this.planner = planner;
    }

    @ModelAttribute void privateHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/presets") Object presets(HttpServletRequest request) {
        var member = MonitoringOnboardingAccess.member(platform, request, false);
        MonitoringOnboardingAccess.noQuery(request);
        var catalog = planner.catalog(member.tenantId());
        MonitoringOnboardingAccess.recheck(platform, member, request, false);
        return success(map("tenant_id", member.tenantId(), "actor_id", member.userId(),
                           "permission_version", member.permissionVersion(), "catalog", catalog,
                           "source", "SERVER_PRESET_CATALOG", "network_accessed", false), request);
    }

    @PostMapping(path = "/plans", consumes = MediaType.APPLICATION_JSON_VALUE) Object plan(HttpServletRequest request) {
        var member = MonitoringOnboardingAccess.member(platform, request, true);
        MonitoringOnboardingAccess.noQuery(request);
        var input = body(request);
        Map<String, Asset> snapshots = new LinkedHashMap<>();
        var plan = planner.plan(member.tenantId(), input, id -> {
            Asset asset = MonitoringOnboardingAccess.asset(platform, member, id);
            snapshots.put(id, asset);
            return asset;
        });
        MonitoringOnboardingAccess.recheck(platform, member, request, true);
        for (var entry : snapshots.entrySet()) {
            if (!entry.getValue().equals(MonitoringOnboardingAccess.asset(platform, member, entry.getKey())))
                throw new cn.mop.domain.DomainException(409,"ASSET_CHANGED","资产资料已变化，请重新生成接入计划");
        }
        MonitoringOnboardingAccess.recheck(platform, member, request, true);
        return success(map("tenant_id", member.tenantId(), "actor_id", member.userId(),
                           "permission_version", member.permissionVersion(), "plan", plan,
                           "asset_scope_checked", snapshots.size() == input.get("assets").size(),
                           "source", "SERVER_AUTHORIZED_PREPARATION"), request);
    }

    private static com.fasterxml.jackson.databind.JsonNode body(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > 65536) throw MonitoringOnboardingAccess.invalid();
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) throw MonitoringOnboardingAccess.invalid();
            return MonitoringPresetRegistry.JSON.readTree(bytes);
        } catch (Exception error) { throw MonitoringOnboardingAccess.invalid(); }
    }
}
