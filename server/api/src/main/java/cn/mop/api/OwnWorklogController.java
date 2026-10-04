package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.worklog.*;
import cn.mop.domain.worklog.WorklogRepository.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Read-only current engineer scope. This is neither team visibility nor a period/financial report. */
@RestController @RequestMapping("/api/v1/my/worklogs") @Profile("remote_test")
public final class OwnWorklogController {
    private final PlatformRepository platform;private final WorklogRepository worklogs;
    public OwnWorklogController(PlatformRepository platform,WorklogRepository worklogs){this.platform=platform;this.worklogs=worklogs;}
    @GetMapping Object list(HttpServletRequest request){
        Access a=access(request);var q=query(request);String sizeValue=q.getOrDefault("page_size","15");if(!Set.of("10","15","20").contains(sizeValue))throw invalid();int size=Integer.parseInt(sizeValue);
        if(q.containsKey("before_created_at")!=q.containsKey("before_id")||q.containsKey("started_from")!=q.containsKey("started_before"))throw invalid();
        Instant before=q.containsKey("before_created_at")?ManualWorklog.parseInstant(q.get("before_created_at")):null;String beforeId=q.containsKey("before_id")?uuid(q.get("before_id")).toString():null;
        ManualWorklog.Kind kind=null;if(q.containsKey("kind"))try{kind=ManualWorklog.Kind.valueOf(q.get("kind"));}catch(IllegalArgumentException e){throw invalid();}
        var filter=new OwnWorklogQuery(q.containsKey("ticket_id")?uuid(q.get("ticket_id")):null,kind,q.containsKey("started_from")?ManualWorklog.parseInstant(q.get("started_from")):null,q.containsKey("started_before")?ManualWorklog.parseInstant(q.get("started_before")):null);
        List<Entry> all=worklogs.listOwnAcrossTickets(a,filter,size+1,before,beforeId);if(all==null||all.size()>size+1||all.stream().anyMatch(v->v==null||!a.tenantId().equals(v.tenantId())||!a.actorId().equals(v.ownerId())))throw unavailable();boolean more=all.size()>size;List<Entry> items=all.subList(0,Math.min(size,all.size()));Entry tail=more?items.get(items.size()-1):null;
        return success(map("tenant_id",a.tenantId(),"owner_id",a.actorId(),"items",items.stream().map(WorklogController::dto).toList(),"has_more",more,"next_before_created_at",tail==null?null:tail.createdAt(),"next_before_id",tail==null?null:tail.id(),"filters",map("ticket_id",filter.ticketId()==null?null:filter.ticketId().toString(),"kind",filter.kind()==null?null:filter.kind().name(),"started_from",filter.startedFrom(),"started_before",filter.startedBefore()),"source","postgresql","scope","OWN_CURRENT_TENANT","page_size_limit",20,"projection","CURRENT_MANUAL_ENTRIES","time_selection","STARTED_AT_HALF_OPEN_IF_SUPPLIED","interval_projection","FULL_RECORDED_INTERVAL_NOT_CLIPPED","snapshot_frozen",false,"aggregates",null,"financial_status","CONFIG_PENDING"),request);
    }
    private Access access(HttpServletRequest r){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();Object value=r.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();String actor=r.getUserPrincipal().getName();var member=platform.membership(actor,tenant).orElseThrow(OwnWorklogController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!Capabilities.forRole(member.role()).contains("worklogs:read_own"))throw new DomainException(403,"ACTION_FORBIDDEN","当前仅开放工程师本人的跨工单实填记录，未授予团队或经营数据查看权");return new Access(actor,tenant,member.permissionVersion(),trace(r));}
    private static Map<String,String> query(HttpServletRequest r){if(r.getQueryString()!=null&&r.getQueryString().getBytes(StandardCharsets.UTF_8).length>2048)throw invalid();Set<String> allowed=Set.of("page_size","before_created_at","before_id","ticket_id","kind","started_from","started_before");Map<String,String> q=new HashMap<>();r.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v.length!=1||v[0]==null||v[0].isBlank()||v[0].length()>64)throw invalid();q.put(k,v[0]);});return q;}
    private static UUID uuid(String v){if(v==null||!v.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(v);}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","本人工时查询字段或成对时间/游标无效；不接受租户、他人或聚合覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","本人工时返回范围未通过核对，未展示未知数据");}
}
