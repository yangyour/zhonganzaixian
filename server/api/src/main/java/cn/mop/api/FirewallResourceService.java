package cn.mop.api;

import cn.mop.adapters.af.AfCollectorMain;
import cn.mop.adapters.monitoring.ZabbixReadClient;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static cn.mop.api.ApiResponse.map;
import static cn.mop.api.NetworkMonitoringService.*;

/** Real AF samples persisted in the existing Zabbix history store. */
public final class FirewallResourceService {
    public record Binding(String tenantId,String assetId,String ip,String hostId,Map<String,String> items,String allowedHosts,String tlsMode){public Binding{items=Map.copyOf(items);}}
    private final Binding binding;private final Reader reader;private final Clock clock;
    public FirewallResourceService(Binding binding,Reader reader,Clock clock){this.binding=binding;this.reader=reader;this.clock=clock;}
    public boolean enabled(){return binding!=null;}
    public boolean matches(NetworkMonitoringService.Binding basic){return binding!=null&&binding.tenantId().equals(basic.tenantId())&&binding.assetId().equals(basic.assetId())&&binding.ip().equals(basic.ip());}
    public static Binding parse(String raw){
        try{
            if(raw==null||raw.length()>8192)throw unavailable();var n=ZabbixReadClient.JSON.readTree(raw);
            if(!fields(n,Set.of("tenant_id","asset_id","ip","host_id","items","allowed_hosts","tls_mode"))||!fields(n.get("items"),Set.of("cpu","memory","state")))throw unavailable();
            String tenant=text(n,"tenant_id"),asset=text(n,"asset_id"),ip=text(n,"ip"),host=text(n,"host_id"),allowed=text(n,"allowed_hosts"),tls=text(n,"tls_mode");
            if(!uuid(tenant)||!uuid(asset)||!id(host)||!ip.equals("192.169.11.1")||!Set.of("SYSTEM","CA_FILE","TEST_INSECURE").contains(tls)||allowed.length()>128)throw unavailable();
            for(String address:allowed.split(","))if(!privateIp(address)&&!address.equals("127.0.0.1"))throw unavailable();
            Map<String,String> ids=new HashMap<>();for(String m:List.of("cpu","memory","state")){String item=text(n.get("items"),m);if(!id(item))throw unavailable();ids.put(m,item);}if(new HashSet<>(ids.values()).size()!=3)throw unavailable();
            return new Binding(tenant,asset,ip,host,ids,allowed,tls);
        }catch(Exception failure){throw unavailable();}
    }
    private JsonNode call(String method,Map<String,Object> params){
        if(!enabled())throw new DomainException(503,"CONFIG_PENDING","防火墙资源采集尚未配置");
        try{var rows=reader.call(method,params);if(rows==null||!rows.isArray())throw unavailable();return rows;}catch(DomainException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private Map<String,JsonNode> current(){
        var hosts=call("host.get",map("hostids",List.of(binding.hostId()),"output",List.of("hostid","host","status")));
        if(hosts.size()!=1||!text(hosts.get(0),"hostid").equals(binding.hostId())||!text(hosts.get(0),"host").equals("mop-lan-af-api-192169111")||!text(hosts.get(0),"status").equals("0"))throw unavailable();
        var rows=call("item.get",map("itemids",binding.items().values(),"output",List.of("itemid","hostid","key_","type","value_type","history","trends","status","state","lastclock","lastvalue","units","trapper_hosts")));
        if(rows.size()!=3)throw unavailable();Map<String,JsonNode> result=new HashMap<>();
        for(var row:rows){String iid=text(row,"itemid");String metric=binding.items().entrySet().stream().filter(e->e.getValue().equals(iid)).map(Map.Entry::getKey).findFirst().orElseThrow(FirewallResourceService::unavailable);
            if(result.put(metric,row)!=null||!text(row,"hostid").equals(binding.hostId())||!text(row,"key_").equals("mop.af."+metric+"[192.169.11.1]")||!text(row,"type").equals("2")
                ||!text(row,"value_type").equals(metric.equals("state")?"1":"0")||!text(row,"history").equals("7d")||!text(row,"trends").equals("0")
                ||!text(row,"units").equals(metric.equals("state")?"":"%")||!text(row,"trapper_hosts").equals(binding.allowedHosts()))throw unavailable();}
        return result;
    }
    public Map<String,Object> snapshot(){
        var rows=current();long now=clock.instant().getEpochSecond(),at=integer(text(rows.get("state"),"lastclock"));String state="FRESH",reason=null;
        if(rows.values().stream().anyMatch(n->!text(n,"status").equals("0")||!text(n,"state").equals("0"))){state="COLLECTOR_ERROR";reason="ITEM_DISABLED_OR_UNSUPPORTED";}
        else if(at==0)state="NO_DATA";else if(at>now)state="CLOCK_SKEW";else if(now-at>180)state="STALE";
        else{
            String source=text(rows.get("state"),"lastvalue");if(!AfCollectorMain.STATES.contains(source))throw unavailable();
            if(!source.equals("OK")){state="COLLECTOR_ERROR";reason=source;}
            else if(integer(text(rows.get("cpu"),"lastclock"))!=at||integer(text(rows.get("memory"),"lastclock"))!=at){state="COLLECTOR_ERROR";reason="SAMPLE_INCOMPLETE";}
        }
        Map<String,Object> metrics=new LinkedHashMap<>();for(String metric:List.of("cpu","memory"))metrics.put(metric,map("value",state.equals("FRESH")?percent(text(rows.get(metric),"lastvalue")):null,"unit","%"));
        return map("asset_id",binding.assetId(),"management_ip",binding.ip(),"generated_at",clock.instant().toString(),"state",state,"reason",reason,"metrics",metrics,
            "received_at",at==0?null:Instant.ofEpochSecond(at).toString(),"source_sampled_at",null,"source","SANGFOR_AF_API","timestamp_meaning","COLLECTOR_RECEIPT_TIME",
            "sample_interval_seconds",60,"freshness_seconds",180,"tls_mode",binding.tlsMode(),"tls_trust_verified",false,"alert_rule_status","CONFIG_PENDING");
    }
    public Map<String,Object> history(String metric,String window){
        if(!Set.of("cpu","memory").contains(metric)||!Set.of("HOUR_1","HOURS_24").contains(window))throw new DomainException(400,"VALIDATION_ERROR","资源指标或时间窗口无效");
        if(!text(current().get(metric),"status").equals("0"))throw unavailable();Instant to=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),from=to.minusSeconds(window.equals("HOUR_1")?3600:86400);
        var rows=call("history.get",map("history",0,"itemids",List.of(binding.items().get(metric)),"output",List.of("itemid","clock","ns","value"),"time_from",from.getEpochSecond(),"time_till",to.getEpochSecond(),"sortfield",List.of("clock","ns"),"sortorder","ASC","limit",2001));
        if(rows.size()>2000)throw new DomainException(422,"POINT_LIMIT_EXCEEDED","历史点数超过上限");
        List<Map<String,Object>> points=new ArrayList<>();Instant previous=null;
        for(var row:rows){if(!fields(row,Set.of("itemid","clock","ns","value"))||!text(row,"itemid").equals(binding.items().get(metric)))throw unavailable();long sec=integer(text(row,"clock")),ns=integer(text(row,"ns"));if(sec<from.getEpochSecond()||sec>to.getEpochSecond()||ns>999999999)throw unavailable();Instant at=Instant.ofEpochSecond(sec,ns);if(previous!=null&&!at.isAfter(previous))throw unavailable();previous=at;points.add(map("recorded_at",at.toString(),"value",percent(text(row,"value"))));}
        if(!text(current().get(metric),"status").equals("0"))throw unavailable();return map("asset_id",binding.assetId(),"metric",metric,"unit","%","window",window,"from",from.toString(),"to",to.toString(),"points",points,"sample_interval_seconds",60,"gap_after_seconds",90,"timestamp_meaning","COLLECTOR_RECEIPT_TIME","complete_window_claimed",false,"source","SANGFOR_AF_API");
    }
    static String percent(String value){if(!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,12})?")||new BigDecimal(value).compareTo(BigDecimal.valueOf(100))>0)throw unavailable();return new BigDecimal(value).toPlainString();}
    public Map<String,Object> readiness(){if(!enabled())return map("enabled",false,"ready",false,"reason","NOT_CONFIGURED");try{var value=snapshot();return map("enabled",true,"ready",value.get("state").equals("FRESH"),"reason",value.get("reason")==null?value.get("state"):value.get("reason"));}catch(Exception failure){return map("enabled",true,"ready",false,"reason","RESOURCE_SOURCE_UNAVAILABLE");}}
    static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","防火墙资源数据来源不可用或映射变化");}
}
