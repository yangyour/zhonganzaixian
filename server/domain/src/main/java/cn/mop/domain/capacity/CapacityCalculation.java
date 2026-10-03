package cn.mop.domain.capacity;
import java.math.*;
import java.util.*;

/** Bounded explicit calculation document shared by estimate and future immutable draft storage. */
public record CapacityCalculation(CapacityEstimate.Inputs inputs,CapacityEstimate.Model model,ProxyBufferEstimate.Inputs proxyBuffer) {
    public static final String CAPACITY_FORMULA="contract-m10-7-capacity-v1",BUFFER_FORMULA="contract-m4-15-buffer-v1";
    public CapacityCalculation {
        if(inputs!=null){count(inputs.hosts());count(inputs.itemsPerHost());for(BigDecimal d:Arrays.asList(inputs.intervalSeconds(),inputs.bytesPerValue(),inputs.trendRatio(),inputs.headroomRatio()))decimal(d);}
        if(model!=null){if(model.version()!=null)text(model.version(),100,false);decimal(model.bytesPerTrendRow());decimal(model.poolNvpsLimit());}
        if(proxyBuffer!=null)for(BigDecimal d:Arrays.asList(proxyBuffer.siteNvps(),proxyBuffer.outageSeconds(),proxyBuffer.bytesPerValue(),proxyBuffer.marginFactor()))decimal(d);
        CapacityEstimate.calculate(inputs,model);ProxyBufferEstimate.calculate(proxyBuffer);
    }
    public record Snapshot(String capacityFormulaVersion,String bufferFormulaVersion,CapacityEstimate.Result capacity,ProxyBufferEstimate.Result proxyBuffer) {
        public Snapshot {if(!CAPACITY_FORMULA.equals(capacityFormulaVersion)||!BUFFER_FORMULA.equals(bufferFormulaVersion)||capacity==null||proxyBuffer==null)throw invalid();}
    }
    public Snapshot snapshot(){return new Snapshot(CAPACITY_FORMULA,BUFFER_FORMULA,CapacityEstimate.calculate(inputs,model),ProxyBufferEstimate.calculate(proxyBuffer));}
    private static void count(BigInteger value){if(value!=null&&(value.signum()<0||value.toString().length()>40))throw invalid();}
    private static void decimal(BigDecimal value){if(value!=null&&(value.signum()<0||value.scale()>40||value.scale() < -40||value.precision()-value.scale()>40))throw invalid();}
    static void text(String value,int max,boolean multiline){if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&!(multiline&&(cp=='\n'||cp=='\r'||cp=='\t'))))throw invalid();}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid explicit capacity draft input");}
}
