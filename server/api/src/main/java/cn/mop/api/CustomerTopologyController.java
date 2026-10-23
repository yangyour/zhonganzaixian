package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetdependency.AssetDependencyRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Bounded current-customer view of recorded dependency drafts, never discovered physical links. */
@RestController @Profile("remote_test")
public final class CustomerTopologyController {
    private final PlatformRepository platform;
    private final AssetDependencyRepository dependencies;
    public CustomerTopologyController(PlatformRepository platform, AssetDependencyRepository dependencies) {
        this.platform=platform; this.dependencies=dependencies;
    }
    private record Access(String actor,String tenant,long version) {}
    private Access access(HttpServletRequest request) {
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        Object tenant=request.getSession(false).getAttribute("mop.tenant_id");
        if(!(tenant instanceof String scope))throw expired();
        String actor=request.getUserPrincipal().getName();
        Membership m=platform.membership(actor,scope).orElseThrow(CustomerTopologyController::expired);
        if(!m.active())throw expired();
        if(!m.capabilities().contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","无资产读取权限");
        return new Access(actor,scope,m.permissionVersion());
    }
    @GetMapping("/api/v1/customer-topology")
    Object read(HttpServletRequest request) {
        Access a=access(request);
        if(request.getContentLengthLong()>0||request.getQueryString()!=null&&request.getQueryString().length()>512)throw invalid();
        Set<String> allowed=Set.of("offset","graph_version","asset_id");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1))throw invalid();
        int offset=(int)number(request.getParameter("offset"),0,10000);
        String expected=request.getParameter("graph_version"), focus=request.getParameter("asset_id");
        long version=number(expected,-1,9007199254740991L);
        if(offset>0&&expected==null)throw invalid();
        if(focus!=null&&!focus.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();
        Map<String,Asset> assets=new LinkedHashMap<>();
        if(focus!=null)assets.put(focus,asset(a,focus));
        var draft=dependencies.current(a.tenant(),a.actor());
        if(!a.tenant().equals(draft.tenantId())||draft.edges().size()>10000)throw new DomainException(502,"INVALID_TOPOLOGY_SOURCE","拓扑来源范围无效");
        if(expected!=null&&version!=draft.version())throw new DomainException(409,"TOPOLOGY_VERSION_CHANGED","拓扑已更新，请重新读取第一页");
        var edges=draft.edges().stream().filter(e->focus==null||focus.equals(e.parentId())||focus.equals(e.childId())).sorted(Comparator.comparing(AssetDependencyRepository.ProposedEdge::parentId).thenComparing(AssetDependencyRepository.ProposedEdge::childId)).toList();
        if(offset>edges.size())throw invalid();
        int end=Math.min(offset+50,edges.size());
        var page=edges.subList(offset,end);
        for(var edge:page)for(String id:List.of(edge.parentId(),edge.childId()))if(!assets.containsKey(id))assets.put(id,asset(a,id));
        if(!a.equals(access(request)))throw expired();
        return success(map("tenant_id",a.tenant(),"version",draft.version(),"updated_at",draft.updatedAt(),"source","postgresql","scope","CURRENT_TENANT","lifecycle_status","DRAFT","physical_topology_verified",false,"monitoring_applied",false,
            "focus_asset_id",focus,"offset",offset,"page_size",50,"total_edges",edges.size(),"has_more",end<edges.size(),
            "nodes",assets.values().stream().map(n->map("id",n.id(),"name",n.name(),"type",n.type(),"management_ip",n.managementIp(),"site_id",n.siteId())).toList(),
            "edges",page.stream().map(e->map("parent_id",e.parentId(),"child_id",e.childId(),"proposed_type",e.proposedType(),"proposed_suppression_scope",e.proposedSuppressionScope(),"proposed_enabled",e.proposedEnabled())).toList()),request);
    }
    private Asset asset(Access a,String id) {
        Asset found=platform.authorizedAsset(a.tenant(),a.actor(),a.version(),id).orElseThrow(()->new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见"));
        if(!a.tenant().equals(found.tenantId())||!id.equals(found.id()))throw new DomainException(502,"INVALID_TOPOLOGY_SOURCE","拓扑资产范围无效");
        return found;
    }
    private static long number(String value,long fallback,long max) {
        if(value==null)return fallback;
        if(!value.matches("0|[1-9][0-9]{0,15}"))throw invalid();
        try {long n=Long.parseLong(value);if(n>max)throw invalid();return n;}catch(NumberFormatException e){throw invalid();}
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","拓扑分页或筛选参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系已变化，请重新读取");}
}
