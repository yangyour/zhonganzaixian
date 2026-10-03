package cn.mop.domain.capacity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Explicit M4-15/M10-7 arithmetic; supplied byte size is not verified measurement evidence. */
public final class ProxyBufferEstimate {
    private ProxyBufferEstimate() {}
    public record Inputs(BigDecimal siteNvps,BigDecimal outageSeconds,BigDecimal bytesPerValue,BigDecimal marginFactor) {}
    public enum Status { ESTIMATED, UNRESOLVED }
    public record Result(Status status,BigDecimal estimatedBytes,List<String> missingInputs) {
        public Result {
            missingInputs=List.copyOf(missingInputs);
            if(status==null||status==Status.ESTIMATED&&(estimatedBytes==null||estimatedBytes.signum()<0||!missingInputs.isEmpty())||status==Status.UNRESOLVED&&(estimatedBytes!=null||missingInputs.isEmpty()))throw new IllegalArgumentException("Inconsistent buffer estimate");
        }
        public String formulaVersion(){return "contract-m4-15-buffer-v1";}
        public String decimalPrecision(){return "DECIMAL128";}
        public boolean estimateOnly(){return true;}
        public boolean measurementVerified(){return false;}
        public boolean failoverVerified(){return false;}
    }
    public static Result calculate(Inputs input){
        if(input==null)return new Result(Status.UNRESOLVED,null,List.of("proxy_buffer"));
        nonnegative(input.siteNvps());nonnegative(input.outageSeconds());positive(input.bytesPerValue());positive(input.marginFactor());
        List<String> missing=new ArrayList<>();absent(missing,"site_nvps",input.siteNvps());absent(missing,"outage_seconds",input.outageSeconds());absent(missing,"bytes_per_value",input.bytesPerValue());absent(missing,"margin_factor",input.marginFactor());
        if(!missing.isEmpty())return new Result(Status.UNRESOLVED,null,missing);
        // Multiplication is exact until a single DECIMAL128 display rounding. No binary floating point.
        BigDecimal bytes=input.siteNvps().multiply(input.outageSeconds()).multiply(input.bytesPerValue()).multiply(input.marginFactor()).round(MathContext.DECIMAL128);
        return new Result(Status.ESTIMATED,bytes,List.of());
    }
    private static void absent(List<String> values,String key,Object value){if(value==null)values.add(key);}
    private static void nonnegative(BigDecimal value){if(value!=null&&value.signum()<0)throw new IllegalArgumentException("Negative buffer input");}
    private static void positive(BigDecimal value){if(value!=null&&value.signum()<=0)throw new IllegalArgumentException("Nonpositive byte size or explicit multiplier");}
}
