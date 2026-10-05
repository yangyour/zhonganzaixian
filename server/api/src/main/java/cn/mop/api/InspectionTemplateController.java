package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.inspection.InspectionTemplate;
import cn.mop.domain.inspection.InspectionTemplateRepository;
import cn.mop.domain.inspection.InspectionTemplateRepository.*;
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

/** Only unpublished current-tenant template candidates. No approval/publish/scan endpoint. */
@RestController @RequestMapping("/api/v1/inspection-templates") @Profile("remote_test")
public final class InspectionTemplateController {
    static final int MAX_BODY_BYTES=1_048_576;
    private static final long MAX_VERSION=9_007_199_254_740_990L;
    private static final Set<String> FIELDS=Set.of("expected_version","device_type","name","items");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(8000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final InspectionTemplateRepository templates;
    public InspectionTemplateController(PlatformRepository platform,InspectionTemplateRepository templates){this.platform=platform;this.templates=templates;}
    @GetMapping Object list(HttpServletRequest request){
        Access a=access(request,false);var q=query(request,Set.of("name_query","device_type","view","page_size","before_created_at","before_id"));int size=pageSize(q);
        if(q.containsKey("before_created_at")!=q.containsKey("before_id"))throw invalid();Instant before=q.containsKey("before_created_at")?ManualWorklog.parseInstant(q.get("before_created_at")):null;String id=q.containsKey("before_id")?uuid(q.get("before_id")):null;View view;
        try{view=View.valueOf(q.getOrDefault("view","CURRENT"));}catch(IllegalArgumentException failure){throw invalid();}
        var rows=templates.list(a,q.getOrDefault("name_query",""),q.get("device_type"),view,size+1,before,id);boolean more=rows.size()>size;var items=rows.subList(0,Math.min(size,rows.size()));Entry last=more?items.get(items.size()-1):null;
        return success(map("tenant_id",a.tenantId(),"items",items.stream().map(InspectionTemplateController::summaryDto).toList(),"has_more",more,"next_before_created_at",last==null?null:last.createdAt().toString(),"next_before_id",last==null?null:last.id(),"view",view.name(),"source","postgresql","scope","CURRENT_TENANT_PADMIN","page_size_limit",100),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request,false);query(request,Set.of());return success(dto(templates.get(a,uuid(id))),request);}
    @GetMapping("/{id}/history") Object history(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,false);var q=query(request,Set.of("page_size","before_version"));int size=pageSize(q);Long before=q.containsKey("before_version")?version(q.get("before_version")):null;String record=uuid(id);var rows=templates.history(a,record,size+1,before);boolean more=rows.size()>size;var items=rows.subList(0,Math.min(size,rows.size()));
        return success(map("tenant_id",a.tenantId(),"template_id",record,"items",items.stream().map(InspectionTemplateController::revisionDto).toList(),"has_more",more,"next_before_version",more?items.get(items.size()-1).version():null,"source","postgresql","scope","CURRENT_TENANT_PADMIN","page_size_limit",100),request);
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object create(HttpServletRequest request){access(request,true);return write(null,"CREATE",body(request),request);}
    @PatchMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) Object update(@PathVariable String id,HttpServletRequest request){access(request,true);return write(uuid(id),"UPDATE",body(request),request);}
    @PostMapping(value="/{id}/archive",consumes=MediaType.APPLICATION_JSON_VALUE) Object archive(@PathVariable String id,HttpServletRequest request){access(request,true);return write(uuid(id),"ARCHIVE",body(request),request);}
    @PostMapping(value="/{id}/restore",consumes=MediaType.APPLICATION_JSON_VALUE) Object restore(@PathVariable String id,HttpServletRequest request){access(request,true);return write(uuid(id),"RESTORE",body(request),request);}
    Object write(String id,String action,JsonNode input,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());if(!Set.of("CREATE","UPDATE","ARCHIVE","RESTORE").contains(action)||(id==null)!=action.equals("CREATE"))throw invalid();String record=id==null?null:uuid(id);
        fields(input,action.equals("ARCHIVE")?Set.of("expected_version"):FIELDS);JsonNode v=input.get("expected_version");if(!v.isIntegralNumber()||!v.canConvertToLong())throw invalid();long expected=v.longValue();if(action.equals("CREATE")?expected!=0:expected<1||expected>=MAX_VERSION)throw invalid();
        InspectionTemplate definition=action.equals("ARCHIVE")?null:definition(input);String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(input),a.permissionVersion(),()->templates.authorizeWrite(a,record),()->dto(switch(action){case "CREATE"->templates.create(a,0,definition);case "UPDATE"->templates.update(a,record,expected,definition);case "ARCHIVE"->templates.archive(a,record,expected);case "RESTORE"->templates.restore(a,record,expected,definition);default->throw invalid();}));return success(result,request);
    }
    private static InspectionTemplate definition(JsonNode input){
        JsonNode values=input.get("items");if(!values.isArray()||values.isEmpty()||values.size()>InspectionTemplate.MAX_ITEMS)throw invalid();var items=new ArrayList<InspectionTemplate.Item>();
        for(JsonNode value:values){fields(value,Set.of("key","section","label","instruction"));items.add(new InspectionTemplate.Item(text(value.get("key")),text(value.get("section")),text(value.get("label")),value.get("instruction").isNull()?null:text(value.get("instruction"))));}
        return new InspectionTemplate(text(input.get("device_type")),text(input.get("name")),items);
    }
    static Map<String,Object> summaryDto(Entry row){var result=map("id",row.id(),"tenant_id",row.tenantId(),"created_by",row.createdBy(),"version",row.version(),"created_at",row.createdAt().toString(),"updated_at",row.updatedAt().toString(),"recorded_by",row.recordedBy(),"recorded_at",row.recordedAt().toString(),"action",row.action(),"archived",row.archived());result.putAll(definitionDto(row.snapshot(),false));return result;}
    static Map<String,Object> dto(Entry row){var result=summaryDto(row);result.put("items",itemsDto(row.snapshot().definition()));return result;}
    static Map<String,Object> revisionDto(Revision row){var result=map("version",row.version(),"previous_version",row.previousVersion(),"recorded_by",row.recordedBy(),"recorded_at",row.recordedAt().toString(),"action",row.action(),"archived",row.archived());result.putAll(definitionDto(row.snapshot(),true));return result;}
    private static List<Map<String,Object>> itemsDto(InspectionTemplate definition){return definition.items().stream().map(item->map("key",item.key(),"section",item.section(),"label",item.label(),"instruction",item.instruction())).toList();}
    private static Map<String,Object> definitionDto(InspectionTemplate.Snapshot snapshot,boolean full){
        var definition=snapshot.definition();var result=map("device_type",definition.deviceType(),"name",definition.name(),"item_count",definition.items().size(),"content_sha256",snapshot.contentSha256(),"approval_status","UNVERIFIED","publication_status","NOT_IMPLEMENTED","report_published",false,"active_scan_performed",false,"source","postgresql","record_source","EXPLICIT_TEMPLATE_DEFINITION");if(full)result.put("items",itemsDto(definition));return result;
    }
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object value=request.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,tenant).orElseThrow(InspectionTemplateController::expired);
        if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains(write?"inspection_templates:write":"inspection_templates:read"))throw new DomainException(403,"ACTION_FORBIDDEN","未发布巡检模板仅限当前授权平台管理员维护");return new Access(actor,tenant,member.permissionVersion(),trace(request));
    }
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>2000)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){var result=new HashMap<String,String>();request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1||values[0]==null||values[0].codePointCount(0,values[0].length())>(key.equals("name_query")?200:64))throw invalid();result.put(key,values[0]);});return result;}
    private static int pageSize(Map<String,String> query){String value=query.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(value))throw invalid();return Integer.parseInt(value);}
    private static long version(String value){if(!value.matches("[1-9][0-9]{0,15}"))throw invalid();try{long v=Long.parseLong(value);if(v>MAX_VERSION)throw invalid();return v;}catch(NumberFormatException failure){throw invalid();}}
    private static void fields(JsonNode value,Set<String> keys){if(value==null||!value.isObject()||value.size()!=keys.size())throw invalid();value.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    /** Object-key order is immaterial; checklist order, text, nulls and expected version are material. */
    private static JsonNode canonical(JsonNode node){
        if(node.isArray()){var result=INPUT.createArrayNode();node.forEach(value->result.add(canonical(value)));return result;}
        if(!node.isObject())return node;var result=INPUT.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);for(String key:keys)result.set(key,canonical(node.get(key)));return result;
    }
    private static String hash(JsonNode input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(INPUT.writeValueAsBytes(canonical(input))));}catch(Exception failure){throw new IllegalStateException("Inspection request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","巡检模板字段、版本、检查项或查询边界无效；不接受租户、批准或发布字段覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
