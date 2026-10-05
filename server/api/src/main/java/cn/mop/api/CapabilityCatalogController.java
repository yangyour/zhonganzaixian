package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.authorization.CapabilityCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Readonly technical review; never creates roles, changes grants or activates pending Q09 policy. */
@RestController @RequestMapping("/api/v1/authorization/capability-catalog") @Profile("remote_test")
public final class CapabilityCatalogController {
    private final PlatformRepository platform;
    public CapabilityCatalogController(PlatformRepository platform){this.platform=platform;}
    @GetMapping Object get(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object value=request.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();Membership member=platform.membership(request.getUserPrincipal().getName(),tenant).orElseThrow(CapabilityCatalogController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!"padmin".equals(member.role())||!member.capabilities().contains("configurations:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前代码声明核对仅开放平台管理员，不修改运行权限");if(!request.getParameterMap().isEmpty())throw new DomainException(400,"VALIDATION_ERROR","能力目录只读且无query参数，不接受角色/租户/生效覆盖");
        var catalog=CapabilityCatalog.current();return success(map("tenant_id",tenant,"scope","CODE_DECLARATIONS_NOT_TENANT_ASSIGNMENTS","catalog_version","mop-capability-declarations-v1","source","STATIC_CODE_CAPABILITIES","declaration_hash",catalog.declarationHash(),"roles",catalog.roles(),"dimensions",catalog.dimensions(),"entries",catalog.entries(),"coverage","DECLARED_CAPABILITIES_ONLY","entry_count",catalog.entries().size(),"resource_authorization_required",true,"resource_access_decision",null,"configuration_driven_authorization",false,"dynamic_roles_status","NOT_IMPLEMENTED","effective_customer_matrix",null,"customer_approval_status","UNVERIFIED","customer_signature",null,"supplier_signature",null,"subject_bindings_included",false,"production_authority_verified",false,"configuration_key","auth.role_matrix_and_assignments","configuration_status","CONFIG_PENDING","actions_this_request",map("role_created",false,"permissions_changed",false,"configuration_published",false)),request);
    }
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
