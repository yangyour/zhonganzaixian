package cn.mop.domain.dashboard;

import cn.mop.domain.DomainException;
import java.math.*;
import java.time.*;
import java.util.*;

/** Complete tenant ledger + explicit ICMP observations. Reachability is not vendor/device health. */
public final class AssetReachability {
    private AssetReachability() {}
    public static final String VERSION="MOP_ASSET_REACHABILITY_V1";
    public static final int FRESHNESS_SECONDS=60;
    public record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{Objects.requireNonNull(tenantId);Objects.requireNonNull(actorId);if(permissionVersion<1||permissionVersion>9007199254740991L)throw invalid();}}
    public record Asset(UUID id,String name,String managementIp,long version){public Asset{Objects.requireNonNull(id);if(name==null||name.isBlank()||name.length()>200||managementIp==null||version<1||version>9007199254740991L)throw invalid();}}
    public record Inventory(Access access,Instant observedAt,List<Asset> assets){public Inventory{Objects.requireNonNull(access);Objects.requireNonNull(observedAt);assets=List.copyOf(assets);if(assets.size()>10000||assets.stream().map(Asset::id).distinct().count()!=assets.size())throw invalid();}}
    @FunctionalInterface public interface Reader {Inventory read(Access access);}
    public record Sample(String state,String value,Instant sampledAt){public Sample{if(!Set.of("FRESH","STALE","NO_DATA","COLLECTOR_ERROR","DISABLED","CLOCK_SKEW").contains(state)||state.equals("FRESH")&&(sampledAt==null||value==null||!Set.of("0","1").contains(value)))throw invalid();}}
    public record Row(UUID id,String name,long version,String state,String reason,Instant sampledAt,Instant validUntil){}
    public record Snapshot(UUID tenantId,UUID actorId,long permissionVersion,Instant observedAt,int total,int normal,int noResponse,int unknown,BigDecimal percentage,List<Row> items,boolean sourceConfigured){public Snapshot{items=List.copyOf(items);}}
    public static Snapshot evaluate(Inventory inventory,Map<UUID,Sample> samples,boolean sourceConfigured){
        if(!sourceConfigured&&!samples.isEmpty())throw invalid();
        if(!inventory.assets().stream().map(Asset::id).collect(java.util.stream.Collectors.toSet()).containsAll(samples.keySet()))throw invalid();
        var rows=new ArrayList<Row>();int normal=0,down=0,unknown=0;
        for(var asset:inventory.assets()){
            var sample=samples.get(asset.id());String state="UNKNOWN",reason=!sourceConfigured?"SOURCE_NOT_CONFIGURED":sample==null?"NO_EXPLICIT_BINDING":sample.state();Instant until=null;
            if(sample!=null&&sample.state().equals("FRESH")){
                if(sample.sampledAt().isAfter(inventory.observedAt()))reason="CLOCK_SKEW";
                else if(!sample.sampledAt().plusSeconds(FRESHNESS_SECONDS).isAfter(inventory.observedAt()))reason="STALE";
                else{state=sample.value().equals("1")?"NORMAL":"NO_RESPONSE";reason="FRESH_ICMP_SAMPLE";until=sample.sampledAt().plusSeconds(FRESHNESS_SECONDS);}
            }
            if(state.equals("NORMAL"))normal++;else if(state.equals("NO_RESPONSE"))down++;else unknown++;
            rows.add(new Row(asset.id(),asset.name(),asset.version(),state,reason,sample==null?null:sample.sampledAt(),until));
        }
        int total=rows.size();var pct=total>0&&unknown==0?BigDecimal.valueOf(normal).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),4,RoundingMode.HALF_UP):null;
        return new Snapshot(inventory.access().tenantId(),inventory.access().actorId(),inventory.access().permissionVersion(),inventory.observedAt(),total,normal,down,unknown,pct,rows,sourceConfigured);
    }
    static DomainException invalid(){return new DomainException(503,"ASSET_REACHABILITY_INVALID","资产在线范围或采样证据无效，未返回部分统计");}
}
