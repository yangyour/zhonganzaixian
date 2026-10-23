package cn.mop.api;

import cn.mop.domain.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

@RestController @Profile("remote_test")
@RequestMapping("/api/v1/monitoring-onboarding/registered-targets")
public class MonitoringScrapeObservationController {
    private final PlatformRepository platform;
    private final MonitoringPresetProbeService registered;
    private final MonitoringScrapeObservationService observations;
    MonitoringScrapeObservationController(PlatformRepository platform, MonitoringPresetProbeService registered, MonitoringScrapeObservationService observations) {
        this.platform=platform; this.registered=registered; this.observations=observations;
    }
    @ModelAttribute void privateHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
    }
    @GetMapping("/{id}/observation") Object read(@PathVariable String id, HttpServletRequest request) { return read(id,request,false); }
    @GetMapping("/{id}/service-observation") Object readService(@PathVariable String id,HttpServletRequest request){return read(id,request,true);}
    private Object read(String id,HttpServletRequest request,boolean includeService) {
        var member=MonitoringOnboardingAccess.member(platform,request,false);
        MonitoringOnboardingAccess.noQuery(request);
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw MonitoringOnboardingAccess.invalid();
        if(!registered.enabled())throw new DomainException(503,"CONFIG_PENDING","服务器尚未配置登记目标目录");
        if(!MonitoringPresetRegistry.uuid(id))throw MonitoringOnboardingAccess.notFound();
        var target=registered.registry.find(member.tenantId(),id).orElseThrow(MonitoringOnboardingAccess::notFound);
        var before=MonitoringOnboardingAccess.asset(platform,member,target.assetId());
        if(!before.managementIp().equals(target.assetIp()))throw MonitoringOnboardingAccess.notFound();
        var report=includeService?observations.readService(target):observations.read(target);
        if(!before.equals(MonitoringOnboardingAccess.asset(platform,member,target.assetId())))
            throw new DomainException(409,"ASSET_CHANGED","资产在查询期间发生变化，请重新读取后再试");
        MonitoringOnboardingAccess.recheck(platform,member,request,false);
        return success(map("tenant_id",member.tenantId(),"actor_id",member.userId(),"permission_version",member.permissionVersion(),
            "asset_scope_checked",true,"source",includeService?"PROMETHEUS_SERVICE_OBSERVATION":"PROMETHEUS_SCRAPE_OBSERVATION","report",report),request);
    }
}
