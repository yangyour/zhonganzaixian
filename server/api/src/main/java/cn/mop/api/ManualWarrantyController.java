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

/** Dedicated explicit warranty form; updates share the asset metadata version and preserve unrelated facts. */
@RestController @RequestMapping("/api/v1/assets/{id}/manual-metadata/warranty") @Profile("remote_test")
public final class ManualWarrantyController {
    static final int MAX_BODY_BYTES=32768;
    private static final long MAX_VERSION=9007199254740990L;
    private static final Set<String> FIELDS=Set.of("expected_version","warranty_start","warranty_expiry","warranty_supplier","overdue_service_date","note");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(12000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final ManualAssetMetadataRepository metadata;private final ObjectMapper mapper;
    public ManualWarrantyController(PlatformRepository platform,ManualAssetMetadataRepository metadata,ObjectMapper mapper){this.platform=platform;this.metadata=metadata;this.mapper=mapper;}
    @GetMapping Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request);query(request,Set.of());return success(dto(metadata.current(a,uuid(id))),request);}
    @GetMapping("/history") Object history(@PathVariable String id,HttpServletRequest request){
        Access a=access(request);Map<String,String> query=query(request,Set.of("page_size","before_version"));int size=pageSize(query);Long before=query.containsKey("before_version")?queryVersion(query.get("before_version")):null;if(before!=null&&before<2)throw invalid();
        List<Revision> all=metadata.history(a,uuid(id),size+1,before);boolean more=all.size()>size;List<Revision> items=all.subList(0,Math.min(size,all.size()));
        return success(map("items",items.stream().map(ManualWarrantyController::revisionDto).toList(),"has_more",more,"next_before_version",more?items.get(items.size()-1).assetVersion():null,"source","postgresql","scope","INTERNAL_MANUAL_DRAFT","page_size_limit",100),request);
    }
    @PatchMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object updateRequest(@PathVariable String id,HttpServletRequest request){access(request);return write(id,body(request),request);}
    Object write(String id,JsonNode body,HttpServletRequest request){
        Access a=access(request);query(request,Set.of());String asset=uuid(id);
        if(body==null||!body.isObject())throw invalid();Set<String> actual=new HashSet<>();body.fieldNames().forEachRemaining(actual::add);if(!actual.equals(FIELDS))throw invalid();
        JsonNode version=body.get("expected_version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<1||version.longValue()>MAX_VERSION)throw invalid();
        ManualWarrantyUpdate facts=new ManualWarrantyUpdate(new ManualWarrantyDetails(ManualAssetMetadata.parseDate(nullable(body,"warranty_start")),nullable(body,"warranty_supplier")),ManualAssetMetadata.parseDate(nullable(body,"warranty_expiry")),ManualAssetMetadata.parseDate(nullable(body,"overdue_service_date")),text(body.get("note")));
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(body),a.permissionVersion(),()->metadata.authorizeCurrentAsset(a,asset),()->dto(metadata.updateWarranty(a,asset,version.longValue(),facts)));
        return success(result,request);
    }
    static Map<String,Object> dto(Current current){Map<String,Object> value=ManualAssetMetadataController.dto(current);value.putAll(warrantyFields(current.snapshot().warrantyDetails()));return value;}
    static Map<String,Object> snapshotDto(Snapshot snapshot){Map<String,Object> value=ManualAssetMetadataController.snapshotDto(snapshot);value.put("warranty_details",detailsDto(snapshot.warrantyDetails()));return value;}
    static Map<String,Object> revisionDto(Revision revision){Map<String,Object> value=ManualAssetMetadataController.revisionDto(revision);value.put("before",snapshotDto(revision.before()));value.putAll(warrantyFields(revision.warrantyDetails()));return value;}
    private static Map<String,Object> warrantyFields(ManualWarrantyDetails details){return map("warranty_details",detailsDto(details),"warranty_coverage_verified",false,"warranty_supplier_verification","UNVERIFIED");}
    private static Object detailsDto(ManualWarrantyDetails details){return details==null?null:map("warranty_start",details.warrantyStart(),"warranty_supplier",details.warrantySupplier());}
    private Access access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope))throw expired();
        String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(ManualWarrantyController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();
        if(!("msp".equals(member.role())||"padmin".equals(member.role()))||!member.capabilities().contains("assets:write"))throw new DomainException(403,"ACTION_FORBIDDEN","仅开放内部人员的手工草稿资料维护，客户维护范围仍待确认");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    private static String nullable(JsonNode body,String key){JsonNode value=body.get(key);if(value==null||!value.isNull()&&!value.isTextual())throw invalid();return value.isNull()?null:value.textValue();}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1||values[0].length()>64)throw invalid();result.put(key,values[0]);});return result;}
    private static int pageSize(Map<String,String> query){String size=query.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(size))throw invalid();return Integer.parseInt(size);}
    private static long queryVersion(String value){if(!value.matches("[1-9][0-9]{0,15}"))throw invalid();try{long version=Long.parseLong(value);if(version>MAX_VERSION)throw invalid();return version;}catch(NumberFormatException failure){throw invalid();}}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>16)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private String hash(JsonNode body){try{var canonical=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();body.fieldNames().forEachRemaining(keys::add);keys.forEach(key->canonical.set(key,body.get(key)));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));}catch(Exception failure){throw new IllegalStateException("Asset metadata request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","维保维护须明确起始、到期、供应商、超期日、资料备注与所见资产版本；未知值用null，不补默认值");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
