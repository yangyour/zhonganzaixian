package cn.mop.api;

import cn.mop.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Read-only recorded facts; tenant scope is derived from current and per-target memberships. */
@RestController @RequestMapping("/api/v1/tenants/inventory") @Profile("remote_test")
public final class TenantInventoryController {
    private final PlatformRepository platform;
    public TenantInventoryController(PlatformRepository platform) { this.platform=platform; }
    @GetMapping Object get(HttpServletRequest request) {
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        Object context=request.getSession(false).getAttribute("mop.tenant_id");
        String actor=request.getUserPrincipal().getName();
        if(!(context instanceof String tenant)||!TenantInventory.canonicalUuid(tenant)||!TenantInventory.canonicalUuid(actor))throw expired();
        Membership member=platform.membership(actor,tenant).orElseThrow(TenantInventoryController::expired);
        if(!member.active()||member.permissionVersion()<1||member.permissionVersion()>9007199254740991L)throw expired();
        if(!"padmin".equals(member.role()))throw new DomainException(403,"ACTION_FORBIDDEN","租户登记资源仅开放当前及每目标租户的平台管理员成员");
        if(request.getQueryString()!=null&&request.getQueryString().getBytes(StandardCharsets.UTF_8).length>512)throw invalid();
        Map<String,String> query=new HashMap<>();
        request.getParameterMap().forEach((key,values)->{if(!Set.of("page_size","after_id").contains(key)||values==null||values.length!=1||values[0]==null)throw invalid();query.put(key,values[0]);});
        String rawSize=query.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(rawSize))throw invalid();
        int limit=Integer.parseInt(rawSize);String after=query.get("after_id");
        var rows=platform.authorizedTenantInventory(tenant,actor,member.permissionVersion(),new TenantInventory.Query(after,limit+1));
        if(rows==null||rows.size()>limit+1)throw broken();
        String previous=after;
        for(var row:rows){if(row==null||previous!=null&&row.id().compareTo(previous)<=0)throw broken();previous=row.id();}
        boolean more=rows.size()>limit;List<TenantInventory> visible=rows.subList(0,Math.min(limit,rows.size()));
        var items=visible.stream().map(TenantInventoryController::projection).toList();
        return success(map("context_tenant_id",tenant,"items",items,"has_more",more,"next_after_id",more?visible.get(visible.size()-1).id():null,"source","postgresql","scope","ACTIVE_PADMIN_MEMBERSHIPS_ONLY","page_size_limit",100,"pagination_consistency","CURRENT_REQUEST_SNAPSHOT_ONLY","count_basis","RECORDED_ROWS_NOT_MONITORED_OR_LICENSED_USAGE","unknown_fields_policy","EXPLICIT_NULL_NO_INFERENCE","new_tenant_creation_enabled",false),request);
    }
    static Map<String,Object> projection(TenantInventory row){
        return map("id",row.id(),"name",row.name(),"created_at",row.createdAt(),"registered_asset_count",Long.toString(row.registeredAssetCount()),"registered_site_count",Long.toString(row.registeredSiteCount()),"active_member_count",Long.toString(row.activeMemberCount()),"isolation_mode",null,"resource_pool",null,"lifecycle_status",null,"asset_quota",null,"monitored_item_count",null,"item_quota",null,"nvps",null,"nvps_quota",null,"data_retention_days",null,"itsm_integration",null,"topology_enabled",null);
    }
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","只接受有界page_size和规范after_id，不接受租户/角色/监控值覆盖");}
    private static DomainException broken(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","租户登记资源回读异常，未返回部分结果");}
}
