package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.configuration.RuntimeConfigurationRepository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

@RestController @Profile("remote_test") @RequestMapping("/api/v1/runtime-configurations")
public class RuntimeConfigurationController {
    private final PlatformRepository platform;private final RuntimeConfigurationRepository repository;private final RuntimeConfigurationCatalog catalog;private final ObjectMapper mapper;
    public RuntimeConfigurationController(PlatformRepository platform,RuntimeConfigurationRepository repository,RuntimeConfigurationCatalog catalog,ObjectMapper mapper){this.platform=platform;this.repository=repository;this.catalog=catalog;this.mapper=mapper;}
    @GetMapping("/catalog") Object catalog(HttpServletRequest r){access(r,false);noQuery(r);return success(map("items",catalog.definitions(),"question_count",31,"source","code_catalog","scope","CURRENT_TENANT","production_approved",false),r);}
    @GetMapping("/{key}") Object current(@PathVariable String key,HttpServletRequest r){Access a=access(r,false);noQuery(r);catalog.entry(key);var value=new LinkedHashMap<String,Object>(repository.read(a,key));String hash=catalog.hash(key);value.put("definition_hash",hash);if("ACTIVE".equals(value.get("status"))&&value.get("selected_release") instanceof Map<?,?> selected&&!hash.equals(selected.get("definition_hash"))){value.put("status","DEFINITION_CHANGED");value.put("effective_value",null);}return success(value,r);}
    @GetMapping("/{key}/history") Object history(@PathVariable String key,HttpServletRequest r){Access a=access(r,false);if(r.getParameterMap().size()>1||r.getParameterMap().keySet().stream().anyMatch(k->!k.equals("before_version"))||r.getParameterValues("before_version")!=null&&r.getParameterValues("before_version").length!=1)throw invalid("仅允许单个before_version");long before=9007199254740991L;if(r.getParameter("before_version")!=null)try{String v=r.getParameter("before_version");if(!v.matches("[1-9][0-9]{0,15}"))throw invalid("版本无效");before=Long.parseLong(v);if(before>9007199254740990L)throw invalid("版本超出范围");}catch(NumberFormatException e){throw invalid("版本无效");}catalog.entry(key);return success(map("items",repository.history(a,key,before),"page_size",25,"source","postgresql"),r);}
    @PostMapping("/{key}/validate") Object validate(@PathVariable String key,@RequestBody JsonNode body,HttpServletRequest r){access(r,true);noQuery(r);fields(body,Set.of("value"));catalog.validate(key,body.get("value"));return success(map("configuration_key",key,"valid",true,"definition_hash",catalog.hash(key),"saved",false),r);}
    @PostMapping("/{key}/releases") Object publish(@PathVariable String key,@RequestBody JsonNode body,HttpServletRequest r){
        Access a=access(r,true);noQuery(r);fields(body,Set.of("expected_version","expected_definition_hash","action","revision_id","revision_version","target_release_id","activation","effective_at","expires_at","reason"));catalog.entry(key);
        String expectedDefinition=text(body,"expected_definition_hash",64);if(!expectedDefinition.matches("[a-f0-9]{64}"))throw invalid("配置定义摘要无效");
        String action=text(body,"action",16),activation=text(body,"activation",16);if(!action.equals(body.get("action").asText())||!activation.equals(body.get("activation").asText())||!Set.of("PUBLISH","ROLLBACK","DISABLE").contains(action)||!Set.of("IMMEDIATE","SCHEDULED").contains(activation))throw invalid("发布动作或生效方式无效");
        long version=version(body,"expected_version",0);String revision=nullableUuid(body,"revision_id"),target=nullableUuid(body,"target_release_id");Long revisionVersion=body.path("revision_version").isNull()?null:version(body,"revision_version",1);
        Instant effective=time(body,"effective_at"),expires=time(body,"expires_at");if(activation.equals("IMMEDIATE")&&effective!=null||activation.equals("SCHEDULED")&&effective==null)throw invalid("生效时间与生效方式不一致");
        if(action.equals("PUBLISH")&&(revision==null||revisionVersion==null||target!=null)||action.equals("ROLLBACK")&&(target==null||revision!=null||revisionVersion!=null)||action.equals("DISABLE")&&(target!=null||revision!=null||revisionVersion!=null))throw invalid("发布来源与动作不一致");
        if(action.equals("DISABLE")&&expires!=null)throw invalid("禁用操作不设置到期时间，恢复须显式发布新版本");
        Command command=new Command(version,action,revision,revisionVersion,target,activation,effective,expires,text(body,"reason",500));
        String requestKey=r.getHeader("Idempotency-Key");if(requestKey==null||!requestKey.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid("需要合法Idempotency-Key");
        return success(platform.idempotentAuthorized(a.actorId(),a.tenantId(),route(r),requestKey,hash(body),a.permissionVersion(),()->repository.authorizeCommand(a,key),()->{
            if(!expectedDefinition.equals(catalog.hash(key)))throw new DomainException(409,"VERSION_CONFLICT","配置定义已变化，请重新读取并校验");
            if(action.equals("PUBLISH")){var proposal=platform.configurationRevision(a.tenantId(),revision).orElseThrow(RuntimeConfigurationController::missing);if(!key.equals(proposal.get("configuration_key")))throw missing();catalog.validate(key,mapper.valueToTree(proposal.get("proposed_value")));}
            else if(action.equals("ROLLBACK")){var match=repository.release(a,key,target);catalog.validate(key,mapper.valueToTree(match.get("value")));}
            return repository.publish(a,key,command,catalog.hash(key));
        }),r);
    }
    private Access access(HttpServletRequest r,boolean write){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw new DomainException(401,"SESSION_EXPIRED","会话已失效");String actor=r.getUserPrincipal().getName(),tenant=(String)r.getSession(false).getAttribute("mop.tenant_id");var m=platform.membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));if(!m.active()||!"padmin".equals(m.role())||!m.capabilities().contains(write?"configurations:write":"configurations:read"))throw new DomainException(403,"ACTION_FORBIDDEN","没有运行配置权限");return new Access(tenant,actor,m.permissionVersion(),trace(r));}
    private static void fields(JsonNode b,Set<String> fields){if(b==null||!b.isObject()||b.size()!=fields.size())throw invalid("请求字段不完整或包含未知字段");b.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw invalid("未知字段");});}
    private static String text(JsonNode b,String k,int n){return ConfigurationRevisionController.text(b,k,n);}
    private static long version(JsonNode b,String k,int min){var v=b.get(k);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<min||v.longValue()>9007199254740990L)throw invalid(k+"版本无效");return v.longValue();}
    private static String nullableUuid(JsonNode b,String k){var v=b.get(k);if(v==null)throw invalid(k+"缺失");if(v.isNull())return null;if(!v.isTextual()||!v.asText().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid(k+"标识无效");return v.asText();}
    private static Instant time(JsonNode b,String k){var v=b.get(k);if(v==null)throw invalid(k+"缺失");if(v.isNull())return null;try{if(!v.isTextual()||!v.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?Z"))throw invalid("时间格式无效");Instant t=Instant.parse(v.asText());if(t.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||t.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid("时间范围无效");return t;}catch(RuntimeException e){throw invalid(k+"须为明确UTC时间，最多毫秒");}}
    private String hash(JsonNode b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(sorted(b))));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode sorted(JsonNode n){if(n.isObject()){var o=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->o.set(k,sorted(n.get(k))));return o;}if(n.isArray()){var a=mapper.createArrayNode();n.forEach(v->a.add(sorted(v)));return a;}return n;}
    private static void noQuery(HttpServletRequest r){if(!r.getParameterMap().isEmpty())throw invalid("不接受额外查询参数");}
    private static DomainException invalid(String s){return new DomainException(400,"VALIDATION_ERROR",s);}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
}
