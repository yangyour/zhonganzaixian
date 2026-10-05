package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionWorksheetRepository;
import cn.mop.domain.inspection.InspectionWorksheetRepository.*;
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

/** Only current-tenant manual worksheet drafts. No inspection attestation or report publication. */
@RestController @RequestMapping("/api/v1/inspection-worksheets") @Profile("remote_test")
public final class InspectionWorksheetController {
    static final int MAX_BODY_BYTES=2_097_152;
    private static final long MAX_VERSION=9_007_199_254_740_990L;
    private static final Set<String> CREATE_FIELDS=Set.of("expected_version","asset_id","template_id","template_version","observations","reported_performed_at"), CONTENT_FIELDS=Set.of("expected_version","observations","reported_performed_at");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(8000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final InspectionWorksheetRepository worksheets;
    public InspectionWorksheetController(PlatformRepository platform,InspectionWorksheetRepository worksheets){this.platform=platform;this.worksheets=worksheets;}
    @GetMapping Object list(HttpServletRequest request){
        Access a=access(request,false);var q=query(request,Set.of("asset_id","view","page_size","before_created_at","before_id"));int size=pageSize(q);
        if(q.containsKey("before_created_at")!=q.containsKey("before_id"))throw invalid();Instant before=q.containsKey("before_created_at")?ManualWorklog.parseInstant(q.get("before_created_at")):null;String id=q.containsKey("before_id")?uuid(q.get("before_id")):null;View view;
        try{view=View.valueOf(q.getOrDefault("view","CURRENT"));}catch(IllegalArgumentException failure){throw invalid();}
        var rows=worksheets.list(a,q.containsKey("asset_id")?uuid(q.get("asset_id")):null,view,size+1,before,id);boolean more=rows.size()>size;var items=rows.subList(0,Math.min(size,rows.size()));Entry last=more?items.get(items.size()-1):null;
        return success(map("tenant_id",a.tenantId(),"items",items.stream().map(InspectionWorksheetController::summaryDto).toList(),"has_more",more,"next_before_created_at",last==null?null:last.createdAt().toString(),"next_before_id",last==null?null:last.id(),"view",view.name(),"source","postgresql","scope","CURRENT_TENANT_PADMIN","page_size_limit",20),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request,false);query(request,Set.of());return success(dto(worksheets.get(a,uuid(id))),request);}
    @GetMapping("/{id}/revisions/{version}") Object sourceRevision(@PathVariable String id,@PathVariable String version,HttpServletRequest request){
        Access a=access(request,false);query(request,Set.of());String record=uuid(id);long revision=version(version);
        var captured=worksheets.captureSources(a,List.of(new Selection(record,revision)));
        if(captured==null||!captured.tenantId().toString().equals(a.tenantId())||captured.sources().size()!=1)throw sourceUnavailable();
        var source=captured.sources().get(0);if(!source.worksheetId().toString().equals(record)||source.version()!=revision)throw sourceUnavailable();
        return success(sourceDto(source),request);
    }
    static Map<String,Object> sourceDto(InspectionSourceSnapshot source){
        var result=map("worksheet_id",source.worksheetId().toString(),"tenant_id",source.tenantId().toString(),"version",source.version(),"created_by",source.createdBy().toString(),"created_at",source.createdAt().toString(),"recorded_by",source.recordedBy().toString(),"recorded_at",source.recordedAt().toString(),"action",source.action(),"archived",source.revisionArchived(),"source_type",source.sourceType(),"source_format",InspectionSourceSnapshot.FORMAT,"source_snapshot_sha256",source.fingerprint(),"scope","CURRENT_TENANT_PADMIN","evidence_access_granted",false);
        result.putAll(contentDto(source.content(),true));return result;
    }
    private static DomainException sourceUnavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","来源读取未返回所请求的租户内明确修订，未提供替代结果");}
    @GetMapping("/{id}/history") Object history(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,false);var q=query(request,Set.of("page_size","before_version"));int size=pageSize(q);Long before=q.containsKey("before_version")?version(q.get("before_version")):null;String record=uuid(id);var rows=worksheets.history(a,record,size+1,before);boolean more=rows.size()>size;var items=rows.subList(0,Math.min(size,rows.size()));
        return success(map("tenant_id",a.tenantId(),"worksheet_id",record,"items",items.stream().map(InspectionWorksheetController::revisionDto).toList(),"has_more",more,"next_before_version",more?items.get(items.size()-1).version():null,"source","postgresql","scope","CURRENT_TENANT_PADMIN","page_size_limit",20),request);
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object create(HttpServletRequest request){access(request,true);return write(null,"CREATE",body(request),request);}
    @PatchMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) Object update(@PathVariable String id,HttpServletRequest request){access(request,true);return write(uuid(id),"UPDATE",body(request),request);}
    @PostMapping(value="/{id}/archive",consumes=MediaType.APPLICATION_JSON_VALUE) Object archive(@PathVariable String id,HttpServletRequest request){access(request,true);return write(uuid(id),"ARCHIVE",body(request),request);}
    @PostMapping(value="/{id}/restore",consumes=MediaType.APPLICATION_JSON_VALUE) Object restore(@PathVariable String id,HttpServletRequest request){access(request,true);return write(uuid(id),"RESTORE",body(request),request);}
    Object write(String id,String action,JsonNode input,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());if(!Set.of("CREATE","UPDATE","ARCHIVE","RESTORE").contains(action)||(id==null)!=action.equals("CREATE"))throw invalid();String record=id==null?null:uuid(id);
        fields(input,action.equals("ARCHIVE")?Set.of("expected_version"):action.equals("CREATE")?CREATE_FIELDS:CONTENT_FIELDS);JsonNode v=input.get("expected_version");if(!v.isIntegralNumber()||!v.canConvertToLong())throw invalid();long expected=v.longValue();if(action.equals("CREATE")?expected!=0:expected<1||expected>=MAX_VERSION)throw invalid();
        Content content=action.equals("ARCHIVE")?null:content(input);Creation creation=null;
        if(action.equals("CREATE")){JsonNode sourceVersion=input.get("template_version");if(!sourceVersion.isIntegralNumber()||!sourceVersion.canConvertToLong()||sourceVersion.longValue()<1||sourceVersion.longValue()>MAX_VERSION)throw invalid();creation=new Creation(uuid(text(input.get("asset_id"))),uuid(text(input.get("template_id"))),sourceVersion.longValue(),content);}
        final Creation source=creation;String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(input),a.permissionVersion(),()->worksheets.authorizeWrite(a,record),()->dto(switch(action){case "CREATE"->worksheets.create(a,0,source);case "UPDATE"->worksheets.update(a,record,expected,content);case "ARCHIVE"->worksheets.archive(a,record,expected);case "RESTORE"->worksheets.restore(a,record,expected,content);default->throw invalid();}));return success(result,request);
    }
    private static Content content(JsonNode input){
        JsonNode values=input.get("observations");if(!values.isArray()||values.size()>100)throw invalid();var observations=new LinkedHashMap<String,InspectionWorksheet.Observation>();
        for(JsonNode value:values){fields(value,Set.of("key","result","note"));String key=text(value.get("key"));if(!key.matches("[a-z][a-z0-9_-]{0,63}"))throw invalid();InspectionWorksheet.Result result;try{result=InspectionWorksheet.Result.valueOf(text(value.get("result")));}catch(IllegalArgumentException failure){throw invalid();}
            var observation=new InspectionWorksheet.Observation(result,value.get("note").isNull()?null:text(value.get("note")),List.of());if(observations.put(key,observation)!=null)throw invalid();}
        JsonNode time=input.get("reported_performed_at");Instant performed=null;if(!time.isNull())try{performed=ManualWorklog.parseInstant(text(time));}catch(DomainException failure){throw invalid();}return new Content(observations,performed);
    }
    static Map<String,Object> summaryDto(Entry row){var result=map("id",row.id(),"tenant_id",row.tenantId(),"created_by",row.createdBy(),"version",row.version(),"created_at",row.createdAt().toString(),"updated_at",row.updatedAt().toString(),"recorded_by",row.recordedBy(),"recorded_at",row.recordedAt().toString(),"action",row.action(),"archived",row.archived());result.putAll(contentDto(row.content(),false));return result;}
    static Map<String,Object> dto(Entry row){var result=summaryDto(row);result.putAll(contentDto(row.content(),true));return result;}
    static Map<String,Object> revisionDto(Revision row){var result=map("version",row.version(),"previous_version",row.previousVersion(),"recorded_by",row.recordedBy(),"recorded_at",row.recordedAt().toString(),"action",row.action(),"archived",row.archived());result.putAll(contentDto(row.content(),true));return result;}
    private static Map<String,Object> contentDto(InspectionWorksheetContent content,boolean full){
        var sheet=content.worksheet();var template=sheet.template();var definition=template.definition();var summary=sheet.summary();
        var result=map("asset_id",sheet.assetId().toString(),"asset_name",sheet.assetName(),"template_id",template.templateId().toString(),"template_version",template.version(),"template_content_sha256",template.contentSha256(),"template_device_type",definition.deviceType(),"template_name",definition.name(),"template_item_count",definition.items().size(),"content_sha256",content.fingerprint(),"reported_performed_at",content.reportedPerformedAt()==null?null:content.reportedPerformedAt().toString(),"time_source",content.timeSource(),"summary",map("total",summary.total(),"answered",summary.answered(),"normal",summary.normal(),"attention",summary.attention(),"requires_action",summary.requiresAction(),"unanswered",summary.unanswered(),"all_results_entered",summary.allResultsEntered()),"verification_status","SELF_REPORTED_UNVERIFIED","template_approval_status","UNVERIFIED","template_applicability_status","UNVERIFIED","publication_status","NOT_IMPLEMENTED","report_published",false,"active_scan_performed",false,"evidence_binding_status","NOT_IMPLEMENTED","source","postgresql","record_source","MANUAL_INSPECTION_DRAFT");
        if(full){result.put("template_items",definition.items().stream().map(item->map("key",item.key(),"section",item.section(),"label",item.label(),"instruction",item.instruction())).toList());result.put("observations",sheet.rows().stream().filter(row->row.observation()!=null).map(row->map("key",row.item().key(),"result",row.observation().result().name(),"note",row.observation().note())).toList());}return result;
    }
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object value=request.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,tenant).orElseThrow(InspectionWorksheetController::expired);
        if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains(write?"inspection_worksheets:write":"inspection_worksheets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","未发布巡检执行单草稿仅限当前授权平台管理员维护");return new Access(actor,tenant,member.permissionVersion(),trace(request));
    }
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>2000)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){var result=new HashMap<String,String>();request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1||values[0]==null||values[0].codePointCount(0,values[0].length())>64)throw invalid();result.put(key,values[0]);});return result;}
    private static int pageSize(Map<String,String> query){String value=query.getOrDefault("page_size","15");if(!Set.of("10","15","20").contains(value))throw invalid();return Integer.parseInt(value);}
    private static long version(String value){if(!value.matches("[1-9][0-9]{0,15}"))throw invalid();try{long v=Long.parseLong(value);if(v>MAX_VERSION)throw invalid();return v;}catch(NumberFormatException failure){throw invalid();}}
    private static void fields(JsonNode value,Set<String> keys){if(value==null||!value.isObject()||value.size()!=keys.size())throw invalid();value.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    /** Object-key order is immaterial; original request array order, text, nulls and expected version are material. */
    private static JsonNode canonical(JsonNode node){
        if(node.isArray()){var result=INPUT.createArrayNode();node.forEach(value->result.add(canonical(value)));return result;}
        if(!node.isObject())return node;var result=INPUT.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);for(String key:keys)result.set(key,canonical(node.get(key)));return result;
    }
    private static String hash(JsonNode input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(INPUT.writeValueAsBytes(canonical(input))));}catch(Exception failure){throw new IllegalStateException("Inspection request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","巡检执行单字段、版本、检查项或查询边界无效；不接受租户、来源绑定变更、证据文件、批准或发布字段覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
