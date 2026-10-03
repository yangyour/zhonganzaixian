package cn.mop.domain.authorization;
import cn.mop.domain.Capabilities;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Current code declarations only, never a signed matrix or a resource authorization decision. */
public final class CapabilityCatalog {
    private CapabilityCatalog(){}
    public record Role(String id,String label) {}
    public record Dimension(String id,String label,List<String> capabilityIds){public Dimension{capabilityIds=List.copyOf(capabilityIds);}}
    public record Entry(String id,String label,List<String> dimensionIds,Map<String,Boolean> declaredByRole){public Entry{dimensionIds=List.copyOf(dimensionIds);declaredByRole=Collections.unmodifiableMap(new LinkedHashMap<>(declaredByRole));}}
    public record Snapshot(String declarationHash,List<Role> roles,List<Dimension> dimensions,List<Entry> entries){public Snapshot{roles=List.copyOf(roles);dimensions=List.copyOf(dimensions);entries=List.copyOf(entries);}}
    private record Definition(String label,List<String> dimensions) {}
    public static final List<Role> ROLES=List.of(new Role("viewer","客户观察者"),new Role("tadmin","客户管理员"),new Role("msp","MSP值班工程师"),new Role("padmin","平台管理员"));
    private static final Map<String,String> DIMENSIONS;
    private static final Map<String,Definition> DEFINITIONS;
    static {
        var dimensions=new LinkedHashMap<String,String>();dimensions.put("ASSETS_METRICS","资产与指标");dimensions.put("ALERTS_TICKETS","告警与工单");dimensions.put("TEMPLATES_AUTOMATION","模板与自动化");dimensions.put("PLATFORM_ADMIN","平台管理");dimensions.put("MEMBER_ADMIN","成员管理");DIMENSIONS=Collections.unmodifiableMap(dimensions);
        var definitions=new TreeMap<String,Definition>();
        define(definitions,"operations:compare_explicit","显式经营样本与人力对照","PLATFORM_ADMIN");
        define(definitions,"capacity_scenarios:read","读取容量方案草稿","PLATFORM_ADMIN");
        define(definitions,"capacity_scenarios:write","保存不可变容量方案草稿","PLATFORM_ADMIN");
        define(definitions,"capacity:estimate","显式容量与缓冲估算","PLATFORM_ADMIN");
        define(definitions,"search:read","分组搜索","ASSETS_METRICS","ALERTS_TICKETS");
        define(definitions,"assets:read","读取资产记录","ASSETS_METRICS");define(definitions,"assets:write","资产相关写入口能力声明","ASSETS_METRICS");define(definitions,"asset_dates:read","读取记录日期对照","ASSETS_METRICS");
        define(definitions,"tickets:read","读取授权报障登记","ALERTS_TICKETS");define(definitions,"tickets:report","提交客户报障登记","ALERTS_TICKETS");
        define(definitions,"ticket_comments:read","读取客户可见评论","ALERTS_TICKETS");define(definitions,"ticket_comments:write","追加客户可见评论","ALERTS_TICKETS");
        define(definitions,"ticket_notes:read_internal","读取内部备注","ALERTS_TICKETS");define(definitions,"ticket_notes:write_internal","追加内部备注","ALERTS_TICKETS");
        define(definitions,"manual_incidents:read","读取手工来源登记","ALERTS_TICKETS");define(definitions,"manual_incidents:write","创建手工来源登记","ALERTS_TICKETS");
        define(definitions,"manual_recovery:read","读取独立人工恢复确认","ALERTS_TICKETS");define(definitions,"manual_recovery:confirm","提交独立人工恢复确认","ALERTS_TICKETS");
        define(definitions,"sla_scenarios:read","只读SLA显式对照","ALERTS_TICKETS");
        define(definitions,"worklogs:read_own","读取本人工时","ALERTS_TICKETS");define(definitions,"worklogs:write_own","维护本人工时","ALERTS_TICKETS");
        define(definitions,"configurations:read","读取设计配置与提案","PLATFORM_ADMIN");define(definitions,"configurations:write","维护设计草稿与内部提案","PLATFORM_ADMIN");define(definitions,"audit:read","读取当前租户审计","PLATFORM_ADMIN");
        DEFINITIONS=Collections.unmodifiableMap(definitions);
    }
    private static void define(Map<String,Definition> target,String id,String label,String...dimensions){if(target.put(id,new Definition(label,List.of(dimensions)))!=null)throw new IllegalStateException("Duplicate capability definition");}
    public static Snapshot current(){var values=new LinkedHashMap<String,List<String>>();for(Role role:ROLES)values.put(role.id(),Capabilities.forRole(role.id()));return fromDeclarations(values);}
    /** Comparison input for tests; it is not a write/activation/configuration API. */
    public static Snapshot fromDeclarations(Map<String,List<String>> declarations){
        if(declarations==null||!declarations.keySet().equals(new HashSet<>(ROLES.stream().map(Role::id).toList())))throw new IllegalArgumentException("Exactly four current base roles required");
        Set<String> active=new HashSet<>();for(var values:declarations.values()){if(values==null||new HashSet<>(values).size()!=values.size()||values.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Invalid capability declarations");active.addAll(values);}if(!DEFINITIONS.keySet().containsAll(active))throw new IllegalArgumentException("Unclassified capability cannot silently enter review catalog");
        List<Entry> entries=new ArrayList<>();StringBuilder canonical=new StringBuilder("mop-capability-declarations-v1\nviewer|tadmin|msp|padmin\n");
        for(String id:new TreeSet<>(active)){Definition definition=DEFINITIONS.get(id);var declared=new LinkedHashMap<String,Boolean>();for(Role role:ROLES)declared.put(role.id(),declarations.get(role.id()).contains(id));entries.add(new Entry(id,definition.label(),definition.dimensions(),declared));canonical.append(id).append('|').append(String.join(",",definition.dimensions())).append('|');for(boolean value:declared.values())canonical.append(value?'1':'0');canonical.append('\n');}
        List<Dimension> dimensions=new ArrayList<>();DIMENSIONS.forEach((id,label)->dimensions.add(new Dimension(id,label,entries.stream().filter(e->e.dimensionIds().contains(id)).map(Entry::id).toList())));
        try{return new Snapshot(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))),ROLES,dimensions,entries);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
