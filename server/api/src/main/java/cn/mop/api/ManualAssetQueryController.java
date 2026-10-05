package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import cn.mop.domain.worklog.ManualWorklog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Separate internal appended view; existing customer asset listing and fixed columns stay unchanged. */
@RestController @RequestMapping("/api/v1/assets/manual-metadata") @Profile("remote_test")
public final class ManualAssetQueryController {
    private final PlatformRepository platform;private final ManualAssetMetadataRepository metadata;
    public ManualAssetQueryController(PlatformRepository platform,ManualAssetMetadataRepository metadata){this.platform=platform;this.metadata=metadata;}
    @GetMapping Object search(HttpServletRequest request){
        Access access=access(request);if(request.getQueryString()!=null&&request.getQueryString().getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();Map<String,String> query=new HashMap<>();
        request.getParameterMap().forEach((key,values)->{if(values.length!=1||values[0]==null||values[0].length()>4000)throw invalid();query.put(key,values[0]);});
        String size=query.remove("page_size");if(size==null)size="15";if(!Set.of("10","15","25","50","100").contains(size))throw invalid();int limit=Integer.parseInt(size);
        String rawTime=query.remove("before_created_at"),rawId=query.remove("before_id");if((rawTime==null)!=(rawId==null))throw invalid();Instant before=null;String beforeId=null;
        if(rawTime!=null){before=ManualWorklog.parseInstant(rawTime);if(!rawId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();beforeId=UUID.fromString(rawId).toString();}
        ManualAssetFilter filters=ManualAssetFilter.fromQuery(query);List<Current> all=metadata.search(access,filters,limit+1,before,beforeId);boolean more=all.size()>limit;List<Current> items=all.subList(0,Math.min(limit,all.size()));Asset tail=more?items.get(items.size()-1).snapshot().asset():null;
        return success(map("items",items.stream().map(ManualAssetMetadataController::dto).toList(),"has_more",more,"next_before_created_at",tail==null?null:tail.createdAt(),"next_before_id",tail==null?null:tail.id(),"filters",new TreeMap<>(query),"source","postgresql","scope","CURRENT_TENANT_INTERNAL_MANUAL_SOURCE","page_size_limit",100,"pagination_consistency","CURRENT_REQUEST_SNAPSHOT_ONLY","unknown_semantics","EXPLICIT_NULL_NO_INFERENCE"),request);
    }
    private Access access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope))throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(ManualAssetQueryController::expired);if(!member.active())throw expired();
        if(!Capabilities.internal(member.role())||!member.capabilities().contains("assets:write"))throw new DomainException(403,"ACTION_FORBIDDEN","仅开放内部手工资料视图，客户资料查询范围仍待确认");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","资料查询参数重复、字段或分页无效，编码查询最长4096字节；不自动截断筛选条件");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
