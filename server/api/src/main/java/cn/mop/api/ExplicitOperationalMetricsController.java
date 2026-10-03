package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.operations.ExplicitOperationalMetrics;
import cn.mop.domain.operations.ExplicitOperationalMetrics.StaffingInput;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.math.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Explicit sample arithmetic only, never a query of engineer/customer operational metrics. */
@RestController @RequestMapping("/api/v1/operations/explicit-comparisons") @Profile("remote_test")
public final class ExplicitOperationalMetricsController {
    static final int MAX_BODY_BYTES=524288;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(128).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    public ExplicitOperationalMetricsController(PlatformRepository platform){this.platform=platform;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object compare(HttpServletRequest request){String tenant=access(request);if(!request.getParameterMap().isEmpty())throw invalid();return calculate(body(request),tenant,request);}
    private Object calculate(JsonNode input,String tenant,HttpServletRequest request){
        fields(input,Set.of("elapsed_millis","staffing"));
        List<BigInteger> durations=null;JsonNode values=input.get("elapsed_millis");
        if(!values.isNull()){if(!values.isArray()||values.size()>ExplicitOperationalMetrics.MAX_SAMPLES)throw invalid();durations=new ArrayList<>();for(JsonNode value:values)durations.add(integer(value));}
        StaffingInput staffing=null;JsonNode s=input.get("staffing");
        if(!s.isNull()){fields(s,Set.of("active_tenants","on_call_fte","second_line_fte"));staffing=new StaffingInput(integer(s.get("active_tenants")),decimal(s.get("on_call_fte")),decimal(s.get("second_line_fte")));}
        try {
            var median=ExplicitOperationalMetrics.medianElapsedMillis(durations);var ratio=ExplicitOperationalMetrics.tenantsPerFte(staffing);
            return success(map("tenant_id",tenant,"scope","EXPLICIT_OPERATIONAL_COMPARISON","source","EXPLICIT_INPUT_NOT_PROJECT_METRICS","input",input,"decimal_encoding","BASE10_STRINGS","sample_median",map("status",median.status().name(),"reason",median.reason().name(),"formula_version",median.formulaVersion(),"unit",median.unit(),"sample_count",median.sampleCount(),"unknown_count",median.unknownCount(),"median_millis",encode(median.sampleMedianMillis()),"duration_basis_verification",median.durationBasisVerification(),"population_verification",median.populationVerification(),"effective_processing_duration",null),"staffing",map("status",ratio.status().name(),"reason",ratio.reason().name(),"formula_version",ratio.formulaVersion(),"explicit_total_fte",encode(ratio.explicitTotalFte()),"tenants_per_fte",encode(ratio.sampleTenantsPerFte()),"missing_inputs",ratio.missingInputs(),"decimal_precision",ratio.decimalPrecision(),"population_verification",ratio.populationVerification(),"staffing_basis_verification",ratio.staffingBasisVerification(),"effective_tenants_per_fte",null,"target_met",null,"billing_applicable",false),"comparison_saved",false,"formal_metrics_available",false,"business_data_queried",false),request);
        }catch(IllegalArgumentException failure){throw invalid();}
    }
    private String access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object value=request.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant)||tenant.isBlank())throw expired();Membership member=platform.membership(request.getUserPrincipal().getName(),tenant).orElseThrow(ExplicitOperationalMetricsController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!Capabilities.forRole(member.role()).contains("operations:compare_explicit"))throw new DomainException(403,"ACTION_FORBIDDEN","显式经营输入对照仅向当前授权平台管理员开放");return tenant;}
    private static String encode(BigDecimal value){return value==null?null:value.stripTrailingZeros().toPlainString();}
    private static BigInteger integer(JsonNode value){if(value.isNull())return null;if(!value.isTextual()||!value.textValue().matches("0|[1-9][0-9]{0,39}"))throw invalid();return new BigInteger(value.textValue());}
    private static BigDecimal decimal(JsonNode value){if(value.isNull())return null;if(!value.isTextual()||!value.textValue().matches("(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))throw invalid();return new BigDecimal(value.textValue());}
    private static void fields(JsonNode value,Set<String> keys){if(value==null||!value.isObject()||value.size()!=keys.size())throw invalid();value.fieldNames().forEachRemaining(key->{if(!keys.contains(key))throw invalid();});}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>10032)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","须提供elapsed_millis和staffing，未知显式null；耗时至多10000个非负整数字符串或null，人力字段完整，最多40位整数/40位小数，不接受默认口径或租户覆盖");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
