package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Internal manual draft facts only; this route cannot configure IP, CMDB or monitoring. */
@RestController @RequestMapping("/api/v1/assets/{id}/manual-metadata") @Profile("remote_test")
public final class ManualAssetMetadataController {
    static final int MAX_BODY_BYTES=32768;
    private static final long MAX_VERSION=9007199254740990L;
    private static final Set<String> FIELDS=Set.of("expected_version","name","type","site_id","brand","model","serial_number","location","owner_id","business_description","purchase_date","warranty_expiry","overdue_service_date","collection_method_note","note");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(12000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final ManualAssetMetadataRepository metadata;private final ObjectMapper mapper;
    public ManualAssetMetadataController(PlatformRepository platform,ManualAssetMetadataRepository metadata,ObjectMapper mapper){this.platform=platform;this.metadata=metadata;this.mapper=mapper;}
    @GetMapping Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request);query(request,Set.of());return success(dto(metadata.current(a,uuid(id))),request);}
    @GetMapping("/references") Object references(@PathVariable String id,HttpServletRequest request){
        Access a=access(request);Map<String,String[]> params=request.getParameterMap();if(params.size()>1||params.keySet().stream().anyMatch(key->!key.equals("q"))||params.values().stream().anyMatch(values->values.length!=1))throw invalid();
        String q=params.containsKey("q")?params.get("q")[0]:"";if(q.codePointCount(0,q.length())>100||q.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE))throw invalid();
        References choices=metadata.references(a,uuid(id),q);return success(map("sites",choices.sites(),"owners",choices.owners(),"sites_truncated",choices.sitesTruncated(),"owners_truncated",choices.ownersTruncated(),"query",q,"limit_per_kind",100,"scope","CURRENT_TENANT_ACTIVE_REFERENCES","source","postgresql","reference_validation","RECHECKED_ON_WRITE","default_owner_id",null),request);
    }
    @GetMapping("/history") Object history(@PathVariable String id,HttpServletRequest request){
        Access a=access(request);Map<String,String> query=query(request,Set.of("page_size","before_version"));int size=pageSize(query);Long before=query.containsKey("before_version")?queryVersion(query.get("before_version")):null;if(before!=null&&before<2)throw invalid();
        List<Revision> all=metadata.history(a,uuid(id),size+1,before);boolean more=all.size()>size;List<Revision> items=all.subList(0,Math.min(size,all.size()));
        return success(map("items",items.stream().map(ManualAssetMetadataController::revisionDto).toList(),"has_more",more,"next_before_version",more?items.get(items.size()-1).assetVersion():null,"source","postgresql","scope","INTERNAL_MANUAL_DRAFT","page_size_limit",100),request);
    }
    @PatchMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object updateRequest(@PathVariable String id,HttpServletRequest request){access(request);return write(id,body(request),request);}
    Object write(String id,JsonNode body,HttpServletRequest request){
        Access a=access(request);query(request,Set.of());String asset=uuid(id);
        if(body==null||!body.isObject())throw invalid();Set<String> actual=new HashSet<>();body.fieldNames().forEachRemaining(actual::add);if(!actual.equals(FIELDS))throw invalid();
        JsonNode version=body.get("expected_version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<1||version.longValue()>MAX_VERSION)throw invalid();
        ManualAssetMetadata facts=new ManualAssetMetadata(text(body.get("name")),text(body.get("type")),text(body.get("site_id")),nullable(body,"brand"),nullable(body,"model"),nullable(body,"serial_number"),nullable(body,"location"),nullable(body,"owner_id"),nullable(body,"business_description"),ManualAssetMetadata.parseDate(nullable(body,"purchase_date")),ManualAssetMetadata.parseDate(nullable(body,"warranty_expiry")),ManualAssetMetadata.parseDate(nullable(body,"overdue_service_date")),nullable(body,"collection_method_note"),text(body.get("note")));
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(body),a.permissionVersion(),()->metadata.authorizeCurrentAsset(a,asset),()->dto(metadata.update(a,asset,version.longValue(),facts)));
        return success(result,request);
    }
    static Map<String,Object> dto(Current current){Map<String,Object> value=snapshotDto(current.snapshot());value.putAll(map("recorded_by",current.recordedBy(),"recorded_at",current.recordedAt(),"can_edit",current.editBlockers().isEmpty(),"edit_blockers",current.editBlockers(),"scope","INTERNAL_MANUAL_DRAFT","verification_status",current.snapshot().metadata()==null?null:"SELF_REPORTED","source","postgresql","monitoring_applied",false,"cmdb_applied",false,"management_ip_change_status","CONFIG_PENDING"));return value;}
    static Map<String,Object> snapshotDto(Snapshot snapshot){return map("asset",snapshot.asset(),"metadata",snapshot.metadata(),"metadata_version",snapshot.metadataVersion());}
    static Map<String,Object> revisionDto(Revision revision){return map("asset_version",revision.assetVersion(),"previous_metadata_version",revision.previousMetadataVersion(),"actor_id",revision.actorId(),"recorded_at",revision.recordedAt(),"before",snapshotDto(revision.before()),"metadata",revision.metadata(),"management_ip",revision.managementIp(),"verification_status","SELF_REPORTED","monitoring_applied",false,"cmdb_applied",false);}
    private Access access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope))throw expired();
        String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(ManualAssetMetadataController::expired);if(!member.active())throw expired();
        if(!("msp".equals(member.role())||"padmin".equals(member.role()))||!member.capabilities().contains("assets:write"))throw new DomainException(403,"ACTION_FORBIDDEN","仅开放内部人员的手工草稿资料维护，客户维护范围仍待确认");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    private static String nullable(JsonNode body,String key){JsonNode value=body.get(key);if(value==null||!value.isNull()&&!value.isTextual())throw invalid();return value.isNull()?null:value.textValue();}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1||values[0].length()>64)throw invalid();result.put(key,values[0]);});return result;}
    private static int pageSize(Map<String,String> query){String size=query.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(size))throw invalid();return Integer.parseInt(size);}
    private static long queryVersion(String value){if(!value.matches("[1-9][0-9]{0,15}"))throw invalid();try{long version=Long.parseLong(value);if(version>MAX_VERSION)throw invalid();return version;}catch(NumberFormatException failure){throw invalid();}}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>40)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private String hash(JsonNode body){try{var canonical=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();body.fieldNames().forEachRemaining(keys::add);keys.forEach(key->canonical.set(key,body.get(key)));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));}catch(Exception failure){throw new IllegalStateException("Asset metadata request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","手工草稿资料字段、版本、日期或分页边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
