package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.domain.worklog.ManualWorklog;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Explicit preparation-only acceptance. No publication worker, storage client or download route. */
@RestController @RequestMapping("/api/v1/asset-export-jobs") @Profile("remote_test")
public final class AssetExportJobController {
    static final int MAX_BODY_BYTES=8192;
    static final String MODE="PREPARE_ONLY_NO_PUBLICATION";
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(1000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final AssetExportJobRepository jobs;
    public AssetExportJobController(PlatformRepository platform,AssetExportJobRepository jobs){this.platform=platform;this.jobs=jobs;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    Object accept(HttpServletRequest r){Access a=access(r);query(r,Set.of());JsonNode input=body(r);fields(input,Set.of("expected_version","mode","filters"));
        if(!input.get("expected_version").isIntegralNumber()||!input.get("expected_version").canConvertToLong()||input.get("expected_version").longValue()!=0||!MODE.equals(text(input.get("mode"))))throw invalid();
        JsonNode f=input.get("filters");fields(f,Set.of("type","status","site_id","query"));var filters=new AssetExportJobState.Filters(nullable(f.get("type")),nullable(f.get("status")),nullable(f.get("site_id")),nullable(f.get("query")));
        String key=r.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object receipt=platform.idempotentAuthorized(a.actorId(),a.tenantId(),r.getMethod()+" "+route(r),key,hash(input),a.permissionVersion(),()->jobs.authorizeAcceptance(a),()->{
            Job job=jobs.accept(a,filters);check(job,a);if(job.state()!=AssetExportJobState.State.QUEUED||job.version()!=0||!filters.equals(job.filters()))throw unavailable();
            return map("job_id",job.id(),"status","QUEUED","version",0,"mode",MODE,"publication_status","NOT_IMPLEMENTED","download_allowed",false);
        });return success(receipt,r);
    }
    @GetMapping Object list(HttpServletRequest r){Access a=access(r);var q=query(r,Set.of("page_size","before_created_at","before_id"));String size=q.getOrDefault("page_size","15");if(!Set.of("10","15","20").contains(size)||q.containsKey("before_created_at")!=q.containsKey("before_id"))throw invalid();int limit=Integer.parseInt(size);Instant before=q.containsKey("before_created_at")?ManualWorklog.parseInstant(q.get("before_created_at")):null;String cursor=q.containsKey("before_id")?uuid(q.get("before_id")):null;var rows=jobs.list(a,limit+1,before,cursor);if(rows==null||rows.size()>limit+1)throw unavailable();for(Job job:rows)check(job,a);boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(rows.size(),limit));Job last=more?page.get(page.size()-1):null;
        return success(map("tenant_id",a.tenantId(),"created_by",a.actorId(),"items",page.stream().map(AssetExportJobController::summary).toList(),"has_more",more,"next_before_created_at",last==null?null:last.createdAt(),"next_before_id",last==null?null:last.id(),"page_size_limit",20,"source","postgresql","scope","CURRENT_CREATOR_TENANT","worker_liveness",null,"publication_status","NOT_IMPLEMENTED","download_allowed",false),r);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest r){Access a=access(r);query(r,Set.of());String canonical=uuid(id);Progress p=jobs.progress(a,canonical);if(p==null)throw unavailable();check(p.job(),a);if(!canonical.equals(p.job().id()))throw unavailable();var value=summary(p.job());value.put("attempts",p.attempts());var e=p.prepared();value.put("prepared",e==null?null:map("selection_sha256",e.selectionSha256(),"file_sha256",e.fileSha256(),"size_bytes",e.sizeBytes(),"row_count",e.rowCount(),"generated_at",e.generatedAt()));value.put("error_code",p.error()==null?null:p.error().name());return success(value,r);}
    static Map<String,Object> summary(Job j){var f=j.filters();return map("id",j.id(),"tenant_id",j.tenantId(),"created_by",j.createdBy(),"accepted_permission_version",j.permissionVersion(),"accepted_role",j.acceptedRole(),"input_sha256",j.inputSha256(),"filters",map("type",f.type(),"status",f.status(),"site_id",f.siteId(),"query",f.query()),"status",j.state().name(),"version",j.version(),"created_at",j.createdAt(),"updated_at",j.updatedAt(),"source","postgresql","scope","CURRENT_CREATOR_TENANT","worker_liveness",null,"object_verification","NOT_CHECKED_BY_PROGRESS_READER","publication_status","NOT_IMPLEMENTED","download_allowed",false);}
    private static void check(Job j,Access a){if(j==null||!a.actorId().equals(j.createdBy())||!a.tenantId().equals(j.tenantId())||a.permissionVersion()!=j.permissionVersion()||!Capabilities.internal(j.acceptedRole())||j.filters()==null||j.state()==null)throw unavailable();}
    private Access access(HttpServletRequest r){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();Object value=r.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();String actor=r.getUserPrincipal().getName();var member=platform.membership(actor,tenant).orElseThrow(AssetExportJobController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!Capabilities.internal(member.role())||!member.capabilities().contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","台账导出准备与本人进度仅限当前授权内部角色；未开放客户下载");return new Access(actor,tenant,member.permissionVersion(),trace(r));}
    static JsonNode body(HttpServletRequest r){try{if(r.getContentLengthLong()>MAX_BODY_BYTES)throw large();byte[] bytes=r.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw large();try(var p=JSON.getFactory().createParser(bytes)){int tokens=0;while(p.nextToken()!=null)if(++tokens>64)throw large();}return JSON.readTree(bytes);}catch(DomainException e){throw e;}catch(Exception e){throw invalid();}}
    private static Map<String,String> query(HttpServletRequest r,Set<String> allowed){if(r.getQueryString()!=null&&r.getQueryString().getBytes(StandardCharsets.UTF_8).length>2048)throw invalid();var q=new HashMap<String,String>();r.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v.length!=1||v[0]==null||v[0].isBlank()||v[0].length()>64)throw invalid();q.put(k,v[0]);});return q;}
    private static String uuid(String s){if(s==null||!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(s).toString();}
    private static String text(JsonNode n){if(n==null||!n.isTextual())throw invalid();return n.textValue();}
    private static String nullable(JsonNode n){return n!=null&&n.isNull()?null:text(n);}
    private static void fields(JsonNode n,Set<String> keys){if(n==null||!n.isObject()||n.size()!=keys.size())throw invalid();n.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    private static JsonNode canonical(JsonNode n){if(!n.isObject())return n;var result=JSON.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)result.set(k,canonical(n.get(k)));return result;}
    private static String hash(JsonNode n){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(n))));}catch(Exception e){throw new IllegalStateException("Export request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","导出仅接受明确准备模式、expected_version=0及四个完整可空筛选；不接受租户、下载或发布覆盖");}
    private static DomainException large(){return new DomainException(413,"REQUEST_TOO_LARGE","导出准备请求超过技术保护，未截断受理");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","导出作业投影未通过范围核对");}
}
