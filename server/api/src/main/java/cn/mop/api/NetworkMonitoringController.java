package cn.mop.api;

import cn.mop.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

@RestController @Profile("remote_test") @RequestMapping("/api/v1/network-monitoring")
public class NetworkMonitoringController {
    private final PlatformRepository platform;
    private final NetworkMonitoringService service;
    @org.springframework.beans.factory.annotation.Autowired(required=false) FirewallResourceService resources;
    public NetworkMonitoringController(PlatformRepository platform,NetworkMonitoringService service){this.platform=platform;this.service=service;}
    @GetMapping Object list(HttpServletRequest request){
        Membership access=access(request);query(request,Set.of());
        List<Map<String,Object>> result=new ArrayList<>();
        if(!service.enabled())throw new DomainException(503,"CONFIG_PENDING","基础网络监控尚未配置");
        var bindings=service.bindings(access.tenantId());
        for(var binding:bindings){
            Asset asset=asset(access,binding);var summary=service.summary(binding,asset.name());if(resources!=null&&resources.matches(binding))summary.put("vendor_metrics_status","PARTIAL_API");result.add(summary);
        }
        // Provider I/O cannot extend a revoked membership or a changed asset/IP.
        for(var binding:bindings)asset(access,binding);
        recheck(access,request);
        return success(map("tenant_id",access.tenantId(),"generated_at",Instant.now().toString(),"devices",result,
            "source","ZABBIX_SIMPLE_CHECK","scope","CURRENT_TENANT_EXPLICIT_TEST_BINDINGS","refresh_seconds",30),request);
    }
    @GetMapping("/{assetId}/history") Object history(@PathVariable String assetId,HttpServletRequest request){
        Membership access=access(request);var query=query(request,Set.of("metric","window"));
        var binding=service.binding(access.tenantId(),assetId).orElseThrow(NetworkMonitoringController::notFound);
        asset(access,binding);
        Object result=service.history(binding,query.getOrDefault("metric","latency"),query.getOrDefault("window","HOUR_1"));
        asset(access,binding);recheck(access,request);return success(result,request);
    }
    @GetMapping("/{assetId}/resources") Object resources(@PathVariable String assetId,HttpServletRequest request){
        Membership access=access(request);query(request,Set.of());var binding=resourceBinding(access,assetId);asset(access,binding);
        var result=resources.snapshot();asset(access,binding);recheck(access,request);return success(result,request);
    }
    @GetMapping("/{assetId}/resources/history") Object resourceHistory(@PathVariable String assetId,HttpServletRequest request){
        Membership access=access(request);var query=query(request,Set.of("metric","window"));var binding=resourceBinding(access,assetId);asset(access,binding);
        var result=resources.history(query.getOrDefault("metric","cpu"),query.getOrDefault("window","HOUR_1"));asset(access,binding);recheck(access,request);return success(result,request);
    }
    private NetworkMonitoringService.Binding resourceBinding(Membership access,String id){var b=service.binding(access.tenantId(),id).orElseThrow(NetworkMonitoringController::notFound);if(resources==null||!resources.matches(b))throw notFound();return b;}
    private Asset asset(Membership access,NetworkMonitoringService.Binding binding){
        Asset asset=platform.authorizedAsset(access.tenantId(),access.userId(),access.permissionVersion(),binding.assetId()).orElseThrow(NetworkMonitoringController::notFound);
        if(!asset.tenantId().equals(access.tenantId())||!asset.managementIp().equals(binding.ip()))throw notFound();return asset;
    }
    private Membership access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        String actor=request.getUserPrincipal().getName();Object selected=request.getSession(false).getAttribute("mop.tenant_id");
        if(!(selected instanceof String tenant)||!NetworkMonitoringService.uuid(tenant)||!NetworkMonitoringService.uuid(actor))throw expired();
        Membership member=platform.membership(actor,tenant).orElseThrow(NetworkMonitoringController::expired);
        if(!member.active()||!member.userId().equals(actor)||!member.tenantId().equals(tenant)||member.permissionVersion()<1)throw expired();
        if(!member.capabilities().contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前成员没有资产读取权限");return member;
    }
    private void recheck(Membership before,HttpServletRequest request){if(!before.equals(access(request)))throw expired();}
    private Map<String,String> query(HttpServletRequest request,Set<String> allowed){
        if(request.getQueryString()!=null&&request.getQueryString().length()>512)throw invalid();Map<String,String> result=new HashMap<>();
        request.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v==null||v.length!=1||v[0]==null||v[0].length()>32)throw invalid();result.put(k,v[0]);});return result;
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","只接受单个指标和时间窗口，不接受设备地址或来源ID");}
    private static DomainException notFound(){return new DomainException(404,"NOT_FOUND","当前租户未找到该监控资产");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或授权已失效");}
}
