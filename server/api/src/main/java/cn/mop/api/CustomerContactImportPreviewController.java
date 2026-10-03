package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.contact.*;
import cn.mop.domain.contact.CustomerContactImportPreview.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Read-only format diagnostics: no contact query, operation, stored draft or batch write. */
@RestController @RequestMapping("/api/v1/contacts/import-preview") @Profile("remote_test")
public final class CustomerContactImportPreviewController {
    static final int MAX_BODY_BYTES=524288;
    private static final Set<String> FIELDS=Set.of("name","roles","mobile","email","wecom_user_id","preferred_priorities","quiet_window","enabled");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(2048).maxNumberLength(32).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    public CustomerContactImportPreviewController(PlatformRepository platform){this.platform=platform;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object preview(HttpServletRequest request){
        String tenant=access(request);
        if(!request.getParameterMap().isEmpty())throw invalid();
        JsonNode input=body(request);fields(input,Set.of("rows"));JsonNode source=input.get("rows");
        if(!source.isArray()||source.isEmpty()||source.size()>CustomerContactImportPreview.MAX_ROWS)throw invalid();
        List<Row> rows=new ArrayList<>();
        for(JsonNode value:source){try{rows.add(row(value));}catch(DomainException failure){if(!"VALIDATION_ERROR".equals(failure.code))throw failure;rows.add(null);}}
        Result result=CustomerContactImportPreview.preview(rows);
        return success(map("tenant_id",tenant,"scope",result.scope(),"source","EXPLICIT_INPUT","rows",result.rows().stream().map(r->map("row_number",r.rowNumber(),"status",r.status(),"code",r.code(),"contact",r.contact()==null?null:contact(r.contact()),"identical_to_row",r.identicalToRow())).toList(),"valid_rows",result.validRows(),"invalid_rows",result.invalidRows(),"identical_rows",result.identicalRows(),"row_limit",CustomerContactImportPreview.MAX_ROWS,"row_limit_kind","TECHNICAL_REQUEST_BOUND","can_import",false,"tenant_authorization","CURRENT_MEMBERSHIP_VERIFIED","existing_contact_check",result.existingContactCheck(),"last_decision_maker_check",result.lastDecisionMakerCheck(),"notification_routing_status",result.notificationRoutingStatus(),"writes_performed",false,"notification_dispatched",false,"preview_saved",false,"job_created",false),request);
    }
    private static Row row(JsonNode input){
        fields(input,FIELDS);JsonNode enabled=input.get("enabled");if(!enabled.isNull()&&!enabled.isBoolean())throw invalid();
        QuietInput quiet=null;JsonNode q=input.get("quiet_window");if(!q.isNull()){fields(q,Set.of("enabled","start","end"));JsonNode active=q.get("enabled");if(!active.isNull()&&!active.isBoolean())throw invalid();quiet=new QuietInput(active.isNull()?null:active.booleanValue(),nullableText(q.get("start")),nullableText(q.get("end")));}
        return new Row(text(input.get("name")),strings(input.get("roles"),3),nullableText(input.get("mobile")),nullableText(input.get("email")),nullableText(input.get("wecom_user_id")),strings(input.get("preferred_priorities"),4),quiet,enabled.isNull()?null:enabled.booleanValue());
    }
    private static Map<String,Object> contact(CustomerContact value){var q=value.quietWindow();return map("name",value.name(),"roles",value.roles().stream().map(Enum::name).sorted().toList(),"mobile",value.mobile(),"email",value.email(),"wecom_user_id",value.wecomUserId(),"preferred_priorities",value.preferredPriorities()==null?null:value.preferredPriorities().stream().map(Enum::name).sorted().toList(),"quiet_window",q==null?null:map("enabled",q.enabled(),"start",q.start()==null?null:q.start().toString(),"end",q.end()==null?null:q.end().toString()),"enabled",value.enabled(),"quiet_timezone",CustomerContact.DISPLAY_ZONE,"contact_verification",value.contactVerification(),"channel_verification",value.channelVerification(),"notification_routing_status",value.notificationRoutingStatus(),"delivery_eligible",value.deliveryEligible(),"identity_binding_created",false,"notification_dispatched",false,"source","EXPLICIT_INPUT","record_source","EXPLICIT_NEW_CONTACT_INPUT");}
    private String access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object selected=request.getSession(false).getAttribute("mop.tenant_id");if(!(selected instanceof String tenant)||tenant.isBlank())throw expired();var member=platform.membership(request.getUserPrincipal().getName(),tenant).orElseThrow(CustomerContactImportPreviewController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!Capabilities.forRole(member.role()).contains("contacts:write"))throw new DomainException(403,"ACTION_FORBIDDEN","仅当前授权平台管理员和租户管理员可预览联系人导入资料");return tenant;}
    private static List<String> strings(JsonNode value,int maximum){if(value.isNull())return null;if(!value.isArray()||value.size()>maximum)throw invalid();List<String> result=new ArrayList<>();for(var item:value)result.add(nullableText(item));return result;}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private static String nullableText(JsonNode value){return value.isNull()?null:text(value);}
    private static void fields(JsonNode value,Set<String> fields){if(value==null||!value.isObject()||value.size()!=fields.size())throw invalid();value.fieldNames().forEachRemaining(key->{if(!fields.contains(key))throw invalid();});}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>8000)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","联系人格式预览须为1至100行的rows；重复JSON键、超限和范围覆盖拒绝，不写入或自动导入");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
