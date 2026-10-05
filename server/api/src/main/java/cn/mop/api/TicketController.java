package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public class TicketController {
    private final PlatformRepository repository;
    private final ObjectMapper mapper;
    private final byte[] cursorSecret=new byte[32];
    private record Access(String actor,String tenant,Membership membership) {}
    public TicketController(PlatformRepository repository,ObjectMapper mapper){this.repository=repository;this.mapper=mapper;new SecureRandom().nextBytes(cursorSecret);}

    @PostMapping("/tickets") ResponseEntity<?> create(@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request);noQuery(request);String key=requestKey(request);CustomerReport report=CustomerReportValidator.parse(body,access.membership.role());
        Object accepted=repository.idempotent(access.actor,access.tenant,route(request),key,hash(body),access.membership.permissionVersion(),()->{
            Membership current=repository.membership(access.actor,access.tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));
            if(!current.capabilities().contains("tickets:report"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无客户报障权限");
            CustomerReportRegistration result=repository.registerCustomerReport(access.tenant,access.actor,report);
            if(result.created())repository.audit(access.actor,access.tenant,"ticket.customer_report.register","ticket",result.ticketId(),"SUCCEEDED","DURABLE_RECEIPT_ONLY_RULES_PENDING",trace(request));
            if(result.attachmentsChanged())repository.audit(access.actor,access.tenant,"ticket.attachments.add","ticket",result.ticketId(),"SUCCEEDED","READY_ORIGINAL_PHOTOS_UNSCANNED",trace(request));
            return map("operation_id",result.operationId(),"status","PENDING");
        });
        return ResponseEntity.status(202).body(success(accepted,request));
    }
    @PostMapping("/tickets/{id}/attachments") Object attachments(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request);noQuery(request);String key=requestKey(request);
        if(body==null||!body.isObject())throw CustomerReportValidator.invalid("请求体必须为JSON对象");
        body.fieldNames().forEachRemaining(field->{if(!Set.of("expected_version","attachment_ids").contains(field))throw CustomerReportValidator.invalid("存在未知或禁止字段");});
        JsonNode version=body.get("expected_version");if(version==null||!version.isIntegralNumber()||!version.canConvertToLong()||version.asLong()<1)throw CustomerReportValidator.invalid("expected_version 必须为正整数");
        List<String> ids=CustomerReportValidator.attachmentIds(body.get("attachment_ids"),true);
        Object result=repository.idempotent(access.actor,access.tenant,route(request),key,hash(body),access.membership.permissionVersion(),()->{
            Membership current=repository.membership(access.actor,access.tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));
            if(!current.capabilities().contains("tickets:report"))throw new DomainException(403,"ACTION_FORBIDDEN","仅报障人可追加照片");
            Map<String,Object> ticket=repository.attachPhotos(access.tenant,access.actor,id,version.asLong(),ids);
            repository.audit(access.actor,access.tenant,"ticket.attachments.add","ticket",id,"SUCCEEDED","READY_ORIGINAL_PHOTOS_UNSCANNED",trace(request));return ticket;
        });return success(result,request);
    }
    @GetMapping("/tickets") Object list(@RequestParam Map<String,String> query,HttpServletRequest request){
        Access access=access(request);Set<String> allowed=Set.of("cursor","page_size","category","urgency","q");
        if(!allowed.containsAll(query.keySet())||request.getParameterMap().values().stream().anyMatch(values->values.length!=1)||query.values().stream().anyMatch(value->value.length()>2048))throw CustomerReportValidator.invalid("查询参数不在白名单、重复或过长");
        int size=15;try{if(query.containsKey("page_size"))size=Integer.parseInt(query.get("page_size"));}catch(NumberFormatException error){throw CustomerReportValidator.invalid("page_size 必须为整数");}
        if(!Set.of(10,15,25,50,100).contains(size))throw CustomerReportValidator.invalid("测试阶段单页选项为10/15/25/50/100，正式上限待确认");
        if(query.containsKey("category")&&!CustomerReportValidator.CATEGORIES.contains(query.get("category")))throw CustomerReportValidator.invalid("category 不在报障类别枚举中");
        if(query.containsKey("urgency")&&!Set.of("normal","urgent").contains(query.get("urgency")))throw CustomerReportValidator.invalid("urgency 不在报障紧急程度枚举中");
        if(query.containsKey("q")&&(query.get("q").codePointCount(0,query.get("q").length())>100||query.get("q").codePoints().anyMatch(ConfigurationValueValidator::invalidCharacter)))throw CustomerReportValidator.invalid("q 过长或含非法字符");
        SortedMap<String,String> filters=new TreeMap<>(query);filters.remove("cursor");filters.remove("page_size");String filter=hash(mapper.valueToTree(filters));JsonNode after=query.containsKey("cursor")?readCursor(query.get("cursor"),access,filter):null;
        List<Map<String,Object>> matches=repository.tickets(access.tenant,access.actor,query.get("category"),query.get("urgency"),query.get("q"),after==null?null:Instant.parse(after.path("created_at").asText()),after==null?null:after.path("id").asText(),size+1);
        boolean more=matches.size()>size;List<Map<String,Object>> items=matches.subList(0,Math.min(size,matches.size()));String next=more?cursor(access,filter,items.get(items.size()-1)):null;
        return success(map("items",items,"next_cursor",next,"has_more",more,"source","postgresql","page_size_limit",100,"page_size_limit_kind","test_technical_limit"),request);
    }
    @GetMapping("/tickets/{id}") Object detail(@PathVariable String id,HttpServletRequest request){Access access=access(request);noQuery(request);return success(repository.ticket(access.tenant,access.actor,id).orElseThrow(TicketController::missing),request);}
    @GetMapping("/operations/{id}") Object operation(@PathVariable String id,HttpServletRequest request){Access access=access(request);noQuery(request);return success(repository.operation(access.tenant,access.actor,id).orElseThrow(TicketController::missing),request);}
    private Access access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw new DomainException(401,"SESSION_EXPIRED","会话不存在或已过期");String actor=request.getUserPrincipal().getName();Object tenantValue=request.getSession(false).getAttribute("mop.tenant_id");
        if(!(tenantValue instanceof String tenant)||tenant.isBlank())throw new DomainException(401,"SESSION_EXPIRED","当前租户会话已失效");Membership membership=repository.membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));if(!membership.active())throw new DomainException(401,"SESSION_EXPIRED","成员关系已失效");if(!membership.capabilities().contains("tickets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无工单查询权限");return new Access(actor,tenant,membership);
    }
    private static String requestKey(HttpServletRequest request){String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw CustomerReportValidator.invalid("Idempotency-Key 为必填，长度1至128且仅包含字母数字及._:-");return key;}
    private static void noQuery(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw CustomerReportValidator.invalid("此接口不接受查询参数");}
    private String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(value))));}catch(Exception error){throw new IllegalStateException(error);}}
    private JsonNode canonical(JsonNode value){if(value.isObject()){var object=mapper.createObjectNode();TreeSet<String> names=new TreeSet<>();value.fieldNames().forEachRemaining(names::add);names.forEach(name->object.set(name,canonical(value.get(name))));return object;}if(value.isArray()){var array=mapper.createArrayNode();value.forEach(item->array.add(canonical(item)));return array;}return value;}
    private String cursor(Access access,String filter,Map<String,Object> last){try{String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(map("tenant",access.tenant,"actor",access.actor,"permission_version",access.membership.permissionVersion(),"filter",filter,"created_at",last.get("created_at"),"id",last.get("id"))));return payload+"."+signature(payload);}catch(Exception error){throw new IllegalStateException(error);}}
    private String signature(String payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cursorSecret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private JsonNode readCursor(String token,Access access,String filter){try{String[] parts=token.split("\\.");if(parts.length!=2||!MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),signature(parts[0]).getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();JsonNode value=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));if(!value.path("tenant").asText().equals(access.tenant)||!value.path("actor").asText().equals(access.actor)||value.path("permission_version").asLong()!=access.membership.permissionVersion()||!value.path("filter").asText().equals(filter))throw new IllegalArgumentException();Instant.parse(value.path("created_at").asText());UUID.fromString(value.path("id").asText());return value;}catch(Exception error){throw new DomainException(400,"CURSOR_INVALID","游标无效、过期或不属于当前授权筛选范围");}}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
}
