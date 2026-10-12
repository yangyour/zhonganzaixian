package cn.mop.api;

import cn.mop.adapters.monitoring.ZabbixReadClient;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

/** Read-only IP reachability samples; never replaces formal asset lifecycle or vendor health. */
public class NetworkMonitoringService {
    public static final List<String> METRICS=List.of("icmp","loss","latency","tcp_22","tcp_80","tcp_443");
    public static final int INTERVAL=60, FRESHNESS=180, POINT_LIMIT=2000;
    @FunctionalInterface public interface Reader {JsonNode call(String method,Map<String,Object> params);}
    public record Binding(String tenantId,String assetId,String ip,String brand,String model,String hostId,Map<String,String> items){
        public Binding {items=Map.copyOf(items);}
    }
    private final List<Binding> bindings;
    private final Reader reader;
    private final Clock clock;
    private final boolean enabled;
    public NetworkMonitoringService(List<Binding> bindings,Reader reader,Clock clock,boolean enabled){
        this.bindings=List.copyOf(bindings);this.reader=reader;this.clock=clock;this.enabled=enabled;
    }
    public boolean enabled(){return enabled;}
    public List<Binding> bindings(String tenant){return bindings.stream().filter(b->b.tenantId().equals(tenant)).toList();}
    public Optional<Binding> binding(String tenant,String asset){return bindings(tenant).stream().filter(b->b.assetId().equals(asset)).findFirst();}
    public static List<Binding> parseBindings(String raw){
        try{
            if(raw==null||raw.length()>32768)throw unavailable();
            JsonNode root=ZabbixReadClient.JSON.readTree(raw);
            if(!root.isArray()||root.size()<1||root.size()>16)throw unavailable();
            List<Binding> result=new ArrayList<>();Set<String> assets=new HashSet<>(),hosts=new HashSet<>(),items=new HashSet<>();
            for(JsonNode node:root){
                if(!fields(node,Set.of("tenant_id","asset_id","ip","brand","model","host_id","items")))throw unavailable();
                String tenant=text(node,"tenant_id"),asset=text(node,"asset_id"),ip=text(node,"ip"),host=text(node,"host_id");
                if(!uuid(tenant)||!uuid(asset)||!assets.add(asset)||!id(host)||!hosts.add(host)||!privateIp(ip))throw unavailable();
                String brand=text(node,"brand"),model=text(node,"model");
                if(brand.isBlank()||model.isBlank()||brand.length()>100||model.length()>100)throw unavailable();
                if(!fields(node.get("items"),Set.copyOf(METRICS)))throw unavailable();
                Map<String,String> mapping=new LinkedHashMap<>();
                for(String metric:METRICS){String item=text(node.get("items"),metric);if(!id(item)||!items.add(item))throw unavailable();mapping.put(metric,item);}
                result.add(new Binding(tenant,asset,ip,brand,model,host,mapping));
            }
            return List.copyOf(result);
        }catch(Exception failure){throw unavailable();}
    }
    static boolean fields(JsonNode node,Set<String> names){
        if(node==null||!node.isObject()||node.size()!=names.size())return false;
        for(String name:names)if(!node.has(name))return false;return true;
    }
    static String text(JsonNode node,String key){JsonNode value=node.get(key);if(value==null||!value.isTextual())throw unavailable("SOURCE_FIELD_TYPE_INVALID");return value.textValue();}
    static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    static boolean id(String value){return value!=null&&value.matches("[1-9][0-9]{0,19}");}
    static boolean privateIp(String value){
        if(value==null||!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))return false;
        int[] p=Arrays.stream(value.split("\\.")).mapToInt(Integer::parseInt).toArray();
        return Arrays.stream(p).allMatch(n->n<=255)&&(p[0]==10||p[0]==192&&p[1]==168||p[0]==172&&p[1]>=16&&p[1]<=31);
    }
    static String key(Binding b,String metric){
        return switch(metric){case "icmp"->"icmpping["+b.ip()+",3,200,56,1000]";
            case "loss"->"icmppingloss["+b.ip()+",3,200,56,1000]";
            case "latency"->"icmppingsec["+b.ip()+",3,200,56,1000,avg]";
            default->"net.tcp.service[tcp,"+b.ip()+","+metric.substring(4)+"]";};
    }
    private JsonNode call(String method,Map<String,Object> params){
        if(!enabled||reader==null)throw new DomainException(503,"CONFIG_PENDING","基础网络监控尚未配置");
        try{JsonNode value=reader.call(method,params);if(value==null||!value.isArray())throw unavailable();return value;}
        catch(DomainException failure){throw failure;}catch(ZabbixReadClient.ReadFailure failure){throw unavailable(failure.reason);}catch(Exception failure){throw unavailable();}
    }
    private Map<String,JsonNode> current(Binding b){
        JsonNode hosts=call("host.get",map("hostids",List.of(b.hostId()),"output",List.of("hostid","status")));
        if(hosts.size()!=1||!text(hosts.get(0),"hostid").equals(b.hostId())||!text(hosts.get(0),"status").equals("0"))throw unavailable("HOST_MAPPING_UNAVAILABLE");
        JsonNode rows=call("item.get",map("itemids",METRICS.stream().map(b.items()::get).toList(),"output",
            List.of("itemid","hostid","key_","type","value_type","status","state","lastclock","lastvalue","delay","history","units")));
        if(rows.size()!=6)throw unavailable("ITEM_SET_INCOMPLETE");Map<String,JsonNode> found=new LinkedHashMap<>();
        for(JsonNode row:rows){
            String iid=text(row,"itemid"),metric=METRICS.stream().filter(m->b.items().get(m).equals(iid)).findFirst().orElseThrow(NetworkMonitoringService::unavailable);
            if(found.put(metric,row)!=null||!text(row,"hostid").equals(b.hostId())||!text(row,"key_").equals(key(b,metric))
                ||!text(row,"type").equals("3")||!text(row,"value_type").equals(floating(metric)?"0":"3")
                ||!text(row,"delay").equals("60s")||!text(row,"history").equals("7d")
                ||!text(row,"units").equals(unit(metric)))throw unavailable("ITEM_CONFIGURATION_DRIFT");
        }
        return found;
    }
    public Map<String,Object> summary(Binding b,String assetName){
        var rows=current(b);Instant now=clock.instant();Map<String,Object> metrics=new LinkedHashMap<>();
        for(String metric:METRICS){
            JsonNode row=rows.get(metric);long seconds=integer(text(row,"lastclock"));
            String state=!text(row,"status").equals("0")?"DISABLED":!text(row,"state").equals("0")?"COLLECTOR_ERROR":
                seconds==0?"NO_DATA":seconds>now.getEpochSecond()?"CLOCK_SKEW":now.getEpochSecond()-seconds>FRESHNESS?"STALE":"FRESH";
            BigDecimal value=state.equals("FRESH")?number(metric,text(row,"lastvalue")):null;
            metrics.put(metric,map("state",state,"value",value==null?null:value.toPlainString(),"unit",unit(metric),
                "recorded_at",seconds==0?null:Instant.ofEpochSecond(seconds).toString()));
        }
        @SuppressWarnings("unchecked") Map<String,Object> icmp=(Map<String,Object>)metrics.get("icmp");
        String network="FRESH".equals(icmp.get("state"))?("1".equals(icmp.get("value"))?"REACHABLE":"NO_RESPONSE"):"UNKNOWN";
        return map("asset_id",b.assetId(),"name",assetName,"management_ip",b.ip(),"brand",b.brand(),"model",b.model(),
            "network_state",network,"metrics",metrics,"sample_interval_seconds",INTERVAL,"freshness_seconds",FRESHNESS,
            "vendor_metrics_status","DOCUMENTATION_PENDING","device_identity_verified",false,"alert_rule_status","CONFIG_PENDING");
    }
    public Map<String,Object> history(Binding b,String metric,String window){
        if(!METRICS.contains(metric)||!Set.of("HOUR_1","HOURS_24").contains(window))throw new DomainException(400,"VALIDATION_ERROR","指标或时间窗口无效");
        if(!text(current(b).get(metric),"status").equals("0"))throw unavailable();
        Instant to=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from=to.minusSeconds(window.equals("HOUR_1")?3600:86400);
        JsonNode rows=call("history.get",map("history",floating(metric)?0:3,"itemids",List.of(b.items().get(metric)),
            "output",List.of("itemid","clock","ns","value"),"time_from",from.getEpochSecond(),"time_till",to.getEpochSecond(),
            "sortfield",List.of("clock","ns"),"sortorder","ASC","limit",POINT_LIMIT+1));
        if(rows.size()>POINT_LIMIT)throw new DomainException(422,"POINT_LIMIT_EXCEEDED","历史点数超过上限，请缩小查询时间");
        List<Map<String,Object>> points=new ArrayList<>();Instant previous=null;
        for(JsonNode row:rows){
            if(!fields(row,Set.of("itemid","clock","ns","value"))||!text(row,"itemid").equals(b.items().get(metric)))throw unavailable();
            long sec=integer(text(row,"clock")),ns=integer(text(row,"ns"));
            if(sec<from.getEpochSecond()||sec>to.getEpochSecond()||ns>999999999)throw unavailable();
            Instant at=Instant.ofEpochSecond(sec,ns);if(previous!=null&&!at.isAfter(previous))throw unavailable();previous=at;
            points.add(map("recorded_at",at.toString(),"value",number(metric,text(row,"value")).toPlainString()));
        }
        // Revalidate current host/item mapping after history; deleted/disabled objects are not authorized by old history.
        if(!text(current(b).get(metric),"status").equals("0"))throw unavailable();
        return map("asset_id",b.assetId(),"metric",metric,"unit",unit(metric),"window",window,"from",from.toString(),"to",to.toString(),
            "points",points,"sample_interval_seconds",INTERVAL,"gap_after_seconds",FRESHNESS,
            "timestamp_meaning","ZABBIX_RECORD_TIME","complete_window_claimed",false,"source","ZABBIX_SIMPLE_CHECK");
    }
    static BigDecimal number(String metric,String raw){
        if(raw==null||!raw.matches("(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?"))throw unavailable("NUMERIC_ENCODING_UNSUPPORTED");
        BigDecimal value=new BigDecimal(raw),max=new BigDecimal(metric.equals("loss")?"100":metric.equals("latency")?"600":"1");
        if(value.compareTo(max)>0||!floating(metric)&&!Set.of("0","1").contains(raw))throw unavailable("NUMERIC_RANGE_REJECTED");return value;
    }
    static long integer(String raw){try{if(!raw.matches("0|[1-9][0-9]{0,11}"))throw unavailable();return Long.parseLong(raw);}catch(Exception failure){throw unavailable();}}
    static boolean floating(String metric){return metric.equals("loss")||metric.equals("latency");}
    static String unit(String metric){return metric.equals("loss")?"%":metric.equals("latency")?"s":"";}
    public Map<String,Object> readiness(){
        if(!enabled)return map("enabled",false,"ready",false,"reason","NOT_CONFIGURED");
        try{
            if(bindings.isEmpty())throw unavailable();
            for(Binding binding:bindings){var summary=summary(binding,"");var metrics=(Map<?,?>)summary.get("metrics");
                for(Object item:metrics.values())if(!"FRESH".equals(((Map<?,?>)item).get("state")))throw unavailable("SAMPLE_"+((Map<?,?>)item).get("state"));}
            return map("enabled",true,"ready",true,"reason",null);
        }catch(DomainException failure){return map("enabled",true,"ready",false,"reason","SOURCE_MAPPING_OR_FRESH_SAMPLES_UNAVAILABLE","details",failure.details);}
        catch(Exception failure){return map("enabled",true,"ready",false,"reason","SOURCE_MAPPING_OR_FRESH_SAMPLES_UNAVAILABLE");}
    }
    static DomainException unavailable(){return unavailable("INVALID_SOURCE_DATA_OR_STALE_SAMPLES");}
    static DomainException unavailable(String reason){return new DomainException(503,"UPSTREAM_UNAVAILABLE","基础网络监控来源不可用或映射变化，请稍后重试",List.of(map("component","zabbix_basic_reader","reason",reason)));}
}
