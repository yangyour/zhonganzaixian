package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.operations.*;
import cn.mop.domain.operations.EventOperationalMetricsReader.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

@RestController @Profile("remote_test") @RequestMapping("/api/v1/metrics/monitoring-events")
public final class EventOperationalMetricsController {
 private final PlatformRepository platform;private final EventOperationalMetricsReader reader;
 public EventOperationalMetricsController(PlatformRepository platform,EventOperationalMetricsReader reader){this.platform=platform;this.reader=reader;}
 @GetMapping Object read(HttpServletRequest request){
  var member=member(request);if(request.getQueryString()!=null&&request.getQueryString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2048)throw invalid();var keys=request.getParameterMap().keySet();if(!Set.of("window","view","before","before_id").containsAll(keys)||request.getParameterMap().values().stream().anyMatch(v->v==null||v.length!=1||v[0]==null||v[0].length()>64)||keys.contains("before")!=keys.contains("before_id"))throw invalid();
  Window window;View view;Cursor before=null;try{window=Window.valueOf(Optional.ofNullable(request.getParameter("window")).orElse("HOURS_24"));view=View.valueOf(Optional.ofNullable(request.getParameter("view")).orElse("RECENT_PROBLEMS"));
   if(keys.contains("before")){String id=request.getParameter("before_id");if(!uuid(id))throw invalid();Instant time=Instant.parse(request.getParameter("before"));if(time.getNano()%1000000!=0)throw invalid();before=new Cursor(time,UUID.fromString(id));}
  }catch(Exception invalid){throw invalid();}
  var result=reader.read(new Access(member.userId(),member.tenantId(),member.permissionVersion()),window,view,before);
  if(result==null||!result.actorId().toString().equals(member.userId())||!result.tenantId().toString().equals(member.tenantId())||result.permissionVersion()!=member.permissionVersion()||result.window()!=window||result.view()!=view)throw new DomainException(503,"EVENT_METRICS_UNAVAILABLE","事件指标结果与授权范围不符");
  DashboardReadAuthorization.recheck(request,platform,member);return ApiResponse.success(dto(result),request);
 }
 private Membership member(HttpServletRequest r){if(r.getUserPrincipal()==null||r.getSession(false)==null||!(r.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)||!uuid(tenant)||!uuid(r.getUserPrincipal().getName()))throw expired();String actor=r.getUserPrincipal().getName();var m=platform.membership(actor,tenant).orElseThrow(EventOperationalMetricsController::expired);if(!m.active()||!m.userId().equals(actor)||!m.tenantId().equals(tenant)||m.permissionVersion()<1||m.permissionVersion()>EventOperationalMetricsReader.MAX_SAFE)throw expired();if(!Capabilities.internal(m.role())||!m.capabilities().containsAll(List.of("dashboard:read","tickets:read")))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能读取内部事件指标");return m;}
 static Map<String,Object> dto(Snapshot s){var counts=new LinkedHashMap<String,Object>();for(var severity:Severity.values())counts.put(severity.name(),s.currentProblems().get(severity));var m=s.mtta();var stats=m.statistics();
  var latency=ApiResponse.map("status",m.status(),"population_count",m.populationCount(),"sample_count",m.sampleCount(),"unacknowledged_count",m.unacknowledgedCount(),"invalid_count",m.invalidCount(),"mean_millis",stats==null||stats.meanMillis()==null?null:stats.meanMillis().toPlainString(),"median_millis",stats==null||stats.medianMillis()==null?null:stats.medianMillis().toPlainString(),"p95_millis",stats==null||stats.p95Millis()==null?null:stats.p95Millis().toString(),"target_millis",300000,"target_breached",stats==null||stats.meanMillis()==null?null:stats.meanMillis().compareTo(BigDecimal.valueOf(300000))>0,"source_manifest_sha256",m.sourceManifestSha256(),"population_basis","PROBLEM_AT_IN_HALF_OPEN_WINDOW_ALL_SUPPRESSION_STATES","timestamp_source","RECORDED_FIRST_ACK_AT_NOT_TICKET_ACCEPTANCE","maximum_samples",OperationalLatencyStatistics.MAX_SAMPLES);
  var items=s.items().stream().map(v->ApiResponse.map("event_id",v.id().toString(),"asset_id",v.assetId().toString(),"version",v.version(),"rule_name",v.ruleName(),"severity",v.severity().name(),"monitor_state",v.monitorState(),"handling_state",v.handlingState(),"suppression_state",v.suppressionState(),"problem_at",v.problemAt().toString(),"first_ack_at",v.firstAckAt()==null?null:v.firstAckAt().toString(),"updated_at",v.updatedAt().toString(),"acknowledgement_state",v.acknowledgementState(s.observedAt()),"elapsed_millis",v.elapsedMillis(s.observedAt())==null?null:v.elapsedMillis(s.observedAt()).toString())).toList();
  return ApiResponse.map("tenant_id",s.tenantId().toString(),"actor_id",s.actorId().toString(),"permission_version",s.permissionVersion(),"observed_at",s.observedAt().toString(),"timezone","Asia/Shanghai","window",s.window().name(),"period",ApiResponse.map("from",s.from().toString(),"to",s.observedAt().toString()),"metric_version",EventOperationalMetrics.VERSION,"statistics_version",OperationalLatencyStatistics.VERSION,"current_problems",ApiResponse.map("total",s.currentProblemCount(),"by_severity",counts,"population_basis","PROBLEM_AND_SUPPRESSION_NONE"),"mtta",latency,"view",s.view().name(),"items",items,"next",s.next()==null?null:ApiResponse.map("before",s.next().problemAt().toString(),"before_id",s.next().id().toString()),"source","CURRENT_AUTHORIZED_POSTGRESQL_EVENT_PROJECTION","collection_freshness_verified",false,"clock_accuracy_verified",false,"frozen_report",false,"writes_performed",false);
 }
 private static boolean uuid(String s){return s!=null&&s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","事件指标只接受明确窗口、视图及单个分页游标");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","事件指标当前会话或权限已变化");}
}
