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
    /** Strict JSON-compatible map codec; numeric facts are strings so their decimal precision is not lost. */
    public static CapacityCalculation fromWire(Object value){
        Map<?,?> document=fields(value,Set.of("inputs","model","proxy_buffer"));CapacityEstimate.Inputs inputs=null;CapacityEstimate.Model model=null;ProxyBufferEstimate.Inputs buffer=null;
        if(document.get("inputs")!=null){Map<?,?> v=fields(document.get("inputs"),Set.of("hosts","items_per_host","interval_seconds","history_days","bytes_per_value","trend_ratio","trend_days","headroom_ratio"));inputs=new CapacityEstimate.Inputs(integer(v.get("hosts")),integer(v.get("items_per_host")),number(v.get("interval_seconds")),days(v.get("history_days")),number(v.get("bytes_per_value")),number(v.get("trend_ratio")),days(v.get("trend_days")),number(v.get("headroom_ratio")));}
        if(document.get("model")!=null){Map<?,?> v=fields(document.get("model"),Set.of("version","bytes_per_trend_row","pool_nvps_limit"));Object label=v.get("version");if(label!=null&&!(label instanceof String))throw invalid();model=new CapacityEstimate.Model((String)label,number(v.get("bytes_per_trend_row")),number(v.get("pool_nvps_limit")));}
        if(document.get("proxy_buffer")!=null){Map<?,?> v=fields(document.get("proxy_buffer"),Set.of("site_nvps","outage_seconds","bytes_per_value","margin_factor"));buffer=new ProxyBufferEstimate.Inputs(number(v.get("site_nvps")),number(v.get("outage_seconds")),number(v.get("bytes_per_value")),number(v.get("margin_factor")));}
        return new CapacityCalculation(inputs,model,buffer);
    }
    public Map<String,Object> wire(){return map("inputs",inputs==null?null:map("hosts",string(inputs.hosts()),"items_per_host",string(inputs.itemsPerHost()),"interval_seconds",string(inputs.intervalSeconds()),"history_days",inputs.historyDays(),"bytes_per_value",string(inputs.bytesPerValue()),"trend_ratio",string(inputs.trendRatio()),"trend_days",inputs.trendDays(),"headroom_ratio",string(inputs.headroomRatio())),"model",model==null?null:map("version",model.version(),"bytes_per_trend_row",string(model.bytesPerTrendRow()),"pool_nvps_limit",string(model.poolNvpsLimit())),"proxy_buffer",proxyBuffer==null?null:map("site_nvps",string(proxyBuffer.siteNvps()),"outage_seconds",string(proxyBuffer.outageSeconds()),"bytes_per_value",string(proxyBuffer.bytesPerValue()),"margin_factor",string(proxyBuffer.marginFactor())));}
    private static Map<String,Object> map(Object...pairs){var values=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return values;}
    private static String string(Object value){return value==null?null:value instanceof BigDecimal decimal?decimal.toPlainString():value.toString();}
    private static Map<?,?> fields(Object value,Set<String> keys){if(!(value instanceof Map<?,?> map)||!map.keySet().equals(keys))throw invalid();return map;}
    private static BigInteger integer(Object value){if(value==null)return null;if(!(value instanceof String s)||!s.matches("0|[1-9][0-9]{0,39}"))throw invalid();return new BigInteger(s);}
    private static BigDecimal number(Object value){if(value==null)return null;if(!(value instanceof String s)||!s.matches("(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))throw invalid();return new BigDecimal(s);}
    private static Integer days(Object value){if(value==null)return null;BigInteger n;if(value instanceof BigInteger b)n=b;else if(value instanceof Integer||value instanceof Long||value instanceof Short||value instanceof Byte)n=BigInteger.valueOf(((Number)value).longValue());else throw invalid();if(n.signum()<0||n.compareTo(BigInteger.valueOf(Integer.MAX_VALUE))>0)throw invalid();return n.intValue();}
    public record Snapshot(String capacityFormulaVersion,String bufferFormulaVersion,CapacityEstimate.Result capacity,ProxyBufferEstimate.Result proxyBuffer) {
        public Snapshot {if(!CAPACITY_FORMULA.equals(capacityFormulaVersion)||!BUFFER_FORMULA.equals(bufferFormulaVersion)||capacity==null||proxyBuffer==null)throw invalid();}
    }
    public Snapshot snapshot(){return new Snapshot(CAPACITY_FORMULA,BUFFER_FORMULA,CapacityEstimate.calculate(inputs,model),ProxyBufferEstimate.calculate(proxyBuffer));}
    private static void count(BigInteger value){if(value!=null&&(value.signum()<0||value.toString().length()>40))throw invalid();}
    private static void decimal(BigDecimal value){if(value!=null&&(value.signum()<0||value.scale()>40||value.scale() < -40||value.precision()-value.scale()>40))throw invalid();}
    static void text(String value,int max,boolean multiline){if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&!(multiline&&(cp=='\n'||cp=='\r'||cp=='\t'))))throw invalid();}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid explicit capacity draft input");}
}
