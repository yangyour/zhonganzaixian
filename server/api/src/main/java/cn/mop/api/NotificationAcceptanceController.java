package cn.mop.api;
import cn.mop.domain.*;import cn.mop.domain.dashboard.NotificationAcceptance;import cn.mop.domain.dashboard.NotificationAcceptance.*;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.servlet.http.*;import org.springframework.context.annotation.Profile;import org.springframework.web.bind.annotation.*;import java.util.*;
import static cn.mop.api.ApiResponse.success;
@RestController @Profile("remote_test")
public final class NotificationAcceptanceController {
 private final PlatformRepository platform;private final Reader reader;private final ObjectMapper mapper;
 public NotificationAcceptanceController(PlatformRepository platform,Reader reader,ObjectMapper mapper){this.platform=platform;this.reader=reader;this.mapper=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
 @GetMapping("/api/v1/dashboard/notification-acceptance") Object read(HttpServletRequest request,HttpServletResponse response){
  response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
  if(!request.getParameterMap().isEmpty())throw new DomainException(400,"VALIDATION_ERROR","只读取当前租户近24小时创建的业务通知");
  if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();var selected=request.getSession(false).getAttribute("mop.tenant_id");var actor=request.getUserPrincipal().getName();if(!(selected instanceof String tenant)||!NetworkMonitoringService.uuid(tenant)||!NetworkMonitoringService.uuid(actor))throw expired();
  var member=platform.membership(actor,tenant).orElseThrow(NotificationAcceptanceController::expired);
  if(!member.active()||!member.userId().equals(actor)||!member.tenantId().equals(tenant))throw expired();
  if(!Set.of("msp","padmin").contains(member.role())||!member.capabilities().containsAll(List.of("dashboard:read","tickets:read","ticket_notes:read_internal")))throw new DomainException(403,"ACTION_FORBIDDEN","缺少内部通知统计读取权限");
  var result=reader.read(new Access(UUID.fromString(tenant),UUID.fromString(actor),member.permissionVersion()));
  if(result==null||!result.tenantId().toString().equals(tenant)||!result.actorId().toString().equals(actor)||result.permissionVersion()!=member.permissionVersion())throw new DomainException(503,"NOTIFICATION_ACCEPTANCE_UNAVAILABLE","通知统计授权范围不一致");
  var dto=mapper.convertValue(result,new TypeReference<Map<String,Object>>(){});dto.put("metric_version",NotificationAcceptance.VERSION);dto.put("source","POSTGRESQL_EMAIL_WECOM_SUBMISSION_LEDGER");dto.put("population_basis","BUSINESS_NOTIFICATIONS_CREATED_LAST_24H");dto.put("verified_delivered",null);dto.put("delivery_rate_pct",null);dto.put("delivery_rate_below_95",null);dto.put("delivery_verification_status","AUTHENTICATED_DELIVERY_SOURCE_NOT_CONNECTED");dto.put("unverified_declarations_counted_as_delivered",false);dto.put("writes_performed",false);
  DashboardReadAuthorization.recheck(request,platform,member);return success(dto,request);
 }
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","会话或成员权限已失效");}
}
