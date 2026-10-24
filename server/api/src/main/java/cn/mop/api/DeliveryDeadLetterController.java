package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.DeliveryDeadLetterService;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Access;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Explicit local FAILED disposition only. Request/receipt sending and UNKNOWN retries are not exposed. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/notification-deliveries")
public final class DeliveryDeadLetterController {
 private final PlatformRepository platform;private final DeliveryDeadLetterService service;private final ObjectMapper json;
 public DeliveryDeadLetterController(PlatformRepository platform,DeliveryDeadLetterService service,ObjectMapper json){this.platform=platform;this.service=service;this.json=json;}
 @ModelAttribute void headers(HttpServletResponse r){r.setHeader("Cache-Control","no-store");r.setHeader("Referrer-Policy","no-referrer");}
 @PostMapping(value="/{id}/dead-letter",consumes=MediaType.APPLICATION_JSON_VALUE)
 Object dispose(@PathVariable String id,HttpServletRequest r){
  if(r.getUserPrincipal()==null||r.getSession(false)==null||!(r.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant))throw expired();
  String actor=r.getUserPrincipal().getName();UUID tenantId=uuid(tenant),actorId=uuid(actor),delivery=uuid(id);
  var member=platform.membership(actor,tenant).orElseThrow(DeliveryDeadLetterController::expired);
  if(!member.active()||!actor.equals(member.userId())||!tenant.equals(member.tenantId())||member.permissionVersion()<1)throw expired();
  if(!"padmin".equals(member.role())||!member.capabilities().containsAll(Set.of("configurations:write","ticket_notes:read_internal","audit:read")))throw new DomainException(403,"ACTION_FORBIDDEN","当前身份无投递死信人工处置权限");
  if(r.getQueryString()!=null||!r.getParameterMap().isEmpty()||Collections.list(r.getHeaders("Idempotency-Key")).size()!=1)throw invalid();
  UUID command=uuid(r.getHeader("Idempotency-Key"));var body=AssetExportJobController.body(r);var fields=new HashSet<String>();body.fieldNames().forEachRemaining(fields::add);
  if(!body.isObject()||!fields.equals(Set.of("expected_version","reason"))||!body.path("expected_version").isIntegralNumber()||!body.path("expected_version").canConvertToLong()
   ||body.path("expected_version").asLong()<0||body.path("expected_version").asLong()>=12000||!body.path("reason").isTextual()
   ||body.path("reason").asText().isBlank()||body.path("reason").asText().length()>1000)throw invalid();
  long expected=body.path("expected_version").asLong();String reason=body.path("reason").asText();
  var result=json.valueToTree(service.dispose(new Access(tenantId,actorId,member.permissionVersion()),delivery,expected,command,reason,ApiResponse.trace(r)));
  var resultFields=new HashSet<String>();if(result!=null)result.fieldNames().forEachRemaining(resultFields::add);
  if(!resultFields.equals(Set.of("tenant_id","actor_id","delivery_id","resource_pool_id","command_id","applied_version","recorded_status_at_application","reason","applied_at","source","provider_receipt_verified","automatic_send_enabled","fresh_send_authorized","receipt_scope"))
   ||result==null||!result.isObject()||!tenant.equals(result.path("tenant_id").asText())||!actor.equals(result.path("actor_id").asText())
   ||!id.equals(result.path("delivery_id").asText())||!command.toString().equals(result.path("command_id").asText())
   ||!MonitoringPresetRegistry.uuid(result.path("resource_pool_id").asText())||!applicationTime(result.path("applied_at").asText())
   ||!result.path("applied_version").isIntegralNumber()||!result.path("applied_version").canConvertToLong()||result.path("applied_version").asLong(-1)!=expected+1||!reason.equals(result.path("reason").asText())
   ||!"DEAD_LETTER".equals(result.path("recorded_status_at_application").asText())||!"ORIGINAL_COMMAND_NOT_CURRENT_HEAD".equals(result.path("receipt_scope").asText())
   ||!"LOCAL_FAILED_DECLARATION".equals(result.path("source").asText())||!result.path("provider_receipt_verified").isBoolean()||result.path("provider_receipt_verified").asBoolean()
   ||!result.path("automatic_send_enabled").isBoolean()||result.path("automatic_send_enabled").asBoolean()||!result.path("fresh_send_authorized").isBoolean()||result.path("fresh_send_authorized").asBoolean())throw uncertain();
  try{DashboardReadAuthorization.recheck(r,platform,member);}catch(DomainException revoked){throw uncertain();}
  return ApiResponse.success(result,r);
 }
 private static boolean applicationTime(String value){try{var at=java.time.Instant.parse(value);return at.toString().equals(value)&&at.getNano()%1000000==0;}catch(Exception invalid){return false;}}
 private static UUID uuid(String value){if(value==null||!MonitoringPresetRegistry.uuid(value))throw invalid();return UUID.fromString(value);}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","人工处置只接受当前版本、明确原因和唯一原命令");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前通知处置会话已变化");}
 private static DomainException uncertain(){return new DomainException(503,"DELIVERY_DISPOSITION_RESULT_UNCONFIRMED","结果无法在当前身份下确认，请保留原命令；不要改用新命令重复操作");}
}
