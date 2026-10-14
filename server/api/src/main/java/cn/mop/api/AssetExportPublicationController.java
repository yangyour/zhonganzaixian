package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.Access;
import cn.mop.domain.assetexport.AssetExportPublicationRepository.Publication;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Explicit publication and actor-bound downloads, separate from historical prepare-only receipts. */
@RestController @RequestMapping("/api/v1/asset-export-jobs") @Profile("remote_test")
public final class AssetExportPublicationController {
    private final PlatformRepository platform;private final AssetExportPublicationRepository publications;private final AssetExportPublicationSupport support;
    public AssetExportPublicationController(PlatformRepository platform,@org.springframework.beans.factory.annotation.Qualifier("assetExportPublicationRepository") AssetExportPublicationRepository publications,AssetExportPublicationSupport support){this.platform=platform;this.publications=publications;this.support=support;}
    @GetMapping("/{id}/publication") Object get(@PathVariable String id,HttpServletRequest r){Access a=access(r);noQuery(r);return success(dto(checked(publications.publication(a,uuid(id)),a,id)),r);}
    @PostMapping(value="/{id}/publication",consumes=MediaType.APPLICATION_JSON_VALUE)
    Object publish(@PathVariable String id,HttpServletRequest r){
        Access a=access(r);noQuery(r);String canonical=uuid(id);JsonNode body=AssetExportJobController.body(r);
        fields(body,Set.of("expected_version","file_sha256","action"));
        if(!body.path("expected_version").isIntegralNumber()||!body.path("expected_version").canConvertToLong()||body.path("expected_version").longValue()<1||body.path("expected_version").longValue()>9007199254740989L
            ||!body.path("file_sha256").isTextual()||!body.path("file_sha256").textValue().matches("[0-9a-f]{64}")||!"PUBLISH_PREPARED_FILE".equals(body.path("action").asText()))throw invalid();
        long version=body.path("expected_version").longValue();String sha=body.path("file_sha256").textValue();support.requireConfigured();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),r.getMethod()+" "+route(r),key(r),hash(version+"\n"+sha+"\nPUBLISH_PREPARED_FILE"),a.permissionVersion(),
            ()->publications.authorizePublication(a,canonical),()->{
                Publication p=publications.publish(a,canonical,version,sha,(object,file)->support.store().storeImmutable(object,file.bytes(),file.encoding().fileSha256()));
                checked(p,a,canonical);if(!p.published())throw unavailable();return dto(p);
            });return success(result,r);
    }
    @PostMapping(value="/{id}/content-grants",consumes=MediaType.APPLICATION_JSON_VALUE)
    Object grant(@PathVariable String id,HttpServletRequest r){
        Access a=access(r);noQuery(r);String canonical=uuid(id);fields(AssetExportJobController.body(r),Set.of());support.requireConfigured();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),r.getMethod()+" "+route(r),key(r),hash("EXPORT_DOWNLOAD_GRANT_V1"),a.permissionVersion(),
            ()->publications.authorizePublication(a,canonical),()->{
                Publication p=checked(publications.publicationInCommand(a,canonical),a,canonical);requirePublished(p);
                var grant=support.grants().issue(p.binding(),p.databaseNow());
                platform.audit(a.actorId(),a.tenantId(),"asset_export.content_grant","asset_export",canonical,"SUCCEEDED","ACTOR_TENANT_VERSION_BOUND_900_SECONDS",a.traceId());
                return map("job_id",canonical,"content_url","/api/v1/asset-export-jobs/"+canonical+"/content?grant="+grant.token(),"expires_at",grant.expiresAt(),"size_bytes",p.prepared().sizeBytes(),"file_sha256",p.prepared().fileSha256());
            });return success(result,r);
    }
    @GetMapping("/{id}/content") ResponseEntity<byte[]> content(@PathVariable String id,HttpServletRequest r){
        Access a=access(r);String canonical=uuid(id);var q=r.getParameterMap();
        if(!q.keySet().equals(Set.of("grant"))||q.get("grant").length!=1||q.get("grant")[0]==null||q.get("grant")[0].length()>80)throw invalid();String token=q.get("grant")[0];support.requireConfigured();
        Publication p=checked(publications.publication(a,canonical),a,canonical);requirePublished(p);support.grants().verify(p.binding(),token,p.databaseNow());
        byte[] bytes=support.store().read(p.objectKey(),p.prepared().sizeBytes(),p.prepared().fileSha256());
        if(bytes==null||bytes.length!=p.prepared().sizeBytes()||!hash(bytes).equals(p.prepared().fileSha256()))throw unavailable();
        Publication after=checked(publications.publication(a,canonical),a,canonical);requirePublished(after);support.grants().verify(after.binding(),token,after.databaseNow());
        if(!p.job().equals(after.job())||!p.prepared().equals(after.prepared())||!Objects.equals(p.objectId(),after.objectId())||!Objects.equals(p.storedAt(),after.storedAt()))throw unavailable();
        platform.audit(a.actorId(),a.tenantId(),"asset_export.content_read","asset_export",canonical,"SUCCEEDED","CURRENT_AUTHORIZATION_AND_HASH_VERIFIED",a.traceId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("asset-export-"+canonical+".xlsx",StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).contentLength(bytes.length).body(bytes);
    }
    private Map<String,Object> dto(Publication p){var e=p.prepared();return map("job_id",p.job().id(),"tenant_id",p.job().tenantId(),"created_by",p.job().createdBy(),"permission_version",p.job().permissionVersion(),"version",p.job().version(),
        "status",p.published()?"PUBLISHED":"NOT_PUBLISHED","prepared",e!=null,"file_sha256",e==null?null:e.fileSha256(),"size_bytes",e==null?null:e.sizeBytes(),"row_count",e==null?null:e.rowCount(),
        "stored_at",p.storedAt(),"observed_at",p.databaseNow(),"publication_enabled",support.enabled(),"download_grant_available",p.published()&&support.enabled(),"notification_sent",false,"source","postgresql");}
    private static Publication checked(Publication p,Access a,String id){if(p==null||p.job()==null||!p.job().id().equals(uuid(id))||!p.job().tenantId().equals(a.tenantId())||!p.job().createdBy().equals(a.actorId())||p.job().permissionVersion()!=a.permissionVersion()||p.databaseNow()==null)throw unavailable();return p;}
    private Access access(HttpServletRequest r){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();Object tenant=r.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String t)||t.isBlank())throw expired();String actor=r.getUserPrincipal().getName();var m=platform.membership(actor,t).orElseThrow(AssetExportPublicationController::expired);if(!m.active())throw expired();if(!Capabilities.internal(m.role())||!m.capabilities().contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","台账导出仅限获授权的内部创建人");return new Access(actor,t,m.permissionVersion(),trace(r));}
    private static void requirePublished(Publication p){if(!p.published())throw new DomainException(409,"EXPORT_NOT_PUBLISHED","文件尚未发布，不能取得下载链接");}
    private static void noQuery(HttpServletRequest r){if(r.getQueryString()!=null)throw invalid();}
    private static void fields(JsonNode b,Set<String> names){if(b==null||!b.isObject()||b.size()!=names.size())throw invalid();b.fieldNames().forEachRemaining(n->{if(!names.contains(n))throw invalid();});}
    private static String uuid(String s){try{if(s==null||!UUID.fromString(s).toString().equals(s))throw invalid();return s;}catch(IllegalArgumentException e){throw invalid();}}
    private static String key(HttpServletRequest r){String k=r.getHeader("Idempotency-Key");if(k==null||!k.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();return k;}
    private static String hash(String s){return hash(s.getBytes(StandardCharsets.UTF_8));}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new IllegalStateException("Export digest unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","导出发布或下载请求字段、版本、摘要不合法");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","导出发布读取未通过范围或文件一致性校验");}
}
