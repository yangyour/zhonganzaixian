package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.ThresholdConditionAnalysis;
import cn.mop.domain.monitoring.ThresholdConditionAnalysis.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Explicit candidate diagnostics only. Never reads a monitoring provider, registry value or customer approval. */
@RestController @RequestMapping("/api/v1/monitoring/threshold-diagnostics") @Profile("remote_test")
public final class ThresholdDiagnosticController {
    static final int MAX_BODY_BYTES=16384;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(128).maxNumberLength(128).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    public ThresholdDiagnosticController(PlatformRepository platform){this.platform=platform;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object analyze(HttpServletRequest request){
        String tenant=access(request);if(!request.getParameterMap().isEmpty())throw invalid();Input input;
        try{input=ThresholdConditionAnalysis.fromWire(body(request));}catch(IllegalArgumentException failure){throw invalid();}
        Result result=ThresholdConditionAnalysis.analyze(input);
        return success(map("tenant_id",tenant,"source","EXPLICIT_INPUT","scope",result.scope(),"analysis_version","threshold-half-lines-v1","inputs",wire(input),"status",result.status(),"direction_consistent",result.directionConsistent(),"critical_strict_subset_of_warning",result.criticalStrictSubsetOfWarning(),"recovery_overlaps_warning",result.recoveryOverlapsWarning(),"recovery_overlaps_critical",result.recoveryOverlapsCritical(),"durations_nonnegative",result.durationsNonnegative(),"issues",result.issues().stream().map(i->map("code",i.code(),"kind",i.kind())).toList(),"publishable",false,"effective_rule",null,"customer_confirmation","UNVERIFIED","physical_range_check","NOT_RUN","units_converted",false,"monitoring_applied",false,"time_series_evaluated",false,"rule_saved",false,"writes_performed",false,"tenant_authorization","CURRENT_MEMBERSHIP_VERIFIED"),request);
    }
    private String access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object selected=request.getSession(false).getAttribute("mop.tenant_id");if(!(selected instanceof String tenant)||tenant.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership current=platform.membership(actor,tenant).orElseThrow(ThresholdDiagnosticController::expired);if(!current.active()||current.permissionVersion()<1||!actor.equals(current.userId())||!tenant.equals(current.tenantId()))throw expired();if(!"padmin".equals(current.role())||!current.capabilities().contains("configurations:read"))throw new DomainException(403,"ACTION_FORBIDDEN","仅当前授权平台配置管理员可诊断显式共享阈值候选");return tenant;}
    static Object body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>256)throw invalid();}return INPUT.readValue(bytes,Object.class);}catch(Exception failure){throw invalid();}}
    static Map<String,Object> wire(Input input){return map("direction",input.direction(),"warning",wire(input.warning()),"critical",wire(input.critical()),"recovery",wire(input.recovery()),"trigger_hold_seconds",number(input.triggerHoldSeconds()),"recovery_hold_seconds",number(input.recoveryHoldSeconds()));}
    private static Object wire(Condition input){return input==null?null:map("comparison",input.comparison(),"threshold",number(input.threshold()),"unit",input.unit());}
    private static String number(BigDecimal value){return value==null?null:value.toPlainString();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","阈值诊断须提供明确方向、三个候选条件及两个持续时长；未知用null，十进制用字符串；重复键、范围覆盖和超限拒绝");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
