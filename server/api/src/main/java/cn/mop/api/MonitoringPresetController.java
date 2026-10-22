package cn.mop.api;
import cn.mop.domain.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Existing CSRF/session filter applies. POST accepts a registered ID/hash, never a URL or credential. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/monitoring-onboarding/registered-targets")
public class MonitoringPresetController {
    private final PlatformRepository platform;private final MonitoringPresetProbeService service;
    public MonitoringPresetController(PlatformRepository platform,MonitoringPresetProbeService service){this.platform=platform;this.service=service;}
    @ModelAttribute void privateHeaders(HttpServletResponse r){r.setHeader("Cache-Control","no-store");r.setHeader("Referrer-Policy","no-referrer");}
    @GetMapping Object list(HttpServletRequest request){var member=access(request,false);boolean expanded=listQuery(request);configured();List<Map<String,Object>> rows=new ArrayList<>();Map<String,Asset> original=new HashMap<>();
        for(var t:service.registry.forTenant(member.tenantId())){Asset a=asset(member,t);original.put(t.id(),a);rows.add(map("target_id",t.id(),"asset_name",a.name(),"configuration",t.configuration(),"configuration_sha256",t.hash(),"test_authorized",service.registry.approved(t)));}
        for(var t:service.registry.forTenant(member.tenantId()))if(!original.get(t.id()).equals(asset(member,t)))throw expired();recheck(member,request,false);
        if(!expanded){Set<String> legacyBindings=new HashSet<>();for(var t:service.registry.forTenant(member.tenantId()))if(!legacyBindings.add(t.assetId()+":"+t.preset().id()))throw new DomainException(409,"CONFIGURATION_VIEW_REQUIRED","该配置包含同设备多服务目标，请使用支持expanded视图的客户端读取完整目标；未返回部分列表");}
        return success(map("tenant_id",member.tenantId(),"actor_id",member.userId(),"permission_version",member.permissionVersion(),"items",rows,"source","SERVER_EXPLICIT_CONFIGURATION","continuous_collection_verified",false),request);
    }
    @PostMapping(path="/{id}/test",consumes=MediaType.APPLICATION_JSON_VALUE) Object test(@PathVariable String id,HttpServletRequest request){
        var member=access(request,true);query(request);configured();if(!MonitoringPresetRegistry.uuid(id))throw notFound();var target=service.registry.find(member.tenantId(),id).orElseThrow(MonitoringPresetController::notFound);
        Asset before=asset(member,target);String expected=body(request);if(!expected.equals(target.hash()))throw new DomainException(409,"VERSION_CONFLICT","登记配置已变化，请重新读取后再测试");
        var result=service.test(target);if(!before.equals(asset(member,target)))throw expired();recheck(member,request,true);
        return success(map("tenant_id",member.tenantId(),"actor_id",member.userId(),"permission_version",member.permissionVersion(),"report",result,"source","SERVER_ONE_SHOT_PROBE"),request);
    }
    private static String body(HttpServletRequest r){try{if(r.getContentLengthLong()>256)throw invalid();byte[] bytes=r.getInputStream().readNBytes(257);if(bytes.length>256)throw invalid();var node=MonitoringPresetRegistry.JSON.readTree(bytes);MonitoringPresetRegistry.fields(node,Set.of("configuration_sha256"));String hash=MonitoringPresetRegistry.text(node,"configuration_sha256");if(!hash.matches("[0-9a-f]{64}"))throw invalid();return hash;}catch(Exception ex){throw invalid();}}
    private void configured(){if(!service.enabled())throw new DomainException(503,"CONFIG_PENDING","服务器尚未启用登记目标测试配置");}
    private Asset asset(Membership m,MonitoringPresetRegistry.Target t){var a=platform.authorizedAsset(m.tenantId(),m.userId(),m.permissionVersion(),t.assetId()).orElseThrow(MonitoringPresetController::notFound);if(!a.tenantId().equals(m.tenantId())||!a.id().equals(t.assetId())||!a.managementIp().equals(t.assetIp()))throw notFound();return a;}
    private Membership access(HttpServletRequest r,boolean test){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();String actor=r.getUserPrincipal().getName();Object chosen=r.getSession(false).getAttribute("mop.tenant_id");if(!(chosen instanceof String tenant)||!MonitoringPresetRegistry.uuid(actor)||!MonitoringPresetRegistry.uuid(tenant))throw expired();var m=platform.membership(actor,tenant).orElseThrow(MonitoringPresetController::expired);if(!m.active()||m.permissionVersion()<1||!m.userId().equals(actor)||!m.tenantId().equals(tenant))throw expired();if(!m.capabilities().containsAll(List.of("assets:read","configurations:read"))||test&&(!m.role().equals("padmin")||!m.capabilities().contains("configurations:write")))throw new DomainException(403,"ACTION_FORBIDDEN","当前身份没有登记监控目标读取或测试权限");return m;}
    private void recheck(Membership m,HttpServletRequest r,boolean test){if(!m.equals(access(r,test)))throw expired();}
    private static boolean listQuery(HttpServletRequest r){
        if(r.getParameterMap().isEmpty()){query(r);return false;}
        String[] views=r.getParameterValues("view");if(r.getParameterMap().size()!=1||views==null||views.length!=1||!"expanded".equals(views[0]))throw invalid();return true;
    }
    private static void query(HttpServletRequest r){if(r.getQueryString()!=null&&!r.getQueryString().isEmpty()||!r.getParameterMap().isEmpty())throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","只接受登记目标ID和当前配置摘要，不接受地址或身份覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话、资产或授权范围已变化");}
    private static DomainException notFound(){return new DomainException(404,"NOT_FOUND","当前租户没有匹配该资产及管理地址的登记目标");}
}
