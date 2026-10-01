package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Platform-authorized original photo access; storage coordinates never reach the browser. */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public class FileController {
    private final PlatformRepository repository;
    private final PhotoPolicy policy;
    private final PhotoObjectStore store;
    private final ObjectMapper mapper;
    private record Access(String actor,String tenant,Membership membership) {}
    public FileController(PlatformRepository repository,PhotoPolicy policy,PhotoObjectStore store,ObjectMapper mapper){this.repository=repository;this.policy=policy;this.store=store;this.mapper=mapper;}

    @GetMapping("/files/policy") Object policy(HttpServletRequest request){access(request,false);noQuery(request);return success(policy.dto(),request);}

    @PostMapping(value="/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(HttpServletRequest request){
        Access access=access(request,true);noQuery(request);String key=requestKey(request);
        Map<String,Part> parts=parts(request);Part file=parts.get("file"),purpose=parts.get("purpose");
        if(file.getSubmittedFileName()==null||purpose.getSubmittedFileName()!=null||purpose.getSize()>64||file.getSize()>PhotoPolicy.MAX_SIZE)throw invalid("multipart 仅接受一张 file 和 purpose=customer_report_photo，照片不得超过5000000 bytes");
        byte[] purposeBytes=bytes(purpose,64),content=bytes(file,PhotoPolicy.MAX_SIZE);
        if(!Arrays.equals(purposeBytes,"customer_report_photo".getBytes(StandardCharsets.UTF_8)))throw invalid("purpose 仅允许 customer_report_photo");
        PhotoUpload photo=policy.validate("customer_report_photo",file.getSubmittedFileName(),file.getContentType(),content);
        // The original content, name, declared type and purpose all participate in replay identity.
        String payloadHash=hash(map("purpose","customer_report_photo","original_name",file.getSubmittedFileName(),"declared_media_type",file.getContentType(),"sha256",photo.sha256()));
        Object accepted=repository.idempotent(access.actor,access.tenant,route(request),key,payloadHash,access.membership.permissionVersion(),()->{
            current(access,true);
            if(!store.ready())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","照片存储当前不可用，未受理新上传");
            Map<String,Object> result=repository.acceptPhoto(access.tenant,access.actor,photo);
            repository.audit(access.actor,access.tenant,"file.customer_report_photo.accept","operation",String.valueOf(result.get("operation_id")),"SUCCEEDED","DURABLE_STAGING_ONLY_UNSCANNED",trace(request));
            return map("operation_id",result.get("operation_id"),"status",result.get("status"));
        });
        return ResponseEntity.status(202).body(success(accepted,request));
    }

    @GetMapping("/files/{id}") Object file(@PathVariable String id,HttpServletRequest request){
        Access access=access(request,false);noQuery(request);
        Map<String,Object> result=repository.file(access.tenant,access.actor,id).orElseThrow(FileController::missing);
        Map<String,Object> publicFile=new LinkedHashMap<>();
        for(String field:List.of("id","tenant_id","purpose","original_name","media_type","size_bytes","sha256","upload_status","scan_status","retention_until","production_approval","policy_source","created_by","ticket_id","operation_id","version","created_at","updated_at","source"))publicFile.put(field,result.get(field));
        return success(publicFile,request);
    }

    @PostMapping("/files/{id}/content-grants") Object grant(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,false);noQuery(request);String key=requestKey(request);
        if(body==null||!body.isObject()||!body.isEmpty())throw invalid("下载授权请求体必须为空JSON对象");
        Object result=repository.idempotent(access.actor,access.tenant,route(request),key,hash(Map.of()),access.membership.permissionVersion(),()->{
            current(access,false);Map<String,Object> grant=repository.contentGrant(access.tenant,access.actor,id);
            repository.audit(access.actor,access.tenant,"file.content_grant.create","file",id,"SUCCEEDED","ACTOR_TENANT_PERMISSION_BOUND_15_MINUTES",trace(request));
            return map("content_url",grant.get("content_url"),"expires_at",grant.get("expires_at"));
        });
        return success(result,request);
    }

    @GetMapping("/files/{id}/content") ResponseEntity<byte[]> content(@PathVariable String id,HttpServletRequest request){
        Access access=access(request,false);Map<String,String[]> query=request.getParameterMap();
        if(!query.keySet().equals(Set.of("grant"))||query.get("grant").length!=1||!query.get("grant")[0].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("仅接受一个合法的 grant 查询参数");
        String grant=query.get("grant")[0];Map<String,Object> location=repository.photoContent(access.tenant,access.actor,id,grant);
        byte[] original=store.read((String)location.get("object_key"),((Number)location.get("byte_size")).longValue(),(String)location.get("sha256"));
        // Storage I/O can outlive a role change. Recheck the persisted grant before returning bytes.
        Map<String,Object> confirmed=repository.photoContent(access.tenant,access.actor,id,grant);
        if(!location.equals(confirmed))throw missing();
        repository.audit(access.actor,access.tenant,"file.content.read","file",id,"SUCCEEDED","CURRENT_AUTHORIZATION_ORIGINAL_BYTES_VERIFIED",trace(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename((String)location.get("original_name"),StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType((String)location.get("media_type"))).contentLength(original.length).body(original);
    }

    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        String actor=request.getUserPrincipal().getName(),tenant=(String)request.getSession(false).getAttribute("mop.tenant_id");if(tenant==null)throw expired();
        Membership membership=repository.membership(actor,tenant).orElseThrow(FileController::expired);
        if(!Capabilities.forRole(membership.role()).contains(write?"tickets:report":"tickets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无此照片动作权限");
        return new Access(actor,tenant,membership);
    }
    private void current(Access access,boolean write){Membership current=repository.membership(access.actor,access.tenant).orElseThrow(FileController::expired);if(!Capabilities.forRole(current.role()).contains(write?"tickets:report":"tickets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无此照片动作权限");}
    private static Map<String,Part> parts(HttpServletRequest request){
        try{Collection<Part> raw=request.getParts();Map<String,Part> result=new LinkedHashMap<>();for(Part part:raw){if(!Set.of("file","purpose").contains(part.getName())||result.putIfAbsent(part.getName(),part)!=null)throw invalid("multipart 含未知或重复字段");}if(!result.keySet().equals(Set.of("file","purpose")))throw invalid("multipart 必须只包含 file 和 purpose");return result;}
        catch(DomainException safe){throw safe;}catch(Exception error){throw invalid("multipart 格式无效或超过照片大小上限");}
    }
    private static byte[] bytes(Part part,int maximum){try(var stream=part.getInputStream()){byte[] bytes=stream.readNBytes(maximum+1);if(bytes.length>maximum)throw invalid("multipart 字段超过大小上限，照片最大5000000 bytes");return bytes;}catch(DomainException safe){throw safe;}catch(Exception error){throw invalid("multipart 内容无法读取");}}
    private String hash(Map<String,?> value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(new TreeMap<>(value))));}catch(Exception error){throw new IllegalStateException("Cannot calculate request digest");}}
    private static String requestKey(HttpServletRequest request){String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid("Idempotency-Key 为必填，长度1至128且仅包含字母数字及._:-");return key;}
    private static void noQuery(HttpServletRequest request){if(request.getQueryString()!=null)throw invalid("此接口不接受查询参数");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","会话不存在或成员关系已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
}
