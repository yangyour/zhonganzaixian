package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.domain.backup.BackupRunState;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Historical metadata only. Asset visibility does not grant enumeration; the explicitly bound query must authorize it independently. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/assets/{assetId}/backup-versions")
public final class BackupVersionInventoryController {
 private static final long MAX=9007199254740991L;
 private static final Instant MIN_TIME=Instant.parse("0001-01-01T00:00:00Z"),MAX_TIME=Instant.parse("9999-12-31T23:59:59.999Z");
 private final PlatformRepository platform;private final ObjectProvider<BackupVersionInventoryQuery> queries;
 private record SessionAccess(Access value,Membership membership){}
 public BackupVersionInventoryController(PlatformRepository platform,ObjectProvider<BackupVersionInventoryQuery> queries){this.platform=Objects.requireNonNull(platform);this.queries=Objects.requireNonNull(queries);}
 @ModelAttribute void privateHeaders(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
 @GetMapping Object list(@PathVariable String assetId,HttpServletRequest request){
  SessionAccess session=access(request);Access a=session.value();var scope=new InventoryScope(a.tenantId(),uuid(assetId));
  var q=query(request);int limit=size(q.getOrDefault("page_size","15"));
  boolean time=q.containsKey("before_registered_at"),id=q.containsKey("before_version_id");if(time!=id)throw invalid();
  InventoryCursor before=time?new InventoryCursor(scope,parseTime(q.get("before_registered_at")),uuid(q.get("before_version_id"))):null;
  BackupVersionInventoryQuery reader;
  try{reader=queries.getIfUnique();}catch(RuntimeException failure){throw unavailable();}
  if(reader==null)throw new DomainException(503,"BACKUP_VERSION_INVENTORY_NOT_CONFIGURED","版本列表的独立授权服务尚未装配；没有使用草稿权限代替，也未授予下载");
  InventoryPage result;
  try{result=reader.inventory(a,scope,before,limit);}catch(DomainException denied){if(denied.status==403)throw forbidden();if(denied.status==404)throw new DomainException(404,"BACKUP_VERSION_NOT_FOUND","当前范围未找到设备或版本登记");if(denied.status==401)throw expired();throw unavailable();}catch(RuntimeException failure){throw unavailable();}
  Map<String,Object> out=pageDto(a,scope,before,limit,result);
  if(!session.equals(access(request)))throw expired();
  return success(out,request);
 }
 static Map<String,Object> pageDto(Access access,InventoryScope scope,InventoryCursor before,int limit,InventoryPage page){
  if(access==null||scope==null||!access.tenantId().equals(scope.tenantId())||limit<1||limit>50||page==null||page.items().size()>limit||before!=null&&!before.scope().equals(scope))throw unavailable();
  var items=new ArrayList<Map<String,Object>>();var seen=new HashSet<UUID>();InventoryCursor previous=before;
  for(InventoryItem item:page.items()){
   if(item==null||item.versionId()==null||item.assetId()==null||!scope.assetId().equals(item.assetId())||item.runId()==null||!seen.add(item.versionId())||item.attemptNumber()<1||item.attemptNumber()>BackupRunState.MAX_ATTEMPTS||item.completedRunVersion()<2||item.completedRunVersion()>MAX||!validTime(item.objectObservedAt())||!validTime(item.registeredAt())||item.registeredAt().isBefore(item.objectObservedAt())||item.encryptedSha256()==null||!item.encryptedSha256().matches("[0-9a-f]{64}")||item.encryptedBytes()<247||item.encryptedBytes()>BackupArchivePayload.MAX_ENVELOPE_BYTES)throw unavailable();
   if(previous!=null&&(item.registeredAt().isAfter(previous.registeredAt())||item.registeredAt().equals(previous.registeredAt())&&item.versionId().toString().compareTo(previous.versionId().toString())>=0))throw unavailable();
   previous=new InventoryCursor(scope,item.registeredAt(),item.versionId());
   items.add(map("version_id",item.versionId().toString(),"asset_id",item.assetId().toString(),"run_id",item.runId().toString(),"attempt_number",item.attemptNumber(),"completed_run_version",item.completedRunVersion(),"object_observed_at",item.objectObservedAt().toString(),"registered_at",item.registeredAt().toString(),"encrypted_sha256",item.encryptedSha256(),"encrypted_bytes",item.encryptedBytes(),"registration_and_run_completion_recorded",true,"current_object_health_verified",false,"download_authorized",false,"device_execution_authorized",false,"retention_verified",false,"recovery_verified",false));
  }
  if(page.hasMore()?items.size()!=limit||previous==null||!previous.equals(page.nextBefore()):page.nextBefore()!=null)throw unavailable();
  return map("tenant_id",scope.tenantId().toString(),"asset_id",scope.assetId().toString(),"actor_id",access.actorId().toString(),"accepted_permission_version",access.permissionVersion(),"items",items,"has_more",page.hasMore(),"next_before_registered_at",page.nextBefore()==null?null:page.nextBefore().registeredAt().toString(),"next_before_version_id",page.nextBefore()==null?null:page.nextBefore().versionId().toString(),"page_size",limit,"page_size_limit",50,"total_count",null,"scope","CURRENT_AUTHORIZED_TENANT_ASSET","snapshot_scope","SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT","source","postgresql","object_storage_accessed",false,"current_object_health_verified",false,"download_authorized",false,"device_execution_authorized",false,"retention_verified",false,"recovery_verified",false);
 }
 private SessionAccess access(HttpServletRequest request){
  if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");
  UUID actorId,tenantId;try{if(!(tenant instanceof String value))throw expired();tenantId=uuid(value);actorId=uuid(request.getUserPrincipal().getName());}catch(RuntimeException invalidScope){throw expired();}
  Membership member;
  try{member=platform.membership(actorId.toString(),tenantId.toString()).orElseThrow(BackupVersionInventoryController::expired);}
  catch(DomainException failure){if(failure.status==401||failure.status==404)throw expired();if(failure.status==403)throw forbidden();throw unavailable();}
  catch(RuntimeException failure){throw unavailable();}
  if(!member.active()||!actorId.toString().equals(member.userId())||!tenantId.toString().equals(member.tenantId())||member.permissionVersion()<1||member.permissionVersion()>MAX)throw expired();
  if(!member.capabilities().contains("assets:read"))throw forbidden();
  return new SessionAccess(new Access(tenantId,actorId,member.permissionVersion()),member);
 }
 private static Map<String,String> query(HttpServletRequest request){
  if(request.getQueryString()!=null&&request.getQueryString().length()>2048)throw invalid();var result=new HashMap<String,String>();
  request.getParameterMap().forEach((key,values)->{if(!Set.of("page_size","before_registered_at","before_version_id").contains(key)||values==null||values.length!=1||values[0]==null||values[0].length()>64)throw invalid();result.put(key,values[0]);});return result;
 }
 private static int size(String value){if(!value.matches("[1-9][0-9]?"))throw invalid();int n=Integer.parseInt(value);if(n>50)throw invalid();return n;}
 private static UUID uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value);}
 private static Instant parseTime(String value){
  if(value==null||!value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,3})?Z"))throw invalid();
  try{Instant time=OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant();if(!validTime(time))throw invalid();return time;}catch(DateTimeException failure){throw invalid();}
 }
 private static boolean validTime(Instant value){return value!=null&&!value.isBefore(MIN_TIME)&&!value.isAfter(MAX_TIME)&&value.getNano()%1_000_000==0;}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","版本列表仅接受当前设备、1至50页大小及成对毫秒UTC游标；不接受身份、租户或下载覆盖");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话、成员关系或授权版本已变化");}
 private static DomainException forbidden(){return new DomainException(403,"BACKUP_VERSION_INVENTORY_ACCESS_DENIED","当前设备的版本列表未获独立授权");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_VERSION_INVENTORY_UNAVAILABLE","版本列表或已登记完成关联尚未核对；未授予下载、解密或执行权限");}
}
