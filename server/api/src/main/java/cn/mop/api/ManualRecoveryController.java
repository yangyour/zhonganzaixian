package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.manualincident.*;
import cn.mop.domain.manualincident.ManualRecoveryRepository.*;
import cn.mop.domain.worklog.ManualWorklog;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Current human confirmation is separate from the immutable original registration and its original receipt. */
@RestController @RequestMapping("/api/v1/manual-incidents/{incidentId}/recovery") @Profile("remote_test")
public final class ManualRecoveryController {
    static final int MAX_BODY_BYTES=32768;
    private static final Set<String> FIELDS=Set.of("expected_version","note","reported_recovered_at");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(8000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final ManualRecoveryRepository recoveries;private final ObjectMapper mapper;
    public ManualRecoveryController(PlatformRepository platform,ManualRecoveryRepository recoveries,ObjectMapper mapper){this.platform=platform;this.recoveries=recoveries;this.mapper=mapper;}
    @GetMapping Object get(@PathVariable String incidentId,HttpServletRequest request){Access a=access(request,false);noQuery(request);return success(dto(recoveries.get(a,uuid(incidentId))),request);}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object confirm(@PathVariable String incidentId,HttpServletRequest request){access(request,true);return write(incidentId,body(request),request);}
    Object write(String incidentId,JsonNode input,HttpServletRequest request){
        Access a=access(request,true);noQuery(request);String id=uuid(incidentId);if(input==null||!input.isObject()||input.size()!=FIELDS.size())throw invalid();input.fieldNames().forEachRemaining(key->{if(!FIELDS.contains(key))throw invalid();});
        JsonNode version=input.get("expected_version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<0||version.longValue()>1)throw invalid();
        ManualRecoveryConfirmation value=new ManualRecoveryConfirmation(text(input.get("note")),input.get("reported_recovered_at").isNull()?null:ManualWorklog.parseInstant(text(input.get("reported_recovered_at"))));
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(input),a.permissionVersion(),()->recoveries.authorizeCurrentIncident(a,id),()->dto(new Current(id,a.tenantId(),1,recoveries.confirm(a,id,version.longValue(),value))));return success(result,request);
    }
    static Map<String,Object> dto(Current value){return map("incident_id",value.incidentId(),"tenant_id",value.tenantId(),"version",value.version(),"manual_recovery_status",value.manualRecoveryStatus(),"confirmation",value.confirmation()==null?null:confirmationDto(value.confirmation()),"source","postgresql","scope","CURRENT_TENANT_INTERNAL_HUMAN_CONFIRMATION");}
    private static Map<String,Object> confirmationDto(Confirmation entry){ManualRecoveryConfirmation value=entry.value();return map("id",entry.id(),"tenant_id",entry.tenantId(),"incident_id",entry.incidentId(),"confirmed_by",entry.actorId(),"note",value.note(),"note_sha256",value.noteSha256(),"reported_recovered_at",value.reportedRecoveredAt(),"recorded_at",entry.recordedAt(),"manual_recovery_status",value.manualRecoveryStatus(),"verification_status",value.verificationStatus(),"record_source",value.recordSource(),"monitor_state",value.monitorState(),"disposition_state",value.dispositionState(),"monitoring_recovery_applicable",value.monitoringRecoveryApplicable(),"ticket_state_changed",value.ticketStateChanged(),"sla_changed",value.slaChanged(),"immutable",true);}
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(ManualRecoveryController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains(write?"manual_recovery:confirm":"manual_recovery:read"))throw new DomainException(403,"ACTION_FORBIDDEN","人工恢复确认仅向当前租户获准内部人员开放");return new Access(actor,scope,member.permissionVersion(),trace(request));
    }
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var p=INPUT.getFactory().createParser(bytes)){int tokens=0;while(p.nextToken()!=null)if(++tokens>16)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static void noQuery(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw invalid();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private String hash(JsonNode input){try{var ordered=mapper.createObjectNode();new TreeSet<>(FIELDS).forEach(key->ordered.set(key,input.get(key)));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(ordered)));}catch(Exception failure){throw new IllegalStateException("Human confirmation request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","人工恢复仅接受明确说明、可空实际恢复时间及所见确认版本；身份和状态不可覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
