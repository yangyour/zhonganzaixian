package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.dashboard.AssetReachability;
import cn.mop.domain.dashboard.AssetReachability.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.success;
@RestController @Profile("remote_test")
public final class AssetReachabilityController {
 private final PlatformRepository platform;private final Reader reader;private final NetworkMonitoringService network;private final ObjectMapper mapper;
 public AssetReachabilityController(PlatformRepository platform,Reader reader,NetworkMonitoringService network,ObjectMapper mapper){this.platform=platform;this.reader=reader;this.network=network;this.mapper=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
 @GetMapping("/api/v1/dashboard/asset-reachability") Object read(HttpServletRequest request,HttpServletResponse response){
  response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
  if(!request.getParameterMap().isEmpty())throw new DomainException(400,"VALIDATION_ERROR","统计范围由当前租户完整台账确定");
  if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
  var selected=request.getSession(false).getAttribute("mop.tenant_id");var actor=request.getUserPrincipal().getName();if(!(selected instanceof String tenant)||!NetworkMonitoringService.uuid(tenant)||!NetworkMonitoringService.uuid(actor))throw expired();
  var member=platform.membership(actor,tenant).orElseThrow(AssetReachabilityController::expired);
  if(!member.active()||!member.userId().equals(actor)||!member.tenantId().equals(tenant))throw expired();
  if(!member.capabilities().containsAll(List.of("dashboard:read","assets:read")))throw new DomainException(403,"ACTION_FORBIDDEN","缺少首页或资产读取权限");
  var access=new Access(UUID.fromString(tenant),UUID.fromString(actor),member.permissionVersion());
  long started=System.nanoTime();var before=reader.read(access);check(before,access);var samples=new HashMap<UUID,Sample>();
  if(network.enabled())for(var binding:network.bindings(tenant)){
    if(System.nanoTime()-started>30_000_000_000L)throw unavailable();
    var asset=before.assets().stream().filter(a->a.id().toString().equals(binding.assetId())&&a.managementIp().equals(binding.ip())).findFirst().orElseThrow(AssetReachabilityController::unavailable);
    var raw=network.summary(binding,asset.name());var metric=(Map<?,?>)((Map<?,?>)raw.get("metrics")).get("icmp");
    var sample=new Sample((String)metric.get("state"),(String)metric.get("value"),metric.get("recorded_at")==null?null:Instant.parse((String)metric.get("recorded_at")));
    if(samples.put(asset.id(),sample)!=null)throw unavailable();
  }
  var after=reader.read(access);check(after,access);
  if(!before.assets().equals(after.assets())||after.observedAt().isBefore(before.observedAt())||System.nanoTime()-started>30_000_000_000L)throw unavailable();
  var result=AssetReachability.evaluate(after,samples,network.enabled());
  var dto=mapper.convertValue(result,new TypeReference<Map<String,Object>>(){});
  dto.put("metric_version",AssetReachability.VERSION);dto.put("source","POSTGRESQL_LEDGER_AND_EXPLICIT_ZABBIX_ICMP");dto.put("freshness_seconds",60);dto.put("percentage_basis","ALL_REGISTERED_ASSETS_ONLY_WHEN_NO_UNKNOWN");dto.put("device_health_claimed",false);dto.put("writes_performed",false);
  DashboardReadAuthorization.recheck(request,platform,member);return success(dto,request);
 }
 private static void check(Inventory inventory,Access access){if(inventory==null||!inventory.access().equals(access))throw unavailable();}
 private static DomainException unavailable(){return new DomainException(503,"ASSET_REACHABILITY_UNAVAILABLE","台账或监控映射发生变化，未返回过期或部分统计");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","会话或成员权限已失效");}
}
