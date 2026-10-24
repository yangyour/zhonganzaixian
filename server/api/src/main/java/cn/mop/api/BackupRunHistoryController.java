package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.backup.*;
import cn.mop.domain.backup.BackupRunHistoryReader.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
@RestController @Profile("remote_test") @RequestMapping("/api/v1/assets/{assetId}/backup-runs")
public final class BackupRunHistoryController {
 private final PlatformRepository platform;private final BackupRunHistoryReader reader;
 public BackupRunHistoryController(PlatformRepository platform,BackupRunHistoryReader reader){this.platform=platform;this.reader=reader;}
 @ModelAttribute void headers(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
 @GetMapping Object read(@PathVariable String assetId,HttpServletRequest request){var member=member(request);UUID asset=uuid(assetId);var params=request.getParameterMap();if(!Set.of("before_queued_at","before_run_id","page_size").containsAll(params.keySet())||params.values().stream().anyMatch(v->v==null||v.length!=1||v[0]==null||v[0].length()>64)||params.containsKey("before_queued_at")!=params.containsKey("before_run_id"))throw invalid();
  int size=20;Cursor before=null;try{if(params.containsKey("page_size"))size=Integer.parseInt(request.getParameter("page_size"));if(size<1||size>50)throw invalid();if(params.containsKey("before_queued_at"))before=new Cursor(Instant.parse(request.getParameter("before_queued_at")),uuid(request.getParameter("before_run_id")));}catch(Exception bad){throw invalid();}
  var access=new Access(UUID.fromString(member.tenantId()),UUID.fromString(member.userId()),member.permissionVersion());var page=reader.read(access,asset,before,size);
  if(page==null||!page.access().equals(access)||!page.assetId().equals(asset)||page.pageSize()!=size)throw new DomainException(503,"BACKUP_RUN_HISTORY_UNAVAILABLE","备份运行结果与当前授权不符");
  if(before==null&&(page.items().size()!=Math.min(page.totalCount(),size)||(page.next()!=null)!=(page.totalCount()>size)))throw new DomainException(503,"BACKUP_RUN_HISTORY_UNAVAILABLE","完整首页计数和明细不一致");
  if(before!=null&&!page.items().isEmpty()){var first=page.items().get(0);if(first.state().queuedAt().isAfter(before.queuedAt())||first.state().queuedAt().equals(before.queuedAt())&&first.runId().toString().compareTo(before.runId().toString())>=0)throw new DomainException(503,"BACKUP_RUN_HISTORY_UNAVAILABLE","运行历史分页越过原始边界");}
  var data=dto(page);DashboardReadAuthorization.recheck(request,platform,member);return ApiResponse.success(data,request);
 }
 static Map<String,Object> dto(Page page){var items=page.items().stream().map(e->{var state=e.state();return ApiResponse.map("run_id",e.runId().toString(),"asset_id",state.assetId().toString(),"version",state.version(),"state",state.state().name(),"queued_at",state.queuedAt().toString(),"finished_at",state.finishedAt()==null?null:state.finishedAt().toString(),"known_failed_attempt_count",state.knownFailedAttemptCount(),"final_run_failure_count",state.finalRunFailureCount(),"reconciliation_required",state.reconciliationRequired(),"snapshot_sha256",e.snapshotSha256(),"attempts",state.attempts().stream().map(a->ApiResponse.map("number",a.number(),"started_at",a.startedAt().toString(),"observed_at",a.observedAt().toString(),"finished_at",a.finishedAt()==null?null:a.finishedAt().toString(),"outcome",a.outcome().name(),"failure",a.failure()==null?null:a.failure().name())).toList());}).toList();
  return ApiResponse.map("tenant_id",page.access().tenantId().toString(),"actor_id",page.access().actorId().toString(),"permission_version",page.access().permissionVersion(),"asset_id",page.assetId().toString(),"observed_at",page.observedAt().toString(),"page_size",page.pageSize(),"total_count",page.totalCount(),"items",items,"next",page.next()==null?null:ApiResponse.map("before_queued_at",page.next().queuedAt().toString(),"before_run_id",page.next().runId().toString()),"failure_count_semantics",null,"consecutive_failure_count",null,"automatic_ticket_status","CONFIG_PENDING_Q22","automatic_ticket_allowed",false,"failure_ticket_threshold",2,"ticket_deadline_seconds",600,"snapshot_scope","SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT","source","CURRENT_AUTHORIZED_BACKUP_RUN_JOURNAL","snapshot_payload_verified",true,"full_journal_chain_verified",false,"object_storage_accessed",false,"device_execution_authorized",false,"writes_performed",false);
 }
 private Membership member(HttpServletRequest r){if(r.getUserPrincipal()==null||r.getSession(false)==null||!(r.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant))throw expired();String actor=r.getUserPrincipal().getName();if(actor==null||!actor.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!tenant.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw expired();var m=platform.membership(actor,tenant).orElseThrow(BackupRunHistoryController::expired);if(!m.active()||!m.userId().equals(actor)||!m.tenantId().equals(tenant)||m.permissionVersion()<1||m.permissionVersion()>9007199254740991L)throw expired();if(!Capabilities.internal(m.role())||!m.capabilities().contains("assets:read"))throw new DomainException(403,"BACKUP_RUN_HISTORY_FORBIDDEN","资产可见不代表拥有内部运行历史授权");return m;}
 private static UUID uuid(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw invalid();return UUID.fromString(value);}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","只接受当前资产、成对游标及有界页大小");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前备份运行读取会话失效");}
}
