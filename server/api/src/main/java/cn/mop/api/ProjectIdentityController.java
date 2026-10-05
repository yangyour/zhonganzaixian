package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.authorization.CapabilityCatalog;
import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import static cn.mop.api.ApiResponse.*;

@RestController @Profile("remote_test")
@ConditionalOnProperty(name="MOP_AUTH_MODE",havingValue="project_local",matchIfMissing=true)
public class ProjectIdentityController {
    private final ProjectIdentityService identity;
    private final ProjectIdentityRepository accounts;
    private final PlatformRepository platform;
    private final SessionService sessions;
    private final ObjectMapper mapper;
    public ProjectIdentityController(ProjectIdentityService identity,PlatformRepository platform,SessionService sessions,ObjectMapper mapper){this.identity=identity;this.accounts=identity.repository();this.platform=platform;this.sessions=sessions;this.mapper=mapper;}
    @GetMapping("/api/v1/auth/csrf") Object csrf(HttpServletRequest request){CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());if(token==null)throw new DomainException(503,"IDENTITY_UNAVAILABLE","登录校验暂不可用");return success(ProjectIdentityRepository.result("csrf_token",token.getToken()),request);}
    @PostMapping("/api/v1/auth/local/login") Object login(@RequestBody byte[] bytes,HttpServletRequest request,HttpServletResponse response){
        var body=body(bytes,Set.of("username","password"));sameOrigin(request);
        String username=ProjectIdentityRepository.username(text(body,"username",64)),password=secret(body,"password");
        var verified=accounts.authenticate(username,password,trace(request)).orElseThrow(()->new DomainException(401,"LOGIN_FAILED","账号或密码不正确，或账号暂不可用"));
        var authentication=UsernamePasswordAuthenticationToken.authenticated(identity.principal(verified),null,List.of());
        sessions.establishProjectSession(authentication,verified.idleMinutes(),request,response);
        return success(sessions.session(request),request);
    }
    @GetMapping("/api/v1/account") Object account(HttpServletRequest request){var a=access(request,null);var result=accounts.ownAccount(a.actor());result.put("password_policy",accounts.policy());return success(result,request);}
    @PostMapping("/api/v1/account/password") Object password(@RequestBody byte[] bytes,HttpServletRequest request,HttpServletResponse response){
        var a=access(request,null);var body=body(bytes,Set.of("expected_version","current_password","new_password"));
        Object result=command(a,body,request,null,()->{},()->accounts.changePassword(a,version(body),secret(body,"current_password"),secret(body,"new_password")));
        if(request.getSession(false)!=null)request.getSession(false).invalidate();SecurityContextHolder.clearContext();
        var cookie=new Cookie("MOP_SESSION","");cookie.setPath("/");cookie.setHttpOnly(true);cookie.setMaxAge(0);cookie.setAttribute("SameSite","Strict");response.addCookie(cookie);
        return success(result,request);
    }
    @GetMapping("/api/v1/identity/users") Object users(@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="true") boolean include_inactive,@RequestParam(defaultValue="1") int page,HttpServletRequest request){
        rejectQuery(request,Set.of("query","include_inactive","page"));var a=access(request,"users:read");
        return success(accounts.userPage(a,query,include_inactive,page),request);
    }
    @GetMapping("/api/v1/identity/users/{id}") Object user(@PathVariable String id,HttpServletRequest request){rejectQuery(request,Set.of());return success(accounts.user(access(request,"users:read"),id),request);}
    @PostMapping("/api/v1/identity/users") Object createUser(@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version","username","display_name","password","role_id","active"));if(version(body)!=0)throw invalid("新建账号版本须为0");
        return success(command(a,body,request,"users:write",()->accounts.requireRole(a,text(body,"role_id",36)),()->accounts.createUser(a,text(body,"username",64),text(body,"display_name",100),secret(body,"password"),text(body,"role_id",36),bool(body,"active"))),request);
    }
    @PatchMapping("/api/v1/identity/users/{id}") Object updateUser(@PathVariable String id,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version","display_name","active"));
        return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.updateUser(a,id,version(body),text(body,"display_name",100),bool(body,"active"))),request);
    }
    @PostMapping("/api/v1/identity/users/{id}/password") Object reset(@PathVariable String id,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version","new_password"));
        return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.resetPassword(a,id,version(body),secret(body,"new_password"))),request);
    }
    @PostMapping("/api/v1/identity/users/{id}/local-account") Object enableLocal(@PathVariable String id,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version","username","password"));
        return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.enableLocalAccount(a,id,version(body),text(body,"username",64),secret(body,"password"))),request);
    }
    @PostMapping("/api/v1/identity/users/{id}/unlock") Object unlock(@PathVariable String id,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version"));return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.unlock(a,id,version(body))),request);
    }
    @PostMapping("/api/v1/identity/users/{id}/revoke-sessions") Object revoke(@PathVariable String id,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version"));return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.revokeSessions(a,id,version(body))),request);
    }
    @GetMapping("/api/v1/identity/users/{id}/memberships") Object memberships(@PathVariable String id,HttpServletRequest request){return success(ProjectIdentityRepository.result("items",accounts.memberships(access(request,"users:read"),id)),request);}
    @PutMapping("/api/v1/identity/users/{id}/memberships/{tenant}") Object membership(@PathVariable String id,@PathVariable String tenant,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version","role_id","active"));
        return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.setMembership(a,id,tenant,version(body),text(body,"role_id",36),bool(body,"active"))),request);
    }
    @GetMapping("/api/v1/identity/roles") Object roles(@RequestParam(required=false) String tenant_id,HttpServletRequest request){rejectQuery(request,Set.of("tenant_id"));var a=access(request,"roles:read");if(tenant_id!=null&&!tenant_id.equals(a.tenant())){var member=platform.membership(a.actor(),tenant_id).orElseThrow(()->new DomainException(404,"NOT_FOUND","对象不存在或不在授权范围"));if(!member.active()||!member.capabilities().contains("roles:read"))throw new DomainException(404,"NOT_FOUND","对象不存在或不在授权范围");a=new ProjectIdentityRepository.Access(a.actor(),tenant_id,member.permissionVersion(),a.trace());}return success(ProjectIdentityRepository.result("items",accounts.roles(a),"tenant_id",a.tenant(),"permission_catalog",CapabilityCatalog.current()),request);}
    @PostMapping("/api/v1/identity/roles") Object createRole(@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"roles:write");var body=body(bytes,Set.of("expected_version","name","description","base_role","permissions","enabled"));if(version(body)!=0)throw invalid("新建角色版本须为0");var role=role(body);
        return success(command(a,body,request,"roles:write",()->{},()->accounts.createRole(a,role)),request);
    }
    @PatchMapping("/api/v1/identity/roles/{id}") Object updateRole(@PathVariable String id,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"roles:write");var body=body(bytes,Set.of("expected_version","name","description","base_role","permissions","enabled"));var role=role(body);
        return success(command(a,body,request,"roles:write",()->accounts.requireRole(a,id),()->accounts.updateRole(a,id,version(body),role)),request);
    }
    @GetMapping("/api/v1/identity/policy") Object policy(HttpServletRequest request){access(request,"roles:read");return success(accounts.policy(),request);}
    @PutMapping("/api/v1/identity/policy") Object policy(@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"roles:write");var body=body(bytes,Set.of("expected_version","minimum_password_length","password_history_count","failed_attempt_limit","lock_minutes","idle_minutes","force_change_on_reset"));var values=new LinkedHashMap<String,Object>();
        for(String key:List.of("minimum_password_length","password_history_count","failed_attempt_limit","lock_minutes","idle_minutes")){
            JsonNode value=body.get(key);if(value==null)throw invalid("策略字段须明确提供null或数值");int max=switch(key){case "minimum_password_length"->128;case "password_history_count"->20;case "failed_attempt_limit"->100;default->1440;};
            if(!value.isNull()&&(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1||value.intValue()>max))throw invalid("策略参数超出支持范围");values.put(key,value.isNull()?null:value.intValue());
        }
        JsonNode force=body.get("force_change_on_reset");if(force==null||!force.isNull()&&!force.isBoolean())throw invalid("强制改密策略须为null或布尔值");values.put("force_change_on_reset",force.isNull()?null:force.booleanValue());
        if((values.get("failed_attempt_limit")==null)!=(values.get("lock_minutes")==null))throw invalid("失败次数与锁定时间须同时设置或同时清空");
        return success(command(a,body,request,"roles:write",()->{},()->accounts.updatePolicy(a,version(body),values)),request);
    }
    @GetMapping("/api/v1/identity/providers") Object providers(HttpServletRequest request){access(request,"users:read");return success(ProjectIdentityRepository.result("local_enabled",true,"unified_identity",ProjectIdentityRepository.result("protocol","OIDC","enabled",identity.oidcEnabled(),"binding","EXPLICIT_ISSUER_SUBJECT","automatic_user_matching",false,"external_roles_grant_permissions",false)),request);}
    @GetMapping("/api/v1/identity/users/{id}/external-identities") Object bindings(@PathVariable String id,HttpServletRequest request){return success(ProjectIdentityRepository.result("items",accounts.bindings(access(request,"users:read"),id)),request);}
    @PostMapping("/api/v1/identity/users/{id}/external-identities") Object bind(@PathVariable String id,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version","issuer","subject"));if(version(body)!=0)throw invalid("新增绑定版本须为0");String issuer=text(body,"issuer",2000),subject=text(body,"subject",255);
        try{URI uri=URI.create(issuer);if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||issuer.endsWith("/"))throw new IllegalArgumentException();}catch(RuntimeException malformed){throw invalid("统一身份签发者地址无效");}
        return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.bindExternal(a,id,issuer,subject)),request);
    }
    @PostMapping("/api/v1/identity/users/{id}/external-identities/{binding}/disable") Object disableBinding(@PathVariable String id,@PathVariable String binding,@RequestBody byte[] bytes,HttpServletRequest request){
        var a=access(request,"users:write");var body=body(bytes,Set.of("expected_version"));return success(command(a,body,request,"users:write",()->accounts.requireTarget(a,id),()->accounts.disableBinding(a,id,binding,version(body))),request);
    }
    private Object command(ProjectIdentityRepository.Access a,JsonNode body,HttpServletRequest request,String permission,Runnable authorize,Supplier<Object> action){return accounts.command(a,route(request),ProjectIdentityService.key(request),identity.commandHash(body),permission,authorize,action);}
    private ProjectIdentityRepository.Access access(HttpServletRequest request,String permission){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw new DomainException(401,"SESSION_EXPIRED","请先登录项目账号");Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String value))throw new DomainException(401,"SESSION_EXPIRED","当前租户未选择");String actor=request.getUserPrincipal().getName();var member=platform.membership(actor,value).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));if(permission!=null&&!member.capabilities().contains(permission))throw new DomainException(403,"ACTION_FORBIDDEN","当前身份没有此管理权限");return new ProjectIdentityRepository.Access(actor,value,member.permissionVersion(),trace(request));
    }
    private JsonNode body(byte[] raw,Set<String> allowed){
        if(raw.length>32768)throw new DomainException(413,"PAYLOAD_TOO_LARGE","账号请求超过技术大小上限");try(var parser=mapper.createParser(raw)){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode value=mapper.readTree(parser);if(value==null||!value.isObject()||parser.nextToken()!=null)throw invalid("请求须为单个JSON对象");value.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw invalid("包含未知请求字段");});return value;}catch(DomainException failure){throw failure;}catch(Exception failure){throw invalid("JSON请求格式无效");}
    }
    private static String text(JsonNode body,String key,int max){var value=body.get(key);if(value==null||!value.isTextual()||value.textValue().isBlank()||value.textValue().codePointCount(0,value.textValue().length())>max||value.textValue().codePoints().anyMatch(Character::isISOControl))throw invalid("请求文本字段无效");return value.textValue();}
    private static String secret(JsonNode body,String key){var value=body.get(key);if(value==null||!value.isTextual())throw invalid("密码字段无效");ProjectIdentityRepository.passwordInput(value.textValue());return value.textValue();}
    private static boolean bool(JsonNode body,String key){var value=body.get(key);if(value==null||!value.isBoolean())throw invalid("请求布尔字段无效");return value.booleanValue();}
    private static long version(JsonNode body){var value=body.get("expected_version");if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0||value.longValue()>9007199254740990L)throw invalid("需要明确的记录版本");return value.longValue();}
    private static ProjectRole role(JsonNode body){var list=body.get("permissions");if(list==null||!list.isArray()||list.size()>256)throw invalid("权限列表无效");var values=new ArrayList<String>();for(var item:list){if(!item.isTextual())throw invalid("权限项必须为字符串");values.add(item.textValue());}var description=body.get("description");if(description==null||!description.isNull()&&!description.isTextual())throw invalid("角色说明须为文本或null");return new ProjectRole(text(body,"name",100),description.isNull()?null:description.textValue(),text(body,"base_role",32),values,bool(body,"enabled"));}
    private static void rejectQuery(HttpServletRequest request,Set<String> allowed){for(var entry:request.getParameterMap().entrySet())if(!allowed.contains(entry.getKey())||entry.getValue().length!=1)throw invalid("不支持的查询条件");}
    private static void sameOrigin(HttpServletRequest request){String origin=request.getHeader("Origin");if(origin==null)return;String expected=request.getScheme()+"://"+request.getServerName()+((request.getScheme().equals("http")&&request.getServerPort()==80||request.getScheme().equals("https")&&request.getServerPort()==443)?"":":"+request.getServerPort());if(!expected.equals(origin))throw new DomainException(403,"CSRF_INVALID","登录请求必须同源");}
    private static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
}
