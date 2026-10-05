package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.manualincident.*;
import cn.mop.domain.manualincident.ManualIncidentRepository.*;
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

/** Manual-source self-reported registration only. No event disposition, recovery or ticket creation endpoint. */
@RestController @RequestMapping("/api/v1/manual-incidents") @Profile("remote_test")
public final class ManualIncidentController {
    static final int MAX_BODY_BYTES=8192;
    private static final Set<String> FIELDS=Set.of("title","severity","asset_id","expected_asset_version");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(2000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final ManualIncidentRepository incidents;private final ObjectMapper mapper;
    public ManualIncidentController(PlatformRepository platform,ManualIncidentRepository incidents,ObjectMapper mapper){this.platform=platform;this.incidents=incidents;this.mapper=mapper;}
    @GetMapping Object list(HttpServletRequest request){
        Access a=access(request,false);Map<String,String> q=query(request,Set.of("page_size","before_registered_at","before_id"));String sizeValue=q.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(sizeValue))throw invalid();int size=Integer.parseInt(sizeValue);
        if(q.containsKey("before_registered_at")!=q.containsKey("before_id"))throw invalid();Instant before=q.containsKey("before_registered_at")?ManualWorklog.parseInstant(q.get("before_registered_at")):null;String id=q.containsKey("before_id")?uuid(q.get("before_id")):null;
        List<Entry> all=incidents.list(a,size+1,before,id);boolean more=all.size()>size;var items=all.subList(0,Math.min(size,all.size()));var tail=more?items.get(items.size()-1):null;
        return success(map("items",items.stream().map(ManualIncidentController::dto).toList(),"has_more",more,"next_before_registered_at",tail==null?null:tail.registeredAt(),"next_before_id",tail==null?null:tail.id(),"source","postgresql","scope","CURRENT_TENANT_INTERNAL_MANUAL_REGISTRATIONS","page_size_limit",100,"pagination_consistency","CURRENT_REQUEST_SNAPSHOT_ONLY"),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request,false);query(request,Set.of());return success(dto(incidents.get(a,uuid(id))),request);}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object create(HttpServletRequest request){access(request,true);return write(body(request),request);}
    Object write(JsonNode input,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());if(input==null||!input.isObject()||input.size()!=FIELDS.size())throw invalid();input.fieldNames().forEachRemaining(k->{if(!FIELDS.contains(k))throw invalid();});
        JsonNode expected=input.get("expected_asset_version");if(!expected.isIntegralNumber()||!expected.canConvertToLong()||expected.longValue()<1||expected.longValue()>9007199254740991L)throw invalid();
        ManualIncident incident=new ManualIncident(text(input.get("title")),ManualIncident.Severity.fromCode(text(input.get("severity"))),uuid(text(input.get("asset_id"))),expected.longValue());
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(input),a.permissionVersion(),()->incidents.authorizeCurrentAsset(a,incident.assetId()),()->dto(incidents.create(a,incident)));return success(result,request);
    }
    static Map<String,Object> dto(Entry entry){ManualIncident value=entry.incident();return map("id",entry.id(),"tenant_id",entry.tenantId(),"asset_id",value.assetId(),"asset_name_at_registration",entry.assetNameAtRegistration(),"observed_asset_version",value.observedAssetVersion(),"title",value.title(),"severity",value.severity().code(),"severity_label",value.severity().label(),"registered_by",entry.actorId(),"registered_at",entry.registeredAt(),"source_state",value.sourceState(),"registration_status",value.registrationStatus(),"verification_status",value.verificationStatus(),"monitor_state",value.monitorState(),"disposition_state",value.dispositionState(),"ticket_priority",value.ticketPriority(),"observed_at",value.observedAt(),"recovered_at",value.recoveredAt(),"manual_recovery_status",value.manualRecoveryStatus(),"monitoring_recovery_applicable",value.monitoringRecoveryApplicable(),"ticket_automatically_created",value.ticketAutomaticallyCreated(),"source","postgresql","record_source","MANUAL_INCIDENT_REGISTRATION","immutable",true);}
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(ManualIncidentController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains(write?"manual_incidents:write":"manual_incidents:read"))throw new DomainException(403,"ACTION_FORBIDDEN","手工来源登记仅向当前租户获准内部人员开放");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var p=INPUT.getFactory().createParser(bytes)){int tokens=0;while(p.nextToken()!=null)if(++tokens>20)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v.length!=1||v[0].length()>64)throw invalid();result.put(k,v[0]);});return result;}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private String hash(JsonNode input){try{var ordered=mapper.createObjectNode();new TreeSet<>(FIELDS).forEach(key->ordered.set(key,input.get(key)));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(ordered)));}catch(Exception failure){throw new IllegalStateException("Manual registration request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","手工登记或分页输入无效；仅接受显式标题、级别、资产与所见版本");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
