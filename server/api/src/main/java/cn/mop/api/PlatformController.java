package cn.mop.api;

import cn.mop.domain.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static cn.mop.api.ApiResponse.*;

@RestController @RequestMapping("/api/v1") @Profile({"local_fixture","remote_test"})
public class PlatformController {
    private final PlatformRepository repository;
    private final ObjectMapper mapper;
    private final ObjectProvider<TestOidcConfiguration.OidcDependencyProbe> identityProbe;
    private final byte[] cursorSecret=new byte[32];
    private static final Set<String> TYPES=Set.of("router","switch","firewall","ap","wireless_controller","other");
    private record Access(FixtureUser user,Tenant tenant,Membership membership,List<String> capabilities){}
    public PlatformController(PlatformRepository repository,ObjectMapper mapper,ObjectProvider<TestOidcConfiguration.OidcDependencyProbe> identityProbe){this.repository=repository;this.mapper=mapper;this.identityProbe=identityProbe;new SecureRandom().nextBytes(cursorSecret);}
    @GetMapping("/health/live") Object live(HttpServletRequest request){return success(map("status","UP","mode",repository.metadata().mode(),"source",repository.metadata().source()),request);}
    @GetMapping("/health/ready") Object ready(HttpServletRequest request){
        if(!repository.dependencyReady())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境 PostgreSQL 不可用，未就绪");
        if(repository.metadata().mode().equals("remote_test")&&(identityProbe.getIfAvailable()==null||!identityProbe.getIfAvailable().ready()))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境 Keycloak 身份源不可用，未就绪");
        return success(map("status","READY_REMOTE_TEST","ready",true,"mode",repository.metadata().mode(),"source",repository.metadata().source(),"durable",repository.metadata().durable(),"production_ready",false),request);
    }
    @GetMapping("/assets") Object assets(@RequestParam Map<String,String> query,HttpServletRequest request){
        Access access=access(request,"assets:read");Set<String> allowed=Set.of("cursor","page_size","type","site_id","status","q");
        if(!allowed.containsAll(query.keySet())||query.values().stream().anyMatch(v->v.length()>2048)||request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw validation("查询参数不在白名单或重复");
        int size=15;try{if(query.containsKey("page_size"))size=Integer.parseInt(query.get("page_size"));}catch(NumberFormatException e){throw validation("page_size 必须为整数");}
        if(!Set.of(10,15,25,50,100).contains(size))throw validation("测试阶段技术单页上限为100，选项10/15/25/50/100；正式上限待确认");
        if(query.containsKey("type")&&!TYPES.contains(query.get("type")))throw validation("type 不在枚举中");
        if(query.containsKey("status")&&!Set.of("unknown","ok","problem","maintenance","disabled").contains(query.get("status")))throw validation("status 不在枚举中");
        if(query.getOrDefault("q","").length()>100||query.getOrDefault("site_id","").length()>64)throw validation("筛选文字过长");
        SortedMap<String,String> filters=new TreeMap<>(query);filters.remove("cursor");filters.remove("page_size");String filterHash=hash(mapper.valueToTree(filters));
        JsonNode cursor=query.containsKey("cursor")?readCursor(query.get("cursor"),access,filterHash):null;
        List<Asset> matches=repository.list(access.tenant.id()).stream()
            .filter(a->!query.containsKey("type")||a.type().equals(query.get("type")))
            .filter(a->!query.containsKey("status")||a.status().equals(query.get("status")))
            .filter(a->!query.containsKey("site_id")||a.siteId().equals(query.get("site_id")))
            .filter(a->!query.containsKey("q")||a.name().toLowerCase(Locale.ROOT).contains(query.get("q").toLowerCase(Locale.ROOT))||a.managementIp().contains(query.get("q")))
            .filter(a->cursor==null||after(a,cursor)).toList();
        boolean more=matches.size()>size;List<Asset> items=matches.subList(0,Math.min(size,matches.size()));
        String next=more?cursor(access,filterHash,items.get(items.size()-1)):null;
        return success(map("items",items,"next_cursor",next,"has_more",more,"source",repository.metadata().source(),"page_size_limit",100,"page_size_limit_kind","test_technical_limit"),request);
    }
    @GetMapping("/assets/{id}") Object asset(@PathVariable String id,HttpServletRequest request){Access access=access(request,"assets:read");return success(repository.find(access.tenant.id(),id).orElseThrow(()->notFound()),request);}
    @PostMapping("/assets") Object createAsset(@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"assets:write");fields(body,Set.of("name","type","management_ip","site_id"));
        String name=text(body,"name",100),type=text(body,"type",32),ip=text(body,"management_ip",45),site=text(body,"site_id",64);
        if(!TYPES.contains(type))throw validation("type 不在设备枚举中");if(!validIp(ip))throw validation("management_ip 必须为合法 IPv4/IPv6 字面量");
        Object data=write(access,body,request,()->{Asset result=repository.insert(access.tenant.id(),access.user.id(),name,type,ip,site);repository.audit(access.user.id(),access.tenant.id(),"asset.create_draft","asset",result.id(),"SUCCEEDED","DRAFT_ONLY",trace(request));return result;});
        return success(data,request);
    }
    @PostMapping("/assets/{id}/onboarding") Object onboard(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"assets:write");fields(body,Set.of("expected_version"));long version=version(body);
        Asset asset=repository.find(access.tenant.id(),id).orElseThrow(()->notFound());
        return success(write(access,body,request,()->{if(asset.version()!=version)throw conflict();throw pending(List.of("Q02","Q03","Q06"),"数据库、组件和监控规则待确认，未受理接入任务");}),request);
    }
    @GetMapping("/configurations") Object configurations(@RequestParam Map<String,String> query,HttpServletRequest request){
        access(request,"configurations:read");if(!Set.of("decision_status").containsAll(query.keySet())||request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw validation("配置查询仅允许decision_status");
        if(query.containsKey("decision_status")&&!Set.of("pending","contract_fixed").contains(query.get("decision_status")))throw validation("decision_status 不在登记枚举中");
        List<Map<String,Object>> items=repository.configurations().stream().filter(item->!query.containsKey("decision_status")||Objects.equals(item.get("decision_status"),query.get("decision_status"))).toList();
        return success(map("items",items,"source",repository.metadata().source(),"registry_status","design_registry_not_runtime_config","runtime_activation",false),request);
    }
    @PostMapping("/configurations/{key}/draft") Object configDraft(@PathVariable String key,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"configurations:write");fields(body,Set.of("proposed_value","expected_version"));long expected=version(body);
        Map<String,Object> entry=repository.configuration(key);JsonNode proposed=body.get("proposed_value");if(proposed==null||proposed.isNull())throw validation("proposed_value 必须非null且符合登记类型");
        ConfigurationValueValidator.validate(entry,proposed);
        Object data=write(access,body,request,()->{Map<String,Object> result=repository.draftConfiguration(key,mapper.convertValue(proposed,Object.class),expected);repository.audit(access.user.id(),access.tenant.id(),"configuration.save_draft","configuration",key,"SUCCEEDED","DRAFT_NOT_EFFECTIVE",trace(request));return result;});return success(data,request);
    }
    @PostMapping("/configurations/{key}/publish") Object publish(@PathVariable String key,@RequestBody JsonNode body,HttpServletRequest request){
        Access access=access(request,"configurations:write");fields(body,Set.of("expected_version"));long expected=version(body);Map<String,Object> entry=repository.configuration(key);
        return success(write(access,body,request,()->{if(((Number)entry.get("version")).longValue()!=expected)throw conflict();
            List<?> questions=(List<?>)entry.getOrDefault("question_ids",List.of());
            throw pending(questions.isEmpty()?List.of("Q02","Q17"):questions,"当前仅有设计登记与测试环境草稿，未配置正式审批及生效存储，未发布");}),request);
    }
    @GetMapping("/audit") Object audit(HttpServletRequest request){Access access=access(request,"audit:read");return success(map("items",repository.audits(access.tenant.id()),"source",repository.metadata().source(),"durable",repository.metadata().durable()),request);}

    private Access access(HttpServletRequest request,String capability){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw new DomainException(401,"SESSION_EXPIRED","会话不存在或已过期");
        String actor=request.getUserPrincipal().getName(),tenant=(String)request.getSession().getAttribute("mop.tenant_id");
        Membership membership=repository.membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效，请重新登录"));
        List<String> capabilities=capabilities(membership.role());if(!capabilities.contains(capability))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无此动作权限");
        return new Access(repository.user(actor).orElseThrow(()->notFound()),repository.tenant(tenant),membership,capabilities);
    }
    private static List<String> capabilities(String role){if(role.equals("padmin"))return List.of("assets:read","assets:write","configurations:read","configurations:write","audit:read");if(Set.of("msp","tadmin").contains(role))return List.of("assets:read","assets:write");return List.of("assets:read");}
    private Object write(Access access,JsonNode body,HttpServletRequest request,Supplier<Object> command){
        return repository.idempotent(access.user.id(),access.tenant.id(),route(request),requestKey(request),hash(body),access.membership.permissionVersion(),()->{
            // Recheck under the same monitor as the write; concurrent revocation cannot race a commit.
            repository.membership(access.user.id(),access.tenant.id()).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));return command.get();
        });
    }
    private String requestKey(HttpServletRequest request){String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw validation("Idempotency-Key 为必填，长度1至128且仅包含字母数字及._:-");return key;}
    private static void fields(JsonNode body,Set<String> allowed){if(!body.isObject())throw validation("请求体必须为JSON对象");body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw validation("未知或禁止字段: "+k);});}
    private static String text(JsonNode body,String key,int max){JsonNode value=body.get(key);if(value==null||!value.isTextual())throw validation(key+" 必须为字符串");String result=value.asText().strip();if(result.isEmpty()||result.length()>max||result.codePoints().anyMatch(Character::isISOControl))throw validation(key+" 为空、过长或含控制字符");return result;}
    private static long version(JsonNode body){JsonNode value=body.get("expected_version");if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<1)throw validation("expected_version 必须为正整数");return value.asLong();}
    private static boolean validIp(String ip){
        if(ip.contains(":")){if(!ip.matches("[0-9a-fA-F:.]+"))return false;try{InetAddress.getByName(ip);return true;}catch(Exception e){return false;}}
        if(!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;for(String octet:ip.split("\\.")){if(Integer.parseInt(octet)>255||octet.length()>1&&octet.startsWith("0"))return false;}return true;
    }
    private String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(value))));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode node=mapper.createObjectNode();TreeSet<String> fields=new TreeSet<>();value.fieldNames().forEachRemaining(fields::add);fields.forEach(k->node.set(k,canonical(value.get(k))));return node;}if(value.isArray()){var array=mapper.createArrayNode();value.forEach(v->array.add(canonical(v)));return array;}return value;}
    private String cursor(Access access,String filter,Asset last){try{String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(map("tenant",access.tenant.id(),"actor",access.user.id(),"permission_version",access.membership.permissionVersion(),"filter",filter,"created_at",last.createdAt().toString(),"id",last.id())));return payload+"."+signature(payload);}catch(Exception e){throw new IllegalStateException(e);}}
    private String signature(String payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cursorSecret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode readCursor(String token,Access access,String filter){try{String[] parts=token.split("\\.");if(parts.length!=2||!MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),signature(parts[0]).getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();JsonNode cursor=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));if(!cursor.path("tenant").asText().equals(access.tenant.id())||!cursor.path("actor").asText().equals(access.user.id())||cursor.path("permission_version").asLong()!=access.membership.permissionVersion()||!cursor.path("filter").asText().equals(filter))throw new IllegalArgumentException();Instant.parse(cursor.path("created_at").asText());return cursor;}catch(Exception e){throw new DomainException(400,"CURSOR_INVALID","游标无效、过期或不属于当前授权筛选范围");}}
    private static boolean after(Asset asset,JsonNode cursor){int compare=asset.createdAt().compareTo(Instant.parse(cursor.path("created_at").asText()));return compare>0||compare==0&&asset.id().compareTo(cursor.path("id").asText())>0;}
    private static DomainException validation(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
    private static DomainException notFound(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","对象版本已变化，请刷新");}
    private static DomainException pending(List<?> questions,String message){return new DomainException(422,"CONFIG_PENDING",message,List.of(map("question_ids",questions)));}
}
