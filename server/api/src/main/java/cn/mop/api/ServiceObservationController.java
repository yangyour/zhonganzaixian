package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.dashboard.ServiceObservationReader;
import cn.mop.domain.dashboard.ServiceObservationReader.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.success;

@RestController
@Profile("remote_test")
public final class ServiceObservationController {
    private final PlatformRepository platform;private final ServiceObservationReader reader;private final ObjectMapper mapper;
    public ServiceObservationController(PlatformRepository platform,ServiceObservationReader reader,ObjectMapper mapper) {
        this.platform=platform;this.reader=reader;
        this.mapper=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    @GetMapping("/api/v1/dashboard/service-observations") Object read(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        if(!request.getParameterMap().isEmpty())throw new DomainException(400,"VALIDATION_ERROR","服务观测范围由当前租户和数据库时钟确定");
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        var selected=request.getSession(false).getAttribute("mop.tenant_id");var actor=request.getUserPrincipal().getName();
        if(!(selected instanceof String tenant)||!uuid(tenant)||!uuid(actor))throw expired();
        var member=platform.membership(actor,tenant).orElseThrow(ServiceObservationController::expired);
        if(!member.active()||!tenant.equals(member.tenantId())||!actor.equals(member.userId()))throw expired();
        if(!Set.of("msp","padmin").contains(member.role())||!member.capabilities().containsAll(List.of("dashboard:read","assets:read","tickets:read","sla_scenarios:read")))throw new DomainException(403,"ACTION_FORBIDDEN","缺少服务观测读取权限");
        var result=reader.read(new Access(UUID.fromString(tenant),UUID.fromString(actor),member.permissionVersion()));
        if(result==null||!result.tenantId().toString().equals(tenant)||!result.actorId().toString().equals(actor)||result.permissionVersion()!=member.permissionVersion())throw new DomainException(503,"SERVICE_OBSERVATION_UNAVAILABLE","服务观测授权范围不一致");
        var dto=mapper.convertValue(result,new TypeReference<Map<String,Object>>(){});
        dto.put("metric_version",ServiceObservationReader.VERSION);dto.put("source","POSTGRESQL_CONFIGURED_SERVICE_OBSERVATIONS");
        dto.put("timezone","Asia/Shanghai");dto.put("mapping_status","EXPLICIT_SERVER_CONFIGURATION_NOT_CUSTOMER_ACCEPTANCE");
        dto.put("positive_health_coverage_loaded",false);dto.put("timeline_row_limit",8);dto.put("service_count",result.services().size());dto.put("writes_performed",false);
        DashboardReadAuthorization.recheck(request,platform,member);
        return success(dto,request);
    }
    private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员权限已失效");}
}
