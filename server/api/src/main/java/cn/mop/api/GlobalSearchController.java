package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.search.*;
import cn.mop.domain.search.GlobalSearchRepository.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Contract grouped search, with unsupported event/script/bell sources explicitly unavailable. */
@RestController @RequestMapping("/api/v1/search") @Profile("remote_test")
public final class GlobalSearchController {
    private final PlatformRepository platform;private final GlobalSearchRepository search;
    public GlobalSearchController(PlatformRepository platform,GlobalSearchRepository search){this.platform=platform;this.search=search;}
    @GetMapping Object get(HttpServletRequest request){
        Access access=access(request);var params=request.getParameterMap();if(params.size()!=1||!params.containsKey("q")||params.get("q").length!=1||request.getQueryString()!=null&&request.getQueryString().getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();
        GlobalSearchQuery query=new GlobalSearchQuery(params.get("q")[0]);Snapshot result=search.search(access,query);if(!result.tenantId().equals(access.tenantId())||!result.query().equals(query.text()))throw new IllegalStateException("Search scope mismatch");
        var assets=result.assets().stream().map(v->map("id",v.id(),"tenant_id",v.tenantId(),"name",v.name(),"type",v.type(),"record_source",v.source(),"version",v.version(),"resource_kind","ASSET_RECORD")).toList();
        var tickets=result.tickets().stream().map(v->map("id",v.id(),"tenant_id",v.tenantId(),"title",v.title(),"receipt_no",v.receiptNo(),"record_source",v.source(),"registration_status",v.registrationStatus(),"version",v.version(),"resource_kind","CUSTOMER_REPORT_REGISTRATION")).toList();
        return success(map("query",result.query(),"tenant_id",result.tenantId(),"observed_at",result.observedAt(),"scope","CURRENT_TENANT_AUTHORIZED_GROUPED_SEARCH","coverage","PARTIAL","coverage_reason","EVENT_AND_AUTOMATION_SOURCES_NOT_IMPLEMENTED","query_semantics","LITERAL_UNICODE_CASE_INSENSITIVE","snapshot_consistency","ONE_READONLY_TRANSACTION","source","postgresql_supported_groups","notification_bell_status","NOT_IMPLEMENTED","groups",List.of(available("assets",GlobalSearchQuery.ASSET_LIMIT,"CURRENT_TENANT",List.of("name"),assets,result.moreAssets()),unavailable("events",GlobalSearchQuery.EVENT_LIMIT),available("tickets",GlobalSearchQuery.TICKET_LIMIT,result.ticketVisibility(),List.of("title","receipt_no"),tickets,result.moreTickets()),unavailable("automation_scripts",GlobalSearchQuery.AUTOMATION_LIMIT))),request);
    }
    private static Map<String,Object> available(String kind,int limit,String scope,List<String> fields,List<Map<String,Object>> items,boolean more){return map("kind",kind,"status","AVAILABLE","limit",limit,"returned",items.size(),"has_more",more,"items",items,"source_scope",scope,"matched_fields",fields,"reason",null);}
    private static Map<String,Object> unavailable(String kind,int limit){return map("kind",kind,"status","NOT_IMPLEMENTED","limit",limit,"returned",null,"has_more",null,"items",null,"source_scope",null,"matched_fields",null,"reason","SOURCE_NOT_IMPLEMENTED");}
    private Access access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(GlobalSearchController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains("search:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无搜索权限");return new Access(actor,scope,member.permissionVersion());}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","分组搜索只接受一次q参数，当前租户来自会话；不接受limit、来源或身份覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
