package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.contact.CustomerContact.QuietWindow;
import cn.mop.domain.notification.*;
import cn.mop.domain.notification.OwnNotificationPreferenceRepository.*;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft.LevelPreference;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Stores an explicit owner request, never an effective delivery decision or provider subscription. */
@RestController @RequestMapping("/api/v1/my/notification-preferences") @Profile("remote_test")
public final class OwnNotificationPreferenceController {
    static final int MAX_BODY_BYTES=8192;
    static final long MAX_VERSION=9_007_199_254_740_990L;
    static final String INTENT="SAVE_REQUEST_DRAFT_ONLY";
    private static final Set<String> FIELDS=Set.of("expected_version","intent","requested_level","requested_channels","requested_quiet_window");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(128).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final OwnNotificationPreferenceRepository preferences;
    public OwnNotificationPreferenceController(PlatformRepository platform,OwnNotificationPreferenceRepository preferences){this.platform=platform;this.preferences=preferences;}
    @GetMapping Object current(HttpServletRequest r){
        var a=access(r,false);query(r,Set.of());var found=preferences.current(a);if(found==null)throw unavailable();found.ifPresent(s->check(a,s,null));
        return success(map("tenant_id",a.tenantId(),"owner_id",a.actorId(),"exists",found.isPresent(),"current",found.map(OwnNotificationPreferenceController::dto).orElse(null),"scope","CURRENT_OWNER_TENANT","source","postgresql"),r);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest r){var a=access(r,false);query(r,Set.of());String key=uuid(id);var s=preferences.get(a,key);check(a,s,key);return success(dto(s),r);}
    @GetMapping("/{id}/history") Object history(@PathVariable String id,HttpServletRequest r){
        var a=access(r,false);var q=query(r,Set.of("page_size","before_version"));String size=q.getOrDefault("page_size","15");if(!Set.of("10","15","20").contains(size))throw invalid();int limit=Integer.parseInt(size);String key=uuid(id);Long before=q.containsKey("before_version")?version(q.get("before_version")):null;
        var rows=preferences.history(a,key,limit+1,before);if(rows==null||rows.size()>limit+1)throw unavailable();long previous=before==null?MAX_VERSION+1:before;
        for(var row:rows){if(row==null||!key.equals(row.preferenceId())||row.version()>=previous)throw unavailable();checkDefinition(a,row.tenantId(),row.draft(),row.recordedBy(),row.recordedPermissionVersion());time(row.recordedAt());if(row.version()<1||row.version()>MAX_VERSION||!(row.version()==1?"CREATE":"UPDATE").equals(row.action()))throw unavailable();previous=row.version();}
        boolean more=rows.size()>limit;var items=rows.subList(0,Math.min(rows.size(),limit));return success(map("tenant_id",a.tenantId(),"owner_id",a.actorId(),"preference_id",key,"items",items.stream().map(OwnNotificationPreferenceController::revisionDto).toList(),"has_more",more,"next_before_version",more?items.get(items.size()-1).version():null,"scope","CURRENT_OWNER_TENANT","source","postgresql","page_size_limit",20),r);
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object create(HttpServletRequest r){access(r,true);return write(null,body(r),r);}
    @PatchMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) Object update(@PathVariable String id,HttpServletRequest r){access(r,true);return write(uuid(id),body(r),r);}
    Object write(String id,JsonNode input,HttpServletRequest r){
        var a=access(r,true);query(r,Set.of());String canonical=id==null?null:uuid(id);fields(input,FIELDS);var v=input.get("expected_version");if(!v.isIntegralNumber()||!v.canConvertToLong()||!INTENT.equals(text(input.get("intent"))))throw invalid();long expected=v.longValue();if(canonical==null?expected!=0:expected<1||expected>=MAX_VERSION)throw invalid();var draft=definition(a,input);
        String key=r.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),r.getMethod()+" "+route(r),key,hash(input),a.permissionVersion(),()->preferences.authorizeCurrentAccess(a,canonical),()->{
            var s=canonical==null?preferences.create(a,draft):preferences.update(a,canonical,expected,draft);check(a,s,canonical);if(s.version()!=expected+1||s.recordedPermissionVersion()!=a.permissionVersion()||!s.draft().equals(draft))throw unavailable();return dto(s);
        });return success(result,r);
    }
    private static OwnNotificationPreferenceDraft definition(Access a,JsonNode n){
        try{var level=n.get("requested_level");var channels=n.get("requested_channels");List<Channel> codes=null;
            if(!channels.isNull()){if(!channels.isArray()||channels.size()>6)throw invalid();codes=new ArrayList<>();for(var code:channels)codes.add(Channel.valueOf(text(code)));}
            var window=n.get("requested_quiet_window");QuietWindow quiet=null;if(!window.isNull()){fields(window,Set.of("enabled","start","end"));var enabled=window.get("enabled");if(!enabled.isNull()&&!enabled.isBoolean())throw invalid();quiet=new QuietWindow(enabled.isNull()?null:enabled.booleanValue(),window.get("start").isNull()?null:QuietWindow.parseMinute(text(window.get("start"))),window.get("end").isNull()?null:QuietWindow.parseMinute(text(window.get("end"))));}
            return new OwnNotificationPreferenceDraft(UUID.fromString(a.tenantId()),UUID.fromString(a.actorId()),level.isNull()?null:LevelPreference.valueOf(text(level)),codes,quiet);
        }catch(IllegalArgumentException e){throw invalid();}
    }
    static Map<String,Object> dto(Snapshot s){var out=definitionDto(s.draft());out.putAll(map("id",s.id(),"tenant_id",s.tenantId(),"version",s.version(),"recorded_by",s.recordedBy(),"recorded_permission_version",s.recordedPermissionVersion(),"created_at",s.createdAt().toString(),"updated_at",s.updatedAt().toString()));return out;}
    static Map<String,Object> revisionDto(Revision s){var out=definitionDto(s.draft());out.putAll(map("preference_id",s.preferenceId(),"tenant_id",s.tenantId(),"version",s.version(),"previous_version",s.version()==1?null:s.version()-1,"action",s.action(),"recorded_by",s.recordedBy(),"recorded_permission_version",s.recordedPermissionVersion(),"recorded_at",s.recordedAt().toString()));return out;}
    private static Map<String,Object> definitionDto(OwnNotificationPreferenceDraft d){var q=d.requestedQuietWindow();return map("owner_id",d.ownerId().toString(),"requested_level",d.requestedLevel()==null?null:d.requestedLevel().name(),"requested_channels",d.requestedChannels()==null?null:d.requestedChannels().stream().map(Enum::name).toList(),"requested_quiet_window",q==null?null:map("enabled",q.enabled(),"start",q.start()==null?null:q.start().toString(),"end",q.end()==null?null:q.end().toString()),"content_sha256",d.fingerprint(),"timezone","Asia/Shanghai","configuration_status","CONFIG_PENDING","effective",false,"effective_level",null,"effective_channels",null,"effective_quiet_window",null,"verified_receiver_id",null,"receiver_binding_status","NOT_VERIFIED","delivery_decision",null,"notification_dispatched",false,"p1_quiet_bypass_contract_rule",true,"record_source","OWN_NOTIFICATION_PREFERENCE_DRAFT","scope","CURRENT_OWNER_TENANT","source","postgresql");}
    private static void check(Access a,Snapshot s,String id){if(s==null||s.id()==null||!s.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||id!=null&&!id.equals(s.id())||s.version()<1||s.version()>MAX_VERSION)throw unavailable();checkDefinition(a,s.tenantId(),s.draft(),s.recordedBy(),s.recordedPermissionVersion());time(s.createdAt());time(s.updatedAt());if(s.updatedAt().isBefore(s.createdAt())||s.version()==1&&!s.createdAt().equals(s.updatedAt()))throw unavailable();}
    private static void checkDefinition(Access a,String tenant,OwnNotificationPreferenceDraft d,String actor,long recordedPermission){if(d==null||!a.tenantId().equals(tenant)||!a.tenantId().equals(d.tenantId().toString())||!a.actorId().equals(d.ownerId().toString())||!a.actorId().equals(actor)||recordedPermission<1||recordedPermission>MAX_VERSION+1)throw unavailable();}
    private static void time(Instant t){if(t==null||t.getNano()%1000000!=0||t.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||t.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw unavailable();}
    private Access access(HttpServletRequest r,boolean write){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();Object value=r.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();String actor=r.getUserPrincipal().getName();var m=platform.membership(actor,tenant).orElseThrow(OwnNotificationPreferenceController::expired);if(!m.active()||m.permissionVersion()<1||m.permissionVersion()>MAX_VERSION+1)throw expired();if(!m.capabilities().contains(write?"notification_preferences:write_own":"notification_preferences:read_own"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能访问本人通知偏好草稿");return new Access(actor,tenant,m.permissionVersion(),trace(r));}
    static JsonNode body(HttpServletRequest r){try{if(r.getContentLengthLong()>MAX_BODY_BYTES)throw large();byte[] bytes=r.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw large();try(var parser=JSON.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>64)throw large();}return JSON.readTree(bytes);}catch(DomainException e){throw e;}catch(Exception e){throw invalid();}}
    private static Map<String,String> query(HttpServletRequest r,Set<String> allowed){if(r.getQueryString()!=null&&r.getQueryString().getBytes(StandardCharsets.UTF_8).length>2048)throw invalid();var result=new HashMap<String,String>();r.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v.length!=1||v[0]==null||v[0].isBlank()||v[0].length()>64)throw invalid();result.put(k,v[0]);});return result;}
    private static long version(String s){if(!s.matches("[1-9][0-9]{0,15}"))throw invalid();try{long v=Long.parseLong(s);if(v>MAX_VERSION)throw invalid();return v;}catch(NumberFormatException e){throw invalid();}}
    private static String uuid(String s){if(s==null||!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(s).toString();}
    private static String text(JsonNode n){if(n==null||!n.isTextual())throw invalid();return n.textValue();}
    private static void fields(JsonNode n,Set<String> keys){if(n==null||!n.isObject()||n.size()!=keys.size())throw invalid();n.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    private static JsonNode canonical(JsonNode n){if(n.isArray()){var result=JSON.createArrayNode();n.forEach(v->result.add(canonical(v)));return result;}if(!n.isObject())return n;var result=JSON.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)result.set(k,canonical(n.get(k)));return result;}
    private static String hash(JsonNode n){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(n))));}catch(Exception e){throw new IllegalStateException("Preference request hash unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","仅接受本人偏好明确请求、草稿意向与版本；不允许覆盖身份、实际接收人、批准或派发状态");}
    private static DomainException large(){return new DomainException(413,"REQUEST_TOO_LARGE","本人通知偏好请求超过有界保护，未截断保存");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","本人通知偏好投影未通过范围或数据完整性核对");}
}
