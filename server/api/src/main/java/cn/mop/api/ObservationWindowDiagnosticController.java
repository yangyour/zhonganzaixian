package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.monitoring.ObservationWindowAnalysis;
import cn.mop.domain.monitoring.ObservationWindowAnalysis.*;
import cn.mop.domain.monitoring.ThresholdConditionAnalysis;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Explicit, bounded diagnostic only; never loads measured samples, publishes a rule or changes event state. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/monitoring/observation-window-diagnostics")
public final class ObservationWindowDiagnosticController {
    static final int MAX_BODY_BYTES=524288,MAX_TOKENS=16000;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(128).maxNumberLength(128).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    public ObservationWindowDiagnosticController(PlatformRepository platform){this.platform=platform;}
    @ModelAttribute void privateHeaders(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object analyze(HttpServletRequest request){
        Membership member=access(request);if(!request.getParameterMap().isEmpty())throw invalid();Object raw=body(request);ObservationWindowAnalysis.Input input;
        try{input=parse(raw);}catch(DomainException failure){throw failure;}catch(RuntimeException failure){throw invalid();}
        Result result=ObservationWindowAnalysis.analyze(input);
        return success(map("tenant_id",member.tenantId(),"actor_id",member.userId(),"permission_version",member.permissionVersion(),"source","EXPLICIT_INPUT","scope",result.scope(),"analysis_version",result.algorithm(),"inputs",raw,
            "status",result.status(),"reasons",result.reasons(),"latest_sample_at",instant(result.latestSampleAt()),"matching_span_started_at",instant(result.matchingSpanStartedAt()),"matching_sample_count",result.matchingSampleCount(),"observed_span_seconds",result.observedSpanSeconds()==null?null:result.observedSpanSeconds().toPlainString(),"candidate_span_reached",result.candidateSpanReached(),
            "continuity_claim",result.continuityClaim(),"customer_confirmation",result.customerConfirmation(),"physical_range_check",result.physicalRangeCheck(),"publishable",false,"effective_rule",null,"units_converted",false,"monitoring_applied",false,"event_state_updated",false,"recovery_confirmed",false,"writes_performed",false,"tenant_authorization","CURRENT_MEMBERSHIP_VERIFIED"),request);
    }
    static ObservationWindowAnalysis.Input parse(Object raw){
        var root=fields(raw,Set.of("condition","hold_seconds","max_gap_seconds","observed_at","samples"));
        var candidate=new LinkedHashMap<String,Object>();candidate.put("direction",null);candidate.put("warning",root.get("condition"));candidate.put("critical",null);candidate.put("recovery",null);candidate.put("trigger_hold_seconds",root.get("hold_seconds"));candidate.put("recovery_hold_seconds",root.get("max_gap_seconds"));
        var parameters=ThresholdConditionAnalysis.fromWire(candidate);
        if(!(root.get("samples") instanceof List<?> rows)||rows.size()>ObservationWindowAnalysis.MAX_SAMPLES)throw invalid();
        List<Sample> samples=new ArrayList<>();
        for(Object row:rows){var s=fields(row,Set.of("recorded_at","state","value","unit"));if(!(s.get("state") instanceof String state)||s.get("unit")!=null&&!(s.get("unit") instanceof String))throw invalid();samples.add(new Sample(time(s.get("recorded_at")),SampleState.valueOf(state),decimal(s.get("value")),(String)s.get("unit")));}
        return new ObservationWindowAnalysis.Input(parameters.warning(),parameters.triggerHoldSeconds(),parameters.recoveryHoldSeconds(),time(root.get("observed_at")),samples);
    }
    private Membership access(HttpServletRequest r){if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();Object value=r.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();String actor=r.getUserPrincipal().getName();var member=platform.membership(actor,tenant).orElseThrow(ObservationWindowDiagnosticController::expired);if(!member.active()||member.permissionVersion()<1||!actor.equals(member.userId())||!tenant.equals(member.tenantId()))throw expired();if(!"padmin".equals(member.role())||!member.capabilities().contains("configurations:read"))throw new DomainException(403,"ACTION_FORBIDDEN","仅当前授权平台配置管理员可诊断显式采样候选");return member;}
    static Object body(HttpServletRequest r){try{if(r.getContentLengthLong()>MAX_BODY_BYTES)throw tooLarge();byte[] bytes=r.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw tooLarge();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>MAX_TOKENS)throw tooLarge();}return INPUT.readValue(bytes,Object.class);}catch(DomainException failure){throw failure;}catch(Exception failure){throw invalid();}}
    private static Map<?,?> fields(Object value,Set<String> keys){if(!(value instanceof Map<?,?> v)||v.size()!=keys.size()||!v.keySet().equals(keys))throw invalid();return v;}
    private static BigDecimal decimal(Object value){if(value==null)return null;if(!(value instanceof String text)||!text.matches("-?(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))throw invalid();var number=new BigDecimal(text);if(text.startsWith("-")&&number.signum()==0)throw invalid();return number;}
    private static Instant time(Object value){if(!(value instanceof String text)||!text.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?Z"))throw invalid();try{return OffsetDateTime.parse(text).toInstant();}catch(DateTimeException failure){throw invalid();}}
    private static String instant(Instant value){return value==null?null:value.toString();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","观察窗只读诊断字段、精确小数字符串、样本状态或时序不符合约定；不接受租户/规则/恢复权限覆盖");}
    private static DomainException tooLarge(){return new DomainException(413,"REQUEST_TOO_LARGE","观察窗诊断超过有界技术保护，未截断计算");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
