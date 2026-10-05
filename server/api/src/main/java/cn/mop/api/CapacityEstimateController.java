package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.capacity.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.math.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Read-only explicit calculations, not saved scenarios, measured pool capacity or procurement approval. */
@RestController @RequestMapping("/api/v1/capacity/estimates") @Profile("remote_test")
public final class CapacityEstimateController {
    static final int MAX_BODY_BYTES=16384;
    private static final Set<String> INPUTS=Set.of("hosts","items_per_host","interval_seconds","history_days","bytes_per_value","trend_ratio","trend_days","headroom_ratio"),MODEL=Set.of("version","bytes_per_trend_row","pool_nvps_limit"),BUFFER=Set.of("site_nvps","outage_seconds","bytes_per_value","margin_factor");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(256).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    public CapacityEstimateController(PlatformRepository platform){this.platform=platform;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object estimate(HttpServletRequest request){access(request);return calculate(body(request),request);}
    Object calculate(JsonNode input,HttpServletRequest request){
        String tenant=access(request);if(!request.getParameterMap().isEmpty())throw invalid();fields(input,Set.of("inputs","model","proxy_buffer"));
        CapacityEstimate.Inputs values=inputs(input.get("inputs"));CapacityEstimate.Model model=model(input.get("model"));ProxyBufferEstimate.Inputs buffer=buffer(input.get("proxy_buffer"));
        CapacityEstimate.Result result;ProxyBufferEstimate.Result proxy;
        try{result=CapacityEstimate.calculate(values,model);proxy=ProxyBufferEstimate.calculate(buffer);}catch(IllegalArgumentException failure){throw invalid();}
        return success(map("tenant_id",tenant,"scope","EXPLICIT_CAPACITY_ESTIMATE","source","EXPLICIT_INPUT_NOT_MEASURED_DATA","formula_version","contract-m10-7-capacity-v1","input",input,"status",result.status().name(),"model_version",result.modelVersion(),"missing_inputs",result.missingInputs(),"outputs",outputs(result.outputs()),"decimal_precision","DECIMAL128","decimal_encoding","BASE10_STRINGS","estimate_only",true,"scenario_saved",false,"model_approval_status","UNVERIFIED","measurement_verification","UNVERIFIED","approved_pool_nvps_limit",null,"measured_capacity",null,"production_capacity_verified",false,"poc_status","NOT_RUN","proxy_buffer",map("status",proxy.status().name(),"formula_version",proxy.formulaVersion(),"missing_inputs",proxy.missingInputs(),"estimated_bytes",decimal(proxy.estimatedBytes()),"margin_factor_below_one",buffer==null||buffer.marginFactor()==null?null:buffer.marginFactor().compareTo(BigDecimal.ONE)<0,"measurement_verified",false,"failover_verified",false),"units",map("nvps","values_per_second","storage","bytes","counts","integers")),request);
    }
    private static Object outputs(CapacityEstimate.Outputs v){return v==null?null:map("nvps",decimal(v.nvps()),"daily_bytes",decimal(v.dailyBytes()),"history_bytes",decimal(v.historyBytes()),"trend_bytes",decimal(v.trendBytes()),"single_node_bytes",decimal(v.singleNodeBytes()),"two_node_bytes",decimal(v.twoNodeBytes()),"peak_nvps",decimal(v.peakNvps()),"total_items",v.totalItems().toString(),"pools",v.pools().toString());}
    private static String decimal(BigDecimal value){return value==null?null:value.stripTrailingZeros().toPlainString();}
    private static CapacityEstimate.Inputs inputs(JsonNode v){if(v.isNull())return null;fields(v,INPUTS);return new CapacityEstimate.Inputs(integer(v.get("hosts")),integer(v.get("items_per_host")),number(v.get("interval_seconds")),days(v.get("history_days")),number(v.get("bytes_per_value")),number(v.get("trend_ratio")),days(v.get("trend_days")),number(v.get("headroom_ratio")));}
    private static CapacityEstimate.Model model(JsonNode v){if(v.isNull())return null;fields(v,MODEL);return new CapacityEstimate.Model(label(v.get("version")),number(v.get("bytes_per_trend_row")),number(v.get("pool_nvps_limit")));}
    private static ProxyBufferEstimate.Inputs buffer(JsonNode v){if(v.isNull())return null;fields(v,BUFFER);return new ProxyBufferEstimate.Inputs(number(v.get("site_nvps")),number(v.get("outage_seconds")),number(v.get("bytes_per_value")),number(v.get("margin_factor")));}
    private static BigInteger integer(JsonNode v){if(v.isNull())return null;if(!v.isTextual()||!v.textValue().matches("0|[1-9][0-9]{0,39}"))throw invalid();return new BigInteger(v.textValue());}
    private static BigDecimal number(JsonNode v){if(v.isNull())return null;if(!v.isTextual()||!v.textValue().matches("(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))throw invalid();return new BigDecimal(v.textValue());}
    private static Integer days(JsonNode v){if(v.isNull())return null;if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<0)throw invalid();return v.intValue();}
    private static String label(JsonNode v){if(v.isNull())return null;if(!v.isTextual())throw invalid();String s=v.textValue();if(s.isEmpty()||s.codePointCount(0,s.length())>100||s.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||s.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE))throw invalid();return s;}
    private static void fields(JsonNode v,Set<String> keys){if(v==null||!v.isObject()||v.size()!=keys.size())throw invalid();v.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    private String access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership m=platform.membership(actor,scope).orElseThrow(CapacityEstimateController::expired);if(!m.active()||m.permissionVersion()<1)throw expired();if(!m.capabilities().contains("capacity:estimate"))throw new DomainException(403,"ACTION_FORBIDDEN","容量显式估算仅向当前授权平台管理员开放");return scope;}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>64)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","容量估算须显式完整字段，未知用null；数量/小数用非负十进制字符串（最多40位整数及40位小数），天数为非负32位整数。间隔/每值字节/池上限/缓冲倍率正数，比例0至1，无默认系数");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
