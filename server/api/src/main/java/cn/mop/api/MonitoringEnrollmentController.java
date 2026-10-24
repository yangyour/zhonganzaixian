package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringEnrollment.*;
import cn.mop.domain.monitoring.MonitoringEnrollmentRepository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@RestController @Profile("remote_test") @RequestMapping("/api/v1/monitoring-onboarding/enrollments")
public final class MonitoringEnrollmentController {
 private final PlatformRepository platform;private final MonitoringEnrollmentRepository tasks;private final MonitoringOnboardingPlanner planner;private final MonitoringEnrollmentProbe probe;private final ObjectMapper json;private final boolean enabled;
 public MonitoringEnrollmentController(PlatformRepository platform,MonitoringEnrollmentRepository tasks,MonitoringOnboardingPlanner planner,MonitoringEnrollmentProbe probe,ObjectMapper json,Environment env){this.platform=platform;this.tasks=tasks;this.planner=planner;this.probe=probe;this.json=json.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);enabled=MonitoringEnrollmentConfiguration.flag(env,"MOP_MONITORING_ENROLLMENT_ENABLED");}
 private Membership access(HttpServletRequest r,boolean write){var member=MonitoringOnboardingAccess.member(platform,r,write);if(!enabled)throw new DomainException(503,"CONFIG_PENDING","持久监控登记尚未显式启用；现有静态目标配置不受影响");return member;}
 private Access access(Membership m,HttpServletRequest r){return new Access(m.userId(),m.tenantId(),m.permissionVersion(),ApiResponse.trace(r));}
 @GetMapping Object list(HttpServletRequest r){var m=access(r,false);var keys=r.getParameterMap().keySet();if(!Set.of("before","before_id").containsAll(keys)||keys.size()!=0&&keys.size()!=2||r.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw invalid();Instant before=null;String id=null;
  if(!keys.isEmpty())try{before=Instant.parse(r.getParameter("before"));id=uuid(r.getParameter("before_id"));if(before.getNano()%1000000!=0)throw invalid();}catch(Exception e){throw invalid();}
  var rows=tasks.list(access(m,r),21,before,id);if(rows.size()>21)throw unavailable();var ids=new HashSet<UUID>();for(var row:rows){bound(m,null,row);if(!ids.add(row.document().id()))throw unavailable();}var page=rows.stream().limit(20).toList();var data=ApiResponse.map("items",page.stream().map(this::dto).toList(),"next",rows.size()>20?Map.of("before",page.get(19).document().createdAt().toString(),"before_id",page.get(19).document().id().toString()):null,"external_configuration_applied",false,"continuous_collection_verified",false);
  MonitoringOnboardingAccess.recheck(platform,m,r,false);return ApiResponse.success(data,r);
 }
 @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest r){var m=access(r,false);MonitoringOnboardingAccess.noQuery(r);var state=tasks.get(access(m,r),uuid(id));bound(m,id,state);var data=dto(state);MonitoringOnboardingAccess.recheck(platform,m,r,false);return ApiResponse.success(data,r);}
 @PostMapping Object create(@RequestBody JsonNode body,HttpServletRequest r){var m=access(r,true);MonitoringOnboardingAccess.noQuery(r);fields(body,Set.of("plan_request","expected_configuration_sha256","expected_catalog_sha256"));String expected=hashText(body,"expected_configuration_sha256"),catalog=hashText(body,"expected_catalog_sha256");
  return write(m,null,body,r,()->{var bindings=new LinkedHashMap<String,AssetSnapshot>();var plan=planner.plan(m.tenantId(),body.get("plan_request"),id->{var asset=MonitoringOnboardingAccess.asset(platform,m,id);bindings.put(id,new AssetSnapshot(UUID.fromString(id),asset.version(),asset.managementIp()));return asset;});
   if(!Boolean.TRUE.equals(plan.get("valid"))||!expected.equals(plan.get("configuration_sha256"))||!catalog.equals(plan.get("catalog_sha256")))throw new DomainException(409,"ENROLLMENT_PLAN_CHANGED","计划、目录或当前资产已变化，请重新生成计划");
   String config;try{config=MonitoringPresetRegistry.JSON.writeValueAsString(plan.get("configuration"));}catch(Exception e){throw invalid();}
   var state=tasks.create(access(m,r),new Creation(catalog,expected,config,List.copyOf(bindings.values())));bound(m,null,state);return dto(state);
  });
 }
 @PostMapping("/{id}/actions") Object change(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){var m=access(r,true);MonitoringOnboardingAccess.noQuery(r);uuid(id);fields(body,Set.of("action","expected_version","reason"));String action=text(body,"action");if(!Set.of("APPROVE","REGISTER_DISABLED","CANCEL").contains(action))throw invalid();long expected=version(body);String reason=body.get("reason").isNull()?null:text(body,"reason");if(action.equals("CANCEL")?(reason==null||reason.isBlank()||reason.length()>1000):reason!=null)throw invalid();
  return write(m,id,body,r,()->{var state=tasks.change(access(m,r),id,expected,action,reason);bound(m,id,state);return dto(state);});
 }
 @PostMapping("/{id}/probe") Object test(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest r){var m=access(r,true);MonitoringOnboardingAccess.noQuery(r);uuid(id);fields(body,Set.of("target_id","configuration_sha256","expected_version"));String targetId=uuid(text(body,"target_id")),hash=hashText(body,"configuration_sha256");long expected=version(body);
  return write(m,id,body,r,()->{
   var state=tasks.authorizeProbe(access(m,r),id,expected);bound(m,id,state);var registry=probe.registry(state.document().configuration());var target=registry.find(m.tenantId(),targetId).orElseThrow(MonitoringOnboardingAccess::notFound);if(!hash.equals(target.hash()))throw new DomainException(409,"VERSION_CONFLICT","目标配置已变化");
   var result=probe.probe(registry,target);MonitoringOnboardingAccess.recheck(platform,m,r,true);
   var observation=observation(result,target);var after=tasks.recordProbe(access(m,r),id,expected,observation);bound(m,id,after);return dto(after);
  });
 }
 private ProbeResult observation(Map<String,Object> r,MonitoringPresetRegistry.Target t){
  if(r==null||!t.id().equals(r.get("target_id"))||!t.hash().equals(r.get("configuration_sha256"))||!Boolean.FALSE.equals(r.get("continuous_collection_verified"))||!Boolean.FALSE.equals(r.get("device_identity_verified"))||!Boolean.FALSE.equals(r.get("health_verified")))throw unavailable();
  try{var count=r.get("sample_count");if(count!=null&&!(count instanceof Integer))throw unavailable();@SuppressWarnings("unchecked")var metrics=(List<String>)r.get("matched_metrics");return new ProbeResult(UUID.fromString(t.id()),t.hash(),(String)r.get("probe_status"),(Integer)count,metrics,(String)r.get("reason"));}catch(Exception invalid){throw unavailable();}
 }
 private Object write(Membership m,String id,JsonNode body,HttpServletRequest r,Supplier<Object> action){String key=r.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}")||Collections.list(r.getHeaders("Idempotency-Key")).size()!=1)throw invalid();return ApiResponse.success(platform.idempotentAuthorized(m.userId(),m.tenantId(),r.getMethod()+" "+ApiResponse.route(r),key,digest(body.toString()),m.permissionVersion(),()->tasks.authorize(access(m,r),id,true),action),r);}
 private Map<String,Object> dto(State state){
  boolean current=planner.catalogSha256().equals(state.document().catalogSha256());var targets=new ArrayList<Map<String,Object>>();
  if(current){var registry=probe.registry(state.document().configuration());for(var t:registry.forTenant(state.document().tenantId().toString()))targets.add(ApiResponse.map("target_id",t.id(),"configuration_sha256",t.hash(),"configuration",t.configuration(),"test_authorized",state.phase()==Phase.REGISTERED_DISABLED&&probe.allowed(registry,t)));}
  else try{var raw=MonitoringPresetRegistry.JSON.readTree(state.document().configuration());for(var row:raw.path("targets")){
   if(!state.document().tenantId().toString().equals(row.path("tenant_id").asText())||!row.path("enabled").isBoolean()||row.path("enabled").asBoolean())throw unavailable();
   var config=Map.of("version",1,"targets",List.of(MonitoringPresetRegistry.JSON.convertValue(row,Map.class)));var canonical=MonitoringPresetRegistry.JSON.writeValueAsString(config);
   targets.add(ApiResponse.map("target_id",row.path("id").asText(),"configuration_sha256",digest(canonical),"configuration",config,"test_authorized",false));
  }}catch(Exception invalid){throw unavailable();}
  return ApiResponse.map("state",json.convertValue(state,Map.class),"targets",targets,"catalog_current",current,"external_configuration_applied",false,"continuous_collection_verified",false,"device_identity_verified",false,"health_verified",false);
 }

 private static void bound(Membership m,String id,State s){if(s==null||!m.tenantId().equals(s.document().tenantId().toString())||id!=null&&!id.equals(s.document().id().toString()))throw unavailable();}
 private static void fields(JsonNode b,Set<String> keys){if(b==null||!b.isObject()||b.size()!=keys.size())throw invalid();b.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
 private static String text(JsonNode b,String key){if(!b.path(key).isTextual())throw invalid();return b.path(key).asText();}
 private static String hashText(JsonNode b,String key){String s=text(b,key);if(!s.matches("[a-f0-9]{64}"))throw invalid();return s;}
 private static long version(JsonNode b){var n=b.get("expected_version");if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<1||n.asLong()>=9007199254740990L)throw invalid();return n.asLong();}
 private static String uuid(String s){if(!MonitoringPresetRegistry.uuid(s))throw invalid();return s;}
 private static String digest(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","监控登记只接受计划来源、明确版本及动作，不接受凭据或扫描回执覆盖");}
 private static DomainException unavailable(){return new DomainException(503,"MONITORING_ENROLLMENT_UNAVAILABLE","监控登记回执范围或来源未通过核对");}
}
