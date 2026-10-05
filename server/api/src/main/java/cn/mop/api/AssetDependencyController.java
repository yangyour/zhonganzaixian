package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetdependency.AssetDependencyRepository;
import cn.mop.domain.assetdependency.AssetDependencyRepository.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Synchronous PostgreSQL draft only; no monitoring writes, publication or inferred impact. */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public final class AssetDependencyController {
    static final int MAX_BODY_BYTES=131072,MAX_BODY_TOKENS=2048;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(256).maxNumberLength(19).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    private final AssetDependencyRepository dependencies;
    private final ObjectMapper mapper;
    private record Access(String actor,String tenant,long permissionVersion) {}
    public AssetDependencyController(PlatformRepository platform,AssetDependencyRepository dependencies,ObjectMapper mapper){this.platform=platform;this.dependencies=dependencies;this.mapper=mapper;}

    @GetMapping("/assets/{id}/dependencies") Object current(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,false);noQuery(request);String asset=asset(id,a.tenant());
        return success(dto(asset,dependencies.current(a.tenant(),a.actor())),request);
    }
    @PostMapping(value="/assets/{id}/dependencies",consumes=MediaType.APPLICATION_JSON_VALUE)
    Object appendRequest(@PathVariable String id,HttpServletRequest request){access(request,true);return append(id,body(request),request);}
    Object append(String id,JsonNode body,HttpServletRequest request){
        Access a=access(request,true);noQuery(request);String asset=asset(id,a.tenant()),key=request.getHeader("Idempotency-Key");
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        fields(body,Set.of("expected_version","additions"));JsonNode version=body.get("expected_version"),values=body.get("additions");
        if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<0||version.longValue()>9007199254740990L||!values.isArray()||values.isEmpty()||values.size()>AssetDependencyRepository.MAX_ADDITIONS)throw invalid();
        List<ProposedEdge> additions=new ArrayList<>();
        for(JsonNode value:values){
            fields(value,Set.of("parent_id","child_id","proposed_type","proposed_suppression_scope","proposed_enabled"));
            String parent=uuid(value.get("parent_id")),child=uuid(value.get("child_id"));
            if(!parent.equals(asset)&&!child.equals(asset))throw invalid();
            JsonNode enabled=value.get("proposed_enabled");if(!enabled.isNull()&&!enabled.isBoolean())throw invalid();
            additions.add(new ProposedEdge(parent,child,nullableText(value.get("proposed_type"),64),nullableText(value.get("proposed_suppression_scope"),128),enabled.isNull()?null:enabled.booleanValue()));
        }
        Object result=platform.idempotent(a.actor(),a.tenant(),route(request),key,hash(body),a.permissionVersion(),()->{
            // The guarded transaction rechecks membership and every graph reference in the repository.
            asset(asset,a.tenant());return dto(asset,dependencies.append(a.tenant(),a.actor(),version.longValue(),additions,trace(request)));
        });return success(result,request);
    }
    static Map<String,Object> dto(String asset,Draft draft){
        return map("asset_id",asset,"tenant_id",draft.tenantId(),"version",draft.version(),"version_kind","tenant_dependency_graph","updated_at",draft.updatedAt(),"complete",true,
            "proposed_edges",draft.edges().stream().map(e->map("parent_id",e.parentId(),"child_id",e.childId(),"proposed_type",e.proposedType(),"proposed_suppression_scope",e.proposedSuppressionScope(),"proposed_enabled",e.proposedEnabled())).toList(),
            "lifecycle_status","DRAFT","decision_status","pending","configuration_status","CONFIG_PENDING","effective_rules",null,"monitoring_applied",false,"question_ids",List.of("Q20"),"source","postgresql",
            "technical_limits",map("max_additions",AssetDependencyRepository.MAX_ADDITIONS,"max_graph_edges",AssetDependencyRepository.MAX_GRAPH_EDGES,"max_body_bytes",MAX_BODY_BYTES));
    }
    private Access access(HttpServletRequest request,boolean write){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope))throw expired();
        String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(AssetDependencyController::expired);if(!member.active())throw expired();
        if(!member.capabilities().contains(write?"assets:write":"assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无资产依赖动作权限");
        return new Access(actor,scope,member.permissionVersion());
    }
    private String asset(String id,String tenant){String normalized=uuid(mapper.getNodeFactory().textNode(id));if(platform.find(tenant,normalized).isEmpty())throw new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");return normalized;}
    private static String uuid(JsonNode value){if(value==null||!value.isTextual()||!value.textValue().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value.textValue()).toString();}
    private static String nullableText(JsonNode value,int maximum){if(value.isNull())return null;if(!value.isTextual())throw invalid();String text=value.textValue();if(text.isEmpty()||text.codePointCount(0,text.length())>maximum||text.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||text.codePoints().anyMatch(ConfigurationValueValidator::invalidCharacter))throw invalid();return text;}
    private static void fields(JsonNode body,Set<String> expected){if(body==null||!body.isObject())throw invalid();Set<String> names=new HashSet<>();body.fieldNames().forEachRemaining(names::add);if(!names.equals(expected))throw invalid();}
    private static void noQuery(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw invalid();}
    static JsonNode body(HttpServletRequest request){
        try{
            if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();
            try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>MAX_BODY_TOKENS)throw invalid();}
            return INPUT.readTree(bytes);
        }catch(Exception failure){throw invalid();}
    }
    private String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(value))));}catch(Exception failure){throw new IllegalStateException("Dependency request hash unavailable");}}
    private JsonNode canonical(JsonNode value){if(value.isObject()){var result=mapper.createObjectNode();TreeSet<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);keys.forEach(key->result.set(key,canonical(value.get(key))));return result;}if(value.isArray()){var result=mapper.createArrayNode();value.forEach(v->result.add(canonical(v)));return result;}return value;}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","依赖草稿请求字段、版本或技术边界无效");}
}
