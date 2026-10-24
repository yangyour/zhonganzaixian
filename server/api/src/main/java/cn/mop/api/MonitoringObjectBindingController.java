package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectBinding.*;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Tenant-scoped mapping publication, raw capture receipts and explicitly configured problem-intake availability. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/monitoring-object-mappings")
public final class MonitoringObjectBindingController {
 private final PlatformRepository platform;
 private final MonitoringObjectBindingRepository mappings;
 private final ObjectMapper json;
 private final boolean enabled;
 private final boolean captureEnabled;
 private final boolean problemIntakeEnabled;
 private final boolean pageIntakeEnabled;

    public MonitoringObjectBindingController(
        PlatformRepository platform,
        MonitoringObjectBindingRepository mappings,
        ObjectMapper json,
        Environment env
    ) {
        this.platform = platform;
        this.mappings = mappings;
        this.json = json
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        enabled = MonitoringEnrollmentConfiguration.flag(env, "MOP_MONITORING_MAPPING_ENABLED");
        captureEnabled = MonitoringEnrollmentConfiguration.flag(env, "MOP_MONITORING_MAPPING_CAPTURE_ENABLED");
        problemIntakeEnabled = MonitoringProblemIntakeController.enabled(env);
        pageIntakeEnabled = MonitoringPageIntakeController.enabled(env);
    }
 private Membership member(HttpServletRequest r,boolean write){var m=MonitoringOnboardingAccess.member(platform,r,write);if(!enabled)throw new DomainException(503,"MONITORING_MAPPING_DISABLED","持久对象映射尚未显式启用");return m;}
 private static Access access(Membership m,HttpServletRequest r){return new Access(m.tenantId(),m.userId(),m.permissionVersion(),ApiResponse.trace(r));}
 private void recheck(Membership m,HttpServletRequest r,boolean write){MonitoringOnboardingAccess.recheck(platform,m,r,write);ProjectSessionValidationFilter.recheckBeforeReturn(r);}
 @GetMapping Object list(HttpServletRequest r){var m=member(r,false);var params=r.getParameterMap();if(!Set.of("asset_id","before","before_id").containsAll(params.keySet())||!params.containsKey("asset_id")||params.containsKey("before")!=params.containsKey("before_id")||params.values().stream().anyMatch(v->v.length!=1))throw invalid();UUID asset=uuid(r.getParameter("asset_id"));MonitoringOnboardingAccess.asset(platform,m,asset.toString());Cursor cursor=null;
  if(params.containsKey("before"))try{Instant time=Instant.parse(r.getParameter("before"));if(time.getNano()%1000000!=0)throw invalid();cursor=new Cursor(time,uuid(r.getParameter("before_id")));}catch(Exception e){throw invalid();}
  var page=mappings.list(access(m,r),asset,cursor);if(page==null||page.items().size()>20)throw unavailable();var ids=new HashSet<UUID>();for(var v:page.items()){bound(m,null,v);if(!ids.add(v.state().document().id()))throw unavailable();}
  var data=ApiResponse.map("items",page.items().stream().map(this::dto).toList(),"next",page.next()==null?null:Map.of("before",page.next().createdAt().toString(),"before_id",page.next().id().toString()),"snapshot_scope","CURRENT_PER_MAPPING_READ","monitoring_applied",false,"dispatch_allowed",false);
  recheck(m,r,false);return ApiResponse.success(data,r);
 }
 @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest r){var m=member(r,false);MonitoringOnboardingAccess.noQuery(r);var view=mappings.get(access(m,r),uuid(id));bound(m,uuid(id),view);var data=dto(view);recheck(m,r,false);return ApiResponse.success(data,r);}
 @PostMapping Object create(@RequestBody JsonNode body,HttpServletRequest r){var m=member(r,true);MonitoringOnboardingAccess.noQuery(r);fields(body,Set.of("asset_id","asset_version","pool_id","object_type","external_id","reason"));var asset=uuid(text(body,"asset_id"));long version=version(body,"asset_version");MonitoringObjectMapping.Key key;
  try{key=new MonitoringObjectMapping.Key(uuid(text(body,"pool_id")),MonitoringObjectMapping.ObjectType.valueOf(text(body,"object_type")),text(body,"external_id"));}catch(Exception e){throw invalid();}String reason=reason(body);
  return write(m,null,body,r,()->{MonitoringOnboardingAccess.asset(platform,m,asset.toString());return mappings.create(access(m,r),asset,version,key,reason);});
 }
 @PostMapping("/{id}/actions") Object change(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){var m=member(r,true);MonitoringOnboardingAccess.noQuery(r);UUID mappingId=uuid(id);Action action;
  try{action=Action.valueOf(text(body,"action"));}catch(Exception e){throw invalid();}if(action==Action.CREATE)throw invalid();
  var keys=new HashSet<>(Set.of("action","expected_version","reason"));if(action==Action.SUBMIT_DRAFT)keys.addAll(Set.of("asset_id","asset_version"));if(action==Action.ACTIVATE)keys.addAll(Set.of("expected_policy_reference","expected_source_definition_sha256"));fields(body,keys);
  long expected=version(body,"expected_version");String reason=reason(body);UUID asset=action==Action.SUBMIT_DRAFT?uuid(text(body,"asset_id")):null;Long assetVersion=action==Action.SUBMIT_DRAFT?version(body,"asset_version"):null;
  String policy=action==Action.ACTIVATE?text(body,"expected_policy_reference"):null,definition=action==Action.ACTIVATE?text(body,"expected_source_definition_sha256"):null;if(action==Action.ACTIVATE&&(!policy.matches("[A-Za-z0-9._:-]{1,256}")||!definition.matches("[a-f0-9]{64}")))throw invalid();if(action==Action.REVOKE&&reason==null)throw invalid();
  return write(m,mappingId,body,r,()->mappings.change(access(m,r),mappingId,action,expected,asset,assetVersion,policy,definition,reason));
 }
 @PostMapping("/{id}/captures") Object capture(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){
  var m=member(r,true);MonitoringOnboardingAccess.noQuery(r);UUID mappingId=uuid(id);
  fields(body,Set.of("expected_version","source_event_id","expected_source_definition_sha256"));long expected=version(body,"expected_version");String eventId=text(body,"source_event_id"),definition=text(body,"expected_source_definition_sha256");
  if(!cn.mop.adapters.monitoring.ZabbixObjectIdentityReader.numeric(eventId)||!definition.matches("[a-f0-9]{64}"))throw invalid();
  String commandKey=r.getHeader("Idempotency-Key");if(commandKey==null||!commandKey.matches("[A-Za-z0-9._:-]{1,128}")||Collections.list(r.getHeaders("Idempotency-Key")).size()!=1)throw invalid();
  var result=platform.idempotentAuthorized(m.userId(),m.tenantId(),r.getMethod()+" "+ApiResponse.route(r),commandKey,digest(body.toString()),m.permissionVersion(),()->mappings.authorize(access(m,r),mappingId,true),()->{
   if(!captureEnabled)throw new DomainException(503,"MAPPING_CAPTURE_DISABLED","单次来源保存尚未显式启用；未调用来源");
   var receipt=mappings.capture(access(m,r),mappingId,expected,eventId,definition);
   if(receipt==null||!mappingId.equals(receipt.mappingId())||receipt.mappingVersion()!=expected||!eventId.equals(receipt.sourceEventId()))throw unavailable();recheck(m,r,true);
   return ApiResponse.map("mapping_id",receipt.mappingId().toString(),"mapping_version",receipt.mappingVersion(),"source_event_id",receipt.sourceEventId(),"source_phase",receipt.phase().name(),"outcome",receipt.outcome(),"record_id",receipt.recordId().toString(),"first_received_at",receipt.firstReceivedAt().toString(),"source_authorization_status","UNVERIFIED","business_processed",false,"acknowledgement_only",true,"current_mapping_claimed",false,"monitoring_applied",false,"dispatch_allowed",false);
  });recheck(m,r,true);return ApiResponse.success(result,r);
 }
 private Object write(Membership m,UUID id,JsonNode body,HttpServletRequest r,Supplier<Receipt> action){String key=r.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}")||Collections.list(r.getHeaders("Idempotency-Key")).size()!=1)throw invalid();
  Object response=platform.idempotentAuthorized(m.userId(),m.tenantId(),r.getMethod()+" "+ApiResponse.route(r),key,digest(body.toString()),m.permissionVersion(),()->{mappings.authorize(access(m,r),id,true);if(id==null)MonitoringOnboardingAccess.asset(platform,m,text(body,"asset_id"));},()->{var receipt=action.get();if(receipt==null||id!=null&&!id.equals(receipt.id())||receipt.acceptedVersion()<1||receipt.acceptedVersion()>MonitoringObjectBinding.MAX_VERSION)throw unavailable();recheck(m,r,true);return ApiResponse.map("id",receipt.id().toString(),"accepted_version",receipt.acceptedVersion(),"action",receipt.action().name(),"acknowledgement_only",true,"current_mapping_claimed",false,"monitoring_applied",false,"dispatch_allowed",false);});
  recheck(m,r,true);return ApiResponse.success(response,r);
 }

    private Map<String, Object> dto(View v) {
        return ApiResponse.map(
            "state",
            json.convertValue(v.state(), Map.class),
            "availability",
            v.availability(),
            "draft_asset_current",
            v.draftAssetCurrent(),
            "current_actor_can_activate",
            v.currentActorCanActivate(),
            "policy_reference",
            v.policyReference(),
            "source_definition_sha256",
            v.sourceDefinitionSha256(),
            "source_capture_enabled",
            captureEnabled,
            "problem_intake_enabled",
            problemIntakeEnabled,
            "page_intake_enabled",
            pageIntakeEnabled,
            "monitoring_applied",
            false,
            "dispatch_allowed",
            false,
            "device_identity_verified",
            false
        );
    }
 private static void bound(Membership m,UUID id,View v){if(v==null||v.state()==null||!m.tenantId().equals(v.state().document().tenantId().toString())||id!=null&&!id.equals(v.state().document().id()))throw unavailable();}
 private static void fields(JsonNode b,Set<String> keys){if(b==null||!b.isObject()||b.size()!=keys.size())throw invalid();b.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
 private static String text(JsonNode b,String k){if(b==null||!b.path(k).isTextual())throw invalid();return b.path(k).asText();}
 private static String reason(JsonNode b){if(b.path("reason").isNull())return null;String s=text(b,"reason");if(s.isBlank()||s.length()>500)throw invalid();return s;}
 private static long version(JsonNode b,String key){var n=b.get(key);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<1||n.asLong()>MonitoringObjectBinding.MAX_VERSION)throw invalid();return n.asLong();}
 private static UUID uuid(String s){if(!MonitoringPresetRegistry.uuid(s))throw invalid();return UUID.fromString(s);}
 private static String digest(String s){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private static DomainException invalid(){return new DomainException(400,"INVALID_MONITORING_MAPPING","只接受明确对象、资产版本与动作，不接受来源证明或租户覆盖");}
 private static DomainException unavailable(){return new DomainException(503,"MONITORING_MAPPING_UNAVAILABLE","映射读取或回执范围未通过核对");}
}
