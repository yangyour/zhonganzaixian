package cn.mop.api;

import cn.mop.adapters.cmdb.CustomerCmdbDictionary;
import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Authenticated reference metadata only. Never returns asset values, credential references or customer contacts. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/cmdb/type-catalog")
public final class CmdbTypeCatalogController {
    private final PlatformRepository platform;
    private final CustomerCmdbDictionary dictionary;
    public CmdbTypeCatalogController(PlatformRepository platform,ObjectMapper mapper){this.platform=platform;this.dictionary=CustomerCmdbDictionary.load(mapper);}
    @GetMapping Object get(HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        String actor=request.getUserPrincipal().getName();Object scope=request.getSession(false).getAttribute("mop.tenant_id");
        if(!(scope instanceof String tenant)||tenant.isBlank())throw expired();
        Membership member=platform.membership(actor,tenant).orElseThrow(CmdbTypeCatalogController::expired);
        if(!member.active()||member.permissionVersion()<1||!actor.equals(member.userId())||!tenant.equals(member.tenantId()))throw expired();
        if(!member.capabilities().contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前成员没有资产读取权限");
        if(!request.getParameterMap().isEmpty()||request.getQueryString()!=null)throw new DomainException(400,"VALIDATION_ERROR","字典只读且不接受租户、类型或生效覆盖参数");
        JsonNode doc=dictionary.document();var parents=new ArrayList<Map<String,Object>>();
        for(JsonNode row:doc.path("primary_types"))parents.add(map("code",row.path("code").asText(),"label",row.path("label").asText()));
        var subtypes=new ArrayList<Map<String,Object>>();
        for(var row:dictionary.subtypes())subtypes.add(map("code",row.code(),"label",row.label(),"parent",row.parent(),"monitoring_mapping_resolved",row.monitoringMappingResolved(),"source_sheet",row.sourceSheet(),"source_row",row.sourceRow()));
        var attributes=new ArrayList<Map<String,Object>>();
        for(JsonNode row:doc.path("attributes"))attributes.add(map("code",row.path("code").asText(),"label",row.path("label").asText(),
            "group",row.path("group").asText(),"data_type",row.path("data_type").asText(),"required_note",row.path("required_note").asText(),
            "derived", "是".equals(row.path("derived_read_only_note").asText()),"source_sheet",row.path("source").path("sheet").asText(),"source_row",row.path("source").path("row").asInt()));
        var methods=new ArrayList<Map<String,Object>>();var dimensions=new ArrayList<Map<String,Object>>();
        for(String field:List.of("collect_method","lifecycle_state","sync_state","health_state","billable")){
            var values=new ArrayList<Map<String,Object>>();
            for(JsonNode row:doc.path("enum_values"))if(field.equals(row.path("field").asText()))values.add(map("code",row.path("code").asText(),"label",row.path("label").asText()));
            if(field.equals("collect_method"))methods.addAll(values);else dimensions.add(map("field",field,"values",values));
        }
        var issues=new ArrayList<Map<String,Object>>();
        for(JsonNode issue:doc.path("unresolved"))issues.add(map("code",issue.path("code").asText(),"resolution",null));
        return success(map("tenant_id",tenant,"catalog_version",CustomerCmdbDictionary.VERSION,"catalog_hash",CustomerCmdbDictionary.SHA256,
            "scope","REFERENCE_ONLY_NOT_ASSET_DATA","primary_types",parents,"subtypes",subtypes,"attributes",attributes,
            "collection_methods",methods,"state_dimensions",dimensions,"unresolved",issues,
            "runtime_publication",false,"asset_mutations",false,"production_approved",false),request);
    }
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
