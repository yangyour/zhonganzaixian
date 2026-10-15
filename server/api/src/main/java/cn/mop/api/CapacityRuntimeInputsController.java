package cn.mop.api;
import cn.mop.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

@RestController @Profile("remote_test") @RequestMapping("/api/v1/capacity/runtime-inputs")
public final class CapacityRuntimeInputsController {
    private final PlatformRepository platform;private final CapacityRuntimeInputs inputs;
    public CapacityRuntimeInputsController(PlatformRepository platform,CapacityRuntimeInputs inputs){this.platform=platform;this.inputs=inputs;}
    @GetMapping Object read(HttpServletRequest request){
        Membership before=access(request);
        if(!request.getParameterMap().isEmpty())throw new DomainException(400,"VALIDATION_ERROR","容量配置只读取当前租户，不接受查询参数");
        var result=inputs.read(before,trace(request));if(!before.equals(access(request)))throw expired();return success(result,request);
    }
    private Membership access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object selected=request.getSession(false).getAttribute("mop.tenant_id");if(!(selected instanceof String tenant)||tenant.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership m=platform.membership(actor,tenant).orElseThrow(CapacityRuntimeInputsController::expired);
        if(!m.active()||m.permissionVersion()<1||!actor.equals(m.userId())||!tenant.equals(m.tenantId()))throw expired();if(!m.role().equals("padmin")||!m.capabilities().contains("capacity:estimate"))throw new DomainException(403,"ACTION_FORBIDDEN","当前账号没有容量配置读取权限");return m;
    }
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或授权已失效");}
}
