package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionSourceCollectionRepository.*;
import cn.mop.domain.worklog.ManualWorklog;
import cn.mop.persistence.InspectionSourceDocumentCodec;
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
/** Explicit immutable source drafts, never a quarterly rule, inspection attestation or report publication. */
@RestController @RequestMapping("/api/v1/inspection-source-collections") @Profile("remote_test")
public final class InspectionSourceCollectionController {
    static final int MAX_BODY_BYTES=262144;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(1200).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final InspectionSourceCollectionRepository collections;
    public InspectionSourceCollectionController(PlatformRepository platform,InspectionSourceCollectionRepository collections){this.platform=platform;this.collections=collections;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object create(HttpServletRequest request){access(request,true);return write(body(request),request);}
    Object write(JsonNode input,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());fields(input,Set.of("expected_version","label","sources"));var version=input.get("expected_version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()!=0)throw invalid();
        JsonNode rows=input.get("sources");if(!rows.isArray())throw invalid();if(rows.size()>InspectionSourceManifest.MAX_SOURCES)throw tooLarge();var sources=new ArrayList<Source>();
        for(var row:rows){fields(row,Set.of("worksheet_id","version","expected_source_sha256"));var v=row.get("version");if(!v.isIntegralNumber()||!v.canConvertToLong())throw invalid();sources.add(new Source(text(row.get("worksheet_id")),v.longValue(),text(row.get("expected_source_sha256"))));}
        Creation creation=new Creation(text(input.get("label")),sources);String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(input),a.permissionVersion(),()->collections.authorizeWrite(a),()->summaryDto(requireSummary(collections.create(a,0,creation),a,null)));return success(result,request);
    }
    @GetMapping Object list(HttpServletRequest request){
        Access a=access(request,false);var q=query(request,Set.of("page_size","before_recorded_at","before_id"));String value=q.getOrDefault("page_size","15");if(!Set.of("10","15","20").contains(value))throw invalid();int size=Integer.parseInt(value);
        if(q.containsKey("before_recorded_at")!=q.containsKey("before_id"))throw invalid();Instant before=q.containsKey("before_recorded_at")?ManualWorklog.parseInstant(q.get("before_recorded_at")):null;String id=q.containsKey("before_id")?uuid(q.get("before_id")):null;
        var rows=collections.list(a,size+1,before,id);if(rows==null||rows.size()>size+1)throw unavailable();for(var row:rows)requireSummary(row,a,null);boolean more=rows.size()>size;var items=rows.subList(0,Math.min(size,rows.size()));Summary last=more?items.get(items.size()-1):null;
        return success(map("tenant_id",a.tenantId(),"items",items.stream().map(InspectionSourceCollectionController::summaryDto).toList(),"has_more",more,"next_before_recorded_at",last==null?null:last.recordedAt().toString(),"next_before_id",last==null?null:last.id(),"page_size_limit",20,"scope","CURRENT_TENANT_PADMIN","source","postgresql"),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,false);query(request,Set.of());String record=uuid(id);Stored stored=collections.get(a,record);if(stored==null)throw unavailable();Summary row=requireSummary(stored.summary(),a,record);InspectionSourceManifest manifest=stored.manifest();
        if(manifest==null||!manifest.tenantId().toString().equals(a.tenantId())||manifest.selectedSourceCount()!=row.sourceCount()||!manifest.fingerprint().equals(row.sourceManifestSha256())||manifest.sources().stream().anyMatch(s->s.recordedAt().isAfter(row.recordedAt())))throw unavailable();
        var result=summaryDto(row);result.put("projection","FULL_SOURCES");result.put("document_integrity","VERIFIED_SERVER_SIDE");result.put("sources",manifest.sources().stream().map(InspectionWorksheetController::sourceDto).toList());
        result.put("diagnostics",map("distinct_selected_assets",manifest.distinctSelectedAssets(),"unknown_inspection_time",references(manifest.sourcesWithUnknownInspectionTime()),"incompletely_entered",references(manifest.incompletelyEnteredSources()),"archived_selected_revisions",references(manifest.archivedSelectedRevisions()),"assets_with_multiple_sources",manifest.assetsWithMultipleSources().entrySet().stream().map(e->map("asset_id",e.getKey().toString(),"sources",references(e.getValue()))).toList()));
        return success(result,request);
    }
    private static List<Map<String,Object>> references(List<InspectionSourceSnapshot.Reference> refs){return refs.stream().map(r->map("worksheet_id",r.worksheetId().toString(),"version",r.version(),"source_snapshot_sha256",r.snapshotSha256(),"content_sha256",r.contentSha256())).toList();}
    static Map<String,Object> summaryDto(Summary row){return map("id",row.id(),"tenant_id",row.tenantId(),"created_by",row.createdBy(),"recorded_at",row.recordedAt().toString(),"version",row.version(),"label",row.label(),"source_count",row.sourceCount(),"document_format",InspectionSourceDocumentCodec.FORMAT,"document_sha256",row.documentSha256(),"source_manifest_format",InspectionSourceManifest.FORMAT,"source_manifest_sha256",row.sourceManifestSha256(),"projection","METADATA_ONLY","record_source","EXPLICIT_IMMUTABLE_SOURCE_COLLECTION","verification_status","SELF_REPORTED_UNVERIFIED","scope","CURRENT_TENANT_PADMIN","source","postgresql","evidence_access_granted",false,"period_time_zone",null,"quarter_selection_rule",null,"archived_source_policy",null,"metric_version",null,"report_template_version",null,"tenant_asset_population",null,"generation_status","CONFIG_PENDING","formal_report_generatable",false,"report_published",false);}
    private static Summary requireSummary(Summary row,Access a,String id){if(row==null||!a.tenantId().equals(row.tenantId())||id!=null&&!id.equals(row.id()))throw unavailable();return row;}
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object value=request.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership m=platform.membership(actor,tenant).orElseThrow(InspectionSourceCollectionController::expired);
        if(!m.active()||m.permissionVersion()<1)throw expired();if(!Capabilities.forRole(m.role()).contains(write?"inspection_source_collections:write":"inspection_source_collections:read"))throw new DomainException(403,"ACTION_FORBIDDEN","独立来源草稿仅限当前授权平台管理员");return new Access(actor,tenant,m.permissionVersion(),trace(request));
    }
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw tooLarge();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw tooLarge();try(var p=INPUT.getFactory().createParser(bytes)){int tokens=0;while(p.nextToken()!=null)if(++tokens>12000)throw tooLarge();}return INPUT.readTree(bytes);}catch(DomainException e){throw e;}catch(Exception e){throw invalid();}}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){var values=new HashMap<String,String>();request.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v.length!=1||v[0]==null||v[0].length()>64)throw invalid();values.put(k,v[0]);});return values;}
    private static void fields(JsonNode value,Set<String> keys){if(value==null||!value.isObject()||value.size()!=keys.size())throw invalid();value.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static JsonNode canonical(JsonNode node){if(node.isArray()){var result=INPUT.createArrayNode();node.forEach(v->result.add(canonical(v)));return result;}if(!node.isObject())return node;var result=INPUT.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);for(String key:keys)result.set(key,canonical(node.get(key)));return result;}
    private static String hash(JsonNode input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(INPUT.writeValueAsBytes(canonical(input))));}catch(Exception e){throw new IllegalStateException("Source collection request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","来源集合字段、明确版本、摘要或查询边界无效；不接受租户、季度、计算结果或发布字段覆盖");}
    private static DomainException tooLarge(){return new DomainException(413,"INSPECTION_SOURCE_TECHNICAL_LIMIT","来源集合请求超过技术保护，未截断或创建");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","来源集合未返回当前租户的明确完整结果");}
}
