package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.calendar.*;
import cn.mop.domain.calendar.WorkingCalendarRepository.*;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.worklog.ManualWorklog;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Configuration drafts and readonly explicit scenarios, never calendar activation or formal SLA promises. */
@RestController @RequestMapping("/api/v1/working-calendars") @Profile("remote_test")
public final class WorkingCalendarController {
    static final int MAX_BODY_BYTES=262144;
    private static final long MAX_INTEGER=9007199254740991L;
    private static final Set<String> FIELDS=Set.of("name","coverage_start","coverage_end","working_windows","coverage_complete","note");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(12000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final WorkingCalendarRepository calendars;private final ObjectMapper mapper;
    public WorkingCalendarController(PlatformRepository platform,WorkingCalendarRepository calendars,ObjectMapper mapper){this.platform=platform;this.calendars=calendars;this.mapper=mapper;}
    @GetMapping Object list(HttpServletRequest request){
        Access a=access(request,false);Map<String,String> query=query(request,Set.of("page_size","before_created_at","before_id"));int size=pageSize(query);
        if(query.containsKey("before_created_at")!=query.containsKey("before_id"))throw invalid();
        Instant before=query.containsKey("before_created_at")?ManualWorklog.parseInstant(query.get("before_created_at")):null;String id=query.containsKey("before_id")?uuid(query.get("before_id")):null;
        List<Snapshot> all=calendars.list(a,size+1,before,id);boolean more=all.size()>size;var items=all.subList(0,Math.min(size,all.size()));var tail=more?items.get(items.size()-1):null;
        return success(map("items",items.stream().map(WorkingCalendarController::dto).toList(),"has_more",more,"next_before_created_at",tail==null?null:tail.createdAt(),"next_before_id",tail==null?null:tail.id(),"source","postgresql","scope","CURRENT_TENANT_CONFIGURATION_DRAFTS","page_size_limit",100),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request,false);query(request,Set.of());return success(dto(calendars.get(a,uuid(id))),request);}
    @GetMapping("/{id}/history") Object history(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,false);var q=query(request,Set.of("page_size","before_version"));int size=pageSize(q);Long before=q.containsKey("before_version")?queryVersion(q.get("before_version")):null;var all=calendars.history(a,uuid(id),size+1,before);boolean more=all.size()>size;var items=all.subList(0,Math.min(size,all.size()));var tail=more?items.get(items.size()-1):null;
        return success(map("items",items.stream().map(v->map("calendar_id",v.calendarId(),"tenant_id",v.tenantId(),"version",v.version(),"before",v.before()==null?null:draftDto(v.before()),"after",draftDto(v.after()),"actor_id",v.actorId(),"recorded_at",v.recordedAt())).toList(),"has_more",more,"next_before_version",tail==null?null:tail.version(),"source","postgresql","page_size_limit",100),request);
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object create(HttpServletRequest request){access(request,true);return write(null,body(request),request);}
    @PatchMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) Object update(@PathVariable String id,HttpServletRequest request){access(request,true);return write(id,body(request),request);}
    Object write(String id,JsonNode input,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());String calendar=id==null?null:uuid(id);Set<String> fields=new HashSet<>(FIELDS);if(calendar!=null)fields.add("expected_version");fields(input,fields);
        WorkingCalendarDraft draft=parseDraft(input);long version=calendar==null?0:version(input.get("expected_version"));String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(input),a.permissionVersion(),()->calendars.authorizeCurrentAccess(a,calendar),()->dto(calendar==null?calendars.create(a,draft):calendars.update(a,calendar,version,draft)));return success(result,request);
    }
    /** POST because scenario input is structured; CSRF is enforced by the shared security chain. No writes or receipt. */
    @PostMapping(value="/{id}/preview",consumes=MediaType.APPLICATION_JSON_VALUE) Object previewRequest(@PathVariable String id,HttpServletRequest request){access(request,false);return preview(id,body(request),request);}
    Object preview(String id,JsonNode input,HttpServletRequest request){
        Access a=access(request,false);query(request,Set.of());fields(input,Set.of("expected_version","start","required_seconds"));long version=version(input.get("expected_version"));Instant start=input.get("start").isNull()?null:ManualWorklog.parseInstant(text(input.get("start")));
        JsonNode seconds=input.get("required_seconds");Duration required=null;if(!seconds.isNull()){if(!seconds.isIntegralNumber()||!seconds.canConvertToLong()||seconds.longValue()<0||seconds.longValue()>MAX_INTEGER)throw invalid();required=Duration.ofSeconds(seconds.longValue());}
        Snapshot current=calendars.get(a,uuid(id));if(current.version()!=version)throw new DomainException(409,"VERSION_CONFLICT","日历草稿已变化，请核对版本后重新试算");
        var result=current.draft().preview(current.id(),current.version(),start,required);var math=result.arithmetic();
        return success(map("calendar_id",current.id(),"tenant_id",current.tenantId(),"calendar_version",current.version(),"status",math.status().name(),"reason",math.reason()==null?null:math.reason().name(),"scenario_deadline",result.scenarioDeadline(),"effective_deadline",null,"scope",result.scope(),"effective",false,"customer_confirmation","UNVERIFIED","source","postgresql","timezone",current.draft().timezone(),"start",start,"required_seconds",seconds.isNull()?null:seconds.longValue(),"remaining_duration",math.remaining()==null?null:math.remaining().toString(),"consumed",math.consumed().stream().map(WorkingCalendarController::windowDto).toList()),request);
    }
    static Map<String,Object> dto(Snapshot value){return map("id",value.id(),"tenant_id",value.tenantId(),"version",value.version(),"draft",draftDto(value.draft()),"updated_by",value.actorId(),"created_at",value.createdAt(),"updated_at",value.updatedAt(),"source","postgresql","lifecycle_status","DRAFT","customer_confirmation","UNVERIFIED","effective",false,"record_source","MANUAL_CALENDAR_DRAFT");}
    static Map<String,Object> draftDto(WorkingCalendarDraft value){return map("name",value.name(),"coverage_start",value.coverage().start(),"coverage_end",value.coverage().end(),"working_windows",value.workingWindows().stream().map(WorkingCalendarController::windowDto).toList(),"coverage_complete",value.coverageComplete(),"note",value.note(),"timezone",value.timezone());}
    private static Map<String,Object> windowDto(Interval value){return map("start",value.start(),"end",value.end());}
    private static WorkingCalendarDraft parseDraft(JsonNode input){
        if(!input.get("coverage_complete").isBoolean()||!input.get("working_windows").isArray()||input.get("working_windows").size()>1000)throw invalid();
        try{List<Interval> windows=new ArrayList<>();for(JsonNode window:input.get("working_windows")){fields(window,Set.of("start","end"));windows.add(new Interval(ManualWorklog.parseInstant(text(window.get("start"))),ManualWorklog.parseInstant(text(window.get("end")))));}
            return new WorkingCalendarDraft(text(input.get("name")),new Interval(ManualWorklog.parseInstant(text(input.get("coverage_start"))),ManualWorklog.parseInstant(text(input.get("coverage_end")))),windows,input.get("coverage_complete").booleanValue(),input.get("note").isNull()?null:text(input.get("note")));
        }catch(IllegalArgumentException failure){throw invalid();}
    }
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(WorkingCalendarController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!Capabilities.forRole(member.role()).contains(write?"configurations:write":"configurations:read"))throw new DomainException(403,"ACTION_FORBIDDEN","工作日历草稿仅向当前租户配置管理员开放");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var p=INPUT.getFactory().createParser(bytes)){int tokens=0;while(p.nextToken()!=null)if(++tokens>8050)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static void fields(JsonNode input,Set<String> allowed){if(input==null||!input.isObject()||input.size()!=allowed.size())throw invalid();input.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid();});}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v.length!=1||v[0].length()>64)throw invalid();result.put(k,v[0]);});return result;}
    private static int pageSize(Map<String,String> q){String value=q.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(value))throw invalid();return Integer.parseInt(value);}
    private static long queryVersion(String value){if(value==null||!value.matches("[1-9][0-9]{0,15}"))throw invalid();try{long result=Long.parseLong(value);if(result>MAX_INTEGER)throw invalid();return result;}catch(NumberFormatException e){throw invalid();}}
    private static long version(JsonNode value){if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1||value.longValue()>MAX_INTEGER)throw invalid();return value.longValue();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private JsonNode canonical(JsonNode value){if(value.isObject()){var out=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,canonical(value.get(k))));return out;}if(value.isArray()){var out=mapper.createArrayNode();value.forEach(v->out.add(canonical(v)));return out;}return value;}
    private String hash(JsonNode body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(body))));}catch(Exception failure){throw new IllegalStateException("Calendar request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","日历草稿、试算或分页输入无效；只接受显式字段");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
