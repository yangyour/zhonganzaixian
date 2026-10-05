package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.ticketcomment.*;
import cn.mop.domain.ticketcomment.TicketCommentRepository.*;
import cn.mop.domain.worklog.ManualWorklog;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Reporter-visible immutable text only. Internal identity, notes, workflow changes and providers are excluded. */
@RestController @RequestMapping("/api/v1/tickets/{ticketId}/comments") @Profile("remote_test")
public final class TicketCommentController {
    static final int MAX_BODY_BYTES=32768;
    private static final long MAX_VERSION=9007199254740991L;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(12000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final TicketCommentRepository notes;private final ObjectMapper mapper;
    public TicketCommentController(PlatformRepository platform,TicketCommentRepository notes,ObjectMapper mapper){this.platform=platform;this.notes=notes;this.mapper=mapper;}
    @GetMapping Object list(@PathVariable String ticketId,HttpServletRequest request){
        Access a=access(request,false);String ticket=uuid(ticketId);Map<String,String> query=query(request,Set.of("page_size","before_created_at","before_id"));int size=pageSize(query);Instant before=null;String beforeId=null;
        if(query.containsKey("before_created_at")!=query.containsKey("before_id"))throw invalid();if(query.containsKey("before_id")){before=ManualWorklog.parseInstant(query.get("before_created_at"));beforeId=uuid(query.get("before_id"));}
        List<Entry> all=notes.list(a,ticket,size+1,before,beforeId);boolean more=all.size()>size;List<Entry> items=all.subList(0,Math.min(size,all.size()));Entry tail=more?items.get(items.size()-1):null;
        return success(map("items",items.stream().map(TicketCommentController::dto).toList(),"has_more",more,"next_before_created_at",tail==null?null:tail.createdAt(),"next_before_id",tail==null?null:tail.id(),"source","postgresql","scope","REPORTER_OR_CURRENT_TENANT_INTERNAL","page_size_limit",100),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String ticketId,@PathVariable String id,HttpServletRequest request){Access a=access(request,false);query(request,Set.of());return success(dto(notes.get(a,uuid(ticketId),uuid(id))),request);}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object createRequest(@PathVariable String ticketId,HttpServletRequest request){access(request,true);return write(ticketId,body(request),request);}
    Object write(String ticketId,JsonNode body,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());String ticket=uuid(ticketId);if(body==null||!body.isObject()||body.size()!=2||!body.has("expected_ticket_version")||!body.has("text"))throw invalid();
        JsonNode version=body.get("expected_ticket_version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<1||version.longValue()>MAX_VERSION)throw invalid();TicketComment note=new TicketComment(text(body.get("text")));
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(body),a.permissionVersion(),()->notes.authorizeCurrentAccess(a,ticket),()->dto(notes.create(a,ticket,version.longValue(),note)));return success(result,request);
    }
    static Map<String,Object> dto(Entry comment){return map("id",comment.id(),"tenant_id",comment.tenantId(),"ticket_id",comment.ticketId(),"observed_ticket_version",comment.observedTicketVersion(),"author_kind",comment.authorKind().name(),"author_label",comment.authorKind().publicLabel(),"text",comment.comment().text(),"text_sha256",comment.comment().sha256(),"created_at",comment.createdAt(),"visibility","REPORTER_AND_INTERNAL","record_source","MANUAL_TICKET_COMMENT","verification_status","SELF_REPORTED","source","postgresql","immutable",true,"ticket_state_changed",false,"sla_changed",false,"itsm_applied",false,"itsm_sync_status","NOT_REQUESTED","itsm_sync_available",false);}
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(TicketCommentController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();
        if(!member.capabilities().contains(write?"ticket_comments:write":"ticket_comments:read"))throw new DomainException(403,"ACTION_FORBIDDEN","评论仅向报障人及当前租户获准内部人员开放");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1||values[0].length()>64)throw invalid();result.put(key,values[0]);});return result;}
    private static int pageSize(Map<String,String> query){String size=query.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(size))throw invalid();return Integer.parseInt(size);}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>8)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private String hash(JsonNode body){try{var canonical=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();body.fieldNames().forEachRemaining(keys::add);keys.forEach(key->canonical.set(key,body.get(key)));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));}catch(Exception failure){throw new IllegalStateException("Ticket comment request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","客户可见评论字段、父版本或分页边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
