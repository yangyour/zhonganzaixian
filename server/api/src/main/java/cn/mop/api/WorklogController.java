package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.worklog.*;
import cn.mop.domain.worklog.WorklogRepository.*;
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

/** Explicit engineer-own intervals. No team override, ticket transition, defaults or financial conclusion. */
@RestController @RequestMapping("/api/v1/tickets/{ticketId}/worklogs") @Profile("remote_test")
public final class WorklogController {
    static final int MAX_BODY_BYTES=32768;
    private static final long MAX_VERSION=9007199254740990L;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(12000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final WorklogRepository worklogs;private final ObjectMapper mapper;
    public WorklogController(PlatformRepository platform,WorklogRepository worklogs,ObjectMapper mapper){this.platform=platform;this.worklogs=worklogs;this.mapper=mapper;}
    @GetMapping Object list(@PathVariable String ticketId,HttpServletRequest request){
        Access a=access(request,false);String ticket=uuid(ticketId);Map<String,String> query=query(request,Set.of("page_size","before_created_at","before_id"));int size=pageSize(query);Instant before=null;String beforeId=null;
        if(query.containsKey("before_created_at")!=query.containsKey("before_id"))throw invalid();
        if(query.containsKey("before_id")){before=ManualWorklog.parseInstant(query.get("before_created_at"));beforeId=uuid(query.get("before_id"));}
        List<Entry> all=worklogs.listOwn(a,ticket,size+1,before,beforeId);boolean more=all.size()>size;List<Entry> items=all.subList(0,Math.min(size,all.size()));Entry tail=more?items.get(items.size()-1):null;
        return success(map("items",items.stream().map(WorklogController::dto).toList(),"has_more",more,"next_before_created_at",tail==null?null:tail.createdAt(),"next_before_id",tail==null?null:tail.id(),"source","postgresql","scope","OWN","page_size_limit",100),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String ticketId,@PathVariable String id,HttpServletRequest request){Access a=access(request,false);query(request,Set.of());return success(dto(worklogs.getOwn(a,uuid(ticketId),uuid(id))),request);}
    @GetMapping("/{id}/history") Object history(@PathVariable String ticketId,@PathVariable String id,HttpServletRequest request){
        Access a=access(request,false);Map<String,String> query=query(request,Set.of("page_size","before_version"));int size=pageSize(query);Long before=query.containsKey("before_version")?queryVersion(query.get("before_version")):null;
        List<Revision> all=worklogs.historyOwn(a,uuid(ticketId),uuid(id),size+1,before);boolean more=all.size()>size;List<Revision> items=all.subList(0,Math.min(size,all.size()));
        return success(map("items",items.stream().map(WorklogController::revisionDto).toList(),"has_more",more,"next_before_version",more?items.get(items.size()-1).version():null,"source","postgresql","scope","OWN","page_size_limit",100),request);
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object createRequest(@PathVariable String ticketId,HttpServletRequest request){access(request,true);return write(ticketId,null,body(request),request);}
    @PatchMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) Object updateRequest(@PathVariable String ticketId,@PathVariable String id,HttpServletRequest request){access(request,true);return write(ticketId,id,body(request),request);}
    Object write(String ticketId,String id,JsonNode body,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());String ticket=uuid(ticketId),record=id==null?null:uuid(id),versionField=record==null?"expected_ticket_version":"expected_version";
        Set<String> expected=Set.of(versionField,"kind","started_at","ended_at","note");if(body==null||!body.isObject())throw invalid();Set<String> actual=new HashSet<>();body.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw invalid();
        JsonNode version=body.get(versionField);if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<1||version.longValue()>MAX_VERSION)throw invalid();
        ManualWorklog.Kind kind;try{kind=ManualWorklog.Kind.valueOf(text(body.get("kind")));}catch(IllegalArgumentException failure){throw invalid();}
        JsonNode note=body.get("note");if(!note.isNull()&&!note.isTextual())throw invalid();ManualWorklog facts=new ManualWorklog(kind,ManualWorklog.parseInstant(text(body.get("started_at"))),ManualWorklog.parseInstant(text(body.get("ended_at"))),note.isNull()?null:note.textValue());
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotent(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(body),a.permissionVersion(),()->dto(record==null?worklogs.create(a,ticket,version.longValue(),facts):worklogs.update(a,ticket,record,version.longValue(),facts)));
        return success(result,request);
    }
    static Map<String,Object> dto(Entry entry){Map<String,Object> value=map("id",entry.id(),"tenant_id",entry.tenantId(),"ticket_id",entry.ticketId(),"owner_id",entry.ownerId(),"version",entry.version(),"created_at",entry.createdAt(),"updated_at",entry.updatedAt());value.putAll(factsDto(entry.facts()));return value;}
    static Map<String,Object> revisionDto(Revision revision){Map<String,Object> value=map("version",revision.version(),"action",revision.action(),"actor_id",revision.actorId(),"recorded_at",revision.recordedAt());value.putAll(factsDto(revision.facts()));return value;}
    private static Map<String,Object> factsDto(ManualWorklog facts){return map("kind",facts.kind().name(),"started_at",facts.startedAt(),"ended_at",facts.endedAt(),"note",facts.note(),"elapsed_millis",facts.elapsedMillis(),"record_source","MANUAL_INTERVAL","source","postgresql","verification_status","SELF_REPORTED","financial_status","CONFIG_PENDING","default_minutes",null,"labor_cost",null,"transport_cost",null,"gross_margin",null);}
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope))throw expired();
        String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(WorklogController::expired);if(!member.active())throw expired();
        if(!member.capabilities().contains(write?"worklogs:write_own":"worklogs:read_own"))throw new DomainException(403,"ACTION_FORBIDDEN","当前只开放工程师本人工时记录");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1||values[0].length()>64)throw invalid();result.put(key,values[0]);});return result;}
    private static int pageSize(Map<String,String> query){String size=query.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(size))throw invalid();return Integer.parseInt(size);}
    private static long queryVersion(String value){if(!value.matches("[1-9][0-9]{0,15}"))throw invalid();try{long version=Long.parseLong(value);if(version>MAX_VERSION)throw invalid();return version;}catch(NumberFormatException failure){throw invalid();}}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>32)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private String hash(JsonNode body){try{var canonical=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();body.fieldNames().forEachRemaining(keys::add);keys.forEach(key->canonical.set(key,body.get(key)));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));}catch(Exception failure){throw new IllegalStateException("Worklog request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","工时字段、版本、日期或技术分页边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
