package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.Access;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

@RestController @RequestMapping("/api/v1/my-export-messages") @Profile("remote_test")
public final class ExportInboxController {
    private final PlatformRepository platform;private final ExportInboxSupport inbox;
    public ExportInboxController(PlatformRepository platform,ExportInboxSupport inbox){this.platform=platform;this.inbox=inbox;}
    @ModelAttribute void headers(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
    @GetMapping Object list(HttpServletRequest r){
        Access a=access(r);inbox.requireEnabled();Set<String> allowed=Set.of("page_size","before_issued_at","before_id","unread_only");
        for(var e:r.getParameterMap().entrySet())if(!allowed.contains(e.getKey())||e.getValue().length!=1)throw invalid();
        String size=r.getParameter("page_size"),before=r.getParameter("before_issued_at"),id=r.getParameter("before_id"),unread=r.getParameter("unread_only");int limit=20;
        if(size!=null){if(!size.matches("[1-9][0-9]?"))throw invalid();limit=Integer.parseInt(size);if(limit>50)throw invalid();}
        if(unread!=null&&!Set.of("true","false").contains(unread)||(before==null)!=(id==null))throw invalid();
        Instant time=null;if(before!=null){try{time=Instant.parse(before);if(!before.equals(time.toString())||time.getNano()%1000000!=0)throw invalid();}catch(RuntimeException e){throw invalid();}uuid(id);}
        var page=inbox.repository.list(a,limit,time,id,"true".equals(unread));var items=new ArrayList<Map<String,Object>>();for(var m:page.items())items.add(dto(m,a,page.observedAt()));var last=page.hasMore()?page.items().get(page.items().size()-1):null;
        return success(map("items",items,"has_more",page.hasMore(),"next_before_issued_at",last==null?null:last.issuedAt(),"next_before_id",last==null?null:last.jobId(),"unread_count",page.unreadCount(),"observed_at",page.observedAt()),r);
    }
    @PostMapping(value="/{id}/read",consumes=MediaType.APPLICATION_JSON_VALUE) Object read(@PathVariable String id,HttpServletRequest r){
        Access a=access(r);inbox.requireEnabled();uuid(id);if(r.getQueryString()!=null)throw invalid();var b=AssetExportJobController.body(r);
        if(!b.isObject()||b.size()!=1||!b.path("expected_version").isIntegralNumber()||!b.path("expected_version").canConvertToLong()||b.path("expected_version").longValue()<0||b.path("expected_version").longValue()>1)throw invalid();long version=b.path("expected_version").longValue();String key=r.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),"POST "+route(r),key,digest("EXPORT_INBOX_READ_V1\n"+id+"\n"+version),a.permissionVersion(),()->inbox.repository.authorizeRead(a,id),()->{var m=inbox.repository.markRead(a,id,version);if(!m.jobId().equals(id)||!m.tenantId().equals(a.tenantId())||!m.recipientId().equals(a.actorId())||m.permissionVersion()!=a.permissionVersion()||m.version()!=1||m.readAt()==null)throw unavailable();return map("job_id",m.jobId(),"version",m.version(),"read_at",m.readAt());});
        return success(result,r);
    }
    private Map<String,Object> dto(ExportInboxRepository.Message m,Access a,Instant now){
        if(m==null||now==null||!m.tenantId().equals(a.tenantId())||!m.recipientId().equals(a.actorId())||m.permissionVersion()!=a.permissionVersion()||m.issuedAt().isAfter(now)||!m.expiresAt().equals(m.issuedAt().plusSeconds(900))||m.version()<0||m.version()>1||(m.version()==0)!=(m.readAt()==null)||m.readAt()!=null&&(m.readAt().isBefore(m.issuedAt())||m.readAt().isAfter(now)))throw unavailable();
        boolean live=now.isBefore(m.expiresAt());String url=live?"/api/v1/asset-export-jobs/"+m.jobId()+"/content?grant="+inbox.publication.grants().issue(m.binding(),m.issuedAt()).token():null;
        return map("job_id",m.jobId(),"tenant_id",m.tenantId(),"recipient_id",m.recipientId(),"permission_version",m.permissionVersion(),"published_version",m.publishedVersion(),"file_sha256",m.sha256(),"size_bytes",m.sizeBytes(),"row_count",m.rowCount(),"issued_at",m.issuedAt(),"expires_at",m.expiresAt(),"version",m.version(),"read_at",m.readAt(),"content_url",url,"link_status",live?"AVAILABLE":"EXPIRED","source","postgresql");
    }
    private Access access(HttpServletRequest r){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();Object t=r.getSession(false).getAttribute("mop.tenant_id");if(!(t instanceof String tenant))throw expired();String actor=r.getUserPrincipal().getName();var m=platform.membership(actor,tenant).orElseThrow(ExportInboxController::expired);if(!m.active())throw expired();if(!Capabilities.internal(m.role())||!m.capabilities().contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前身份没有导出消息权限");return new Access(actor,tenant,m.permissionVersion(),trace(r));}
    private static String digest(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void uuid(String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw invalid();}catch(IllegalArgumentException e){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","导出消息请求参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员会话已失效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","导出消息内容或授权范围未通过核对");}
}
