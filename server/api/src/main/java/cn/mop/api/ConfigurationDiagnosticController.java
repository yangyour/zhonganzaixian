package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.ConfigurationDiagnostic;
import cn.mop.domain.configuration.ConfigurationDiagnosticReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;
import static cn.mop.api.ApiResponse.*;

/** Current-tenant metadata diagnostics only; this GET never publishes or selects an effective rule. */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public class ConfigurationDiagnosticController {
    private final PlatformRepository repository;
    private final ConfigurationDiagnosticReader reader;

    public ConfigurationDiagnosticController(PlatformRepository repository,ConfigurationDiagnosticReader reader){this.repository=repository;this.reader=reader;}

    @GetMapping("/settings/effective") Object effective(HttpServletRequest request){
        ConfigurationDiagnosticReader.TrustedContext context=access(request);
        Map<String,String[]> parameters=request.getParameterMap();
        if(!parameters.keySet().equals(Set.of("key"))||parameters.get("key")==null||parameters.get("key").length!=1)throw invalid();
        String key=parameters.get("key")[0];
        if(key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw invalid();
        ConfigurationDiagnostic diagnostic=reader.read(context,key);
        if(diagnostic==null||!key.equals(diagnostic.key())||!context.tenantId().equals(diagnostic.context().tenantId()))
            throw new DomainException(503,"UPSTREAM_UNAVAILABLE","配置诊断来源与当前请求范围不符，未返回替代结果");
        repository.audit(context.actorId(),context.tenantId(),"configuration.effective_diagnostic.read","configuration",key,"SUCCEEDED","CONFIG_PENDING_METADATA_ONLY_NOT_EFFECTIVE",trace(request));
        return success(diagnostic.dto(),request);
    }

    private ConfigurationDiagnosticReader.TrustedContext access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        Object value=request.getSession(false).getAttribute("mop.tenant_id");
        if(!(value instanceof String tenant))throw expired();
        String actor=request.getUserPrincipal().getName();
        Membership membership=repository.membership(actor,tenant).orElseThrow(ConfigurationDiagnosticController::expired);
        if(!membership.active())throw expired();
        if(!Capabilities.forRole(membership.role()).contains("configurations:read"))
            throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无配置诊断读取权限");
        try{return new ConfigurationDiagnosticReader.TrustedContext(actor,tenant,membership.permissionVersion());}
        catch(IllegalArgumentException malformed){throw expired();}
    }
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","会话或当前成员关系已失效");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","配置诊断仅接受一个合法的 key 查询参数");}
}
