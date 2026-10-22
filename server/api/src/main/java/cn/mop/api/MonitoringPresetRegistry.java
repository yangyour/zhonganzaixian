package cn.mop.api;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable startup configuration. Never accepts target addresses from HTTP requests. */
final class MonitoringPresetRegistry {
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10).maxStringLength(1024).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    record Preset(String id,String engine,String kind,String path,List<String> metrics,String module,String successMetric){Preset{metrics=List.copyOf(metrics);}}
    record Target(String id,String tenantId,String assetId,Preset preset,URI endpoint,String downstream,String authProfile,int timeout,String json,String hash){
        Object configuration(){try{return JSON.readValue(json,Object.class);}catch(Exception ex){throw invalid();}}
        String assetIp(){if(downstream==null)return endpoint.getHost();if(preset.id().equals("http-service"))return URI.create(downstream).getHost();if(preset.id().equals("tcp-service"))return URI.create("tcp://"+downstream).getHost();return downstream;}
        @Override public String toString(){return "MonitoringTarget[redacted]";}
    }
    private final Map<String,Target> targets;
    private final Set<String> endpoints,downstreams;
    MonitoringPresetRegistry(String raw,String allowedEndpoints,String allowedDownstreams){
        try{
            var catalog=presets();JsonNode root=parse(raw);fields(root,Set.of("version","targets"));
            if(!root.get("version").isIntegralNumber()||!root.get("version").canConvertToInt()||root.get("version").intValue()!=1||!root.get("targets").isArray()||root.get("targets").isEmpty()||root.get("targets").size()>64)throw invalid();
            Map<String,Target> found=new LinkedHashMap<>();Set<List<String>> bindings=new HashSet<>();
            for(JsonNode row:root.get("targets")){
                fields(row,Set.of("id","tenant_id","asset_id","preset_id","enabled","endpoint","target","auth_profile","interval_seconds","timeout_seconds"));
                String id=text(row,"id"),tenant=text(row,"tenant_id"),asset=text(row,"asset_id");if(!uuid(id)||!uuid(tenant)||!uuid(asset))throw invalid();
                Preset p=catalog.get(text(row,"preset_id"));if(p==null||!row.get("enabled").isBoolean())throw invalid();
                int timeout=number(row.get("timeout_seconds"),1,10);Integer interval=row.get("interval_seconds").isNull()?null:number(row.get("interval_seconds"),5,3600);
                if(row.get("enabled").booleanValue()&&interval==null||interval!=null&&timeout>=interval)throw invalid();
                URI e=endpoint(text(row,"endpoint"),p);String target=nullable(row,"target"),auth=nullable(row,"auth_profile");
                if(Set.of("blackbox","snmp").contains(p.kind())){
                    if(target==null)throw invalid();
                    if(p.id().equals("http-service")){URI u=URI.create(target);if(!Set.of("http","https").contains(u.getScheme())||!ip(u.getHost())||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||u.getPort()>65535||u.getPort()==0||target.contains("%")||target.contains("\\")||!ascii(target))throw invalid();}
                    else if(p.id().equals("tcp-service")){URI u=URI.create("tcp://"+target);if(!ip(u.getHost())||u.getPort()<1||u.getPort()>65535||u.getUserInfo()!=null||!u.getRawPath().isEmpty()||u.getQuery()!=null||u.getFragment()!=null)throw invalid();}
                    else if(!ip(target))throw invalid();
                }else if(target!=null)throw invalid();
                if(p.kind().equals("snmp")){if(auth==null||!auth.matches("[a-zA-Z_][a-zA-Z0-9_-]{0,63}"))throw invalid();}else if(auth!=null)throw invalid();
                @SuppressWarnings("unchecked") Map<String,Object> values=JSON.convertValue(row,Map.class);
                String single=JSON.writeValueAsString(Map.of("version",1,"targets",List.of(new TreeMap<>(values))));
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(single.getBytes(StandardCharsets.UTF_8)));
                Target t=new Target(id,tenant,asset,p,e,target,auth,timeout,single,hash);
                if(found.put(id,t)!=null||!bindings.add(List.of(tenant,asset,p.id(),e.toString(),destinationKey(p,target))))throw invalid();
            }
            targets=Collections.unmodifiableMap(found);endpoints=allowlist(allowedEndpoints);downstreams=allowlist(allowedDownstreams);
        }catch(Exception ex){throw invalid();}
    }
    static String destinationKey(Preset p,String target){
        if(target==null)return "";
        if(p.id().equals("http-service")){URI u=URI.create(target);int port=u.getPort()<0?(u.getScheme().equals("https")?443:80):u.getPort();return u.getScheme()+"://"+u.getHost()+":"+port+(u.getRawPath().isEmpty()?"/":u.getRawPath());}
        if(p.id().equals("tcp-service")){URI u=URI.create("tcp://"+target);return u.getHost()+":"+u.getPort();}
        return target;
    }
    List<Target> forTenant(String tenant){return targets.values().stream().filter(t->t.tenantId().equals(tenant)).toList();}
    Optional<Target> find(String tenant,String id){return Optional.ofNullable(targets.get(id)).filter(t->t.tenantId().equals(tenant));}
    boolean approved(Target target){return endpoints.contains(target.endpoint().toString())&&(target.downstream()==null||downstreams.contains(target.downstream()));}
    static Map<String,Preset> presets(){try(var in=MonitoringPresetRegistry.class.getResourceAsStream("/monitoring/presets.v1.json")){
        if(in==null)throw invalid();JsonNode root=JSON.readTree(in);Map<String,Preset> result=new HashMap<>();
        for(JsonNode r:root.get("presets")){List<String> metrics=new ArrayList<>();r.get("required_any_metrics").forEach(x->metrics.add(x.textValue()));Preset p=new Preset(text(r,"id"),text(r,"engine"),text(r,"test_kind"),nullable(r,"path"),metrics,r.path("module").isTextual()?r.get("module").textValue():null,r.path("success_metric").isTextual()?r.get("success_metric").textValue():null);if(result.put(p.id(),p)!=null)throw invalid();}return Map.copyOf(result);
    }catch(Exception ex){throw invalid();}}
    static JsonNode parse(String s){try{if(s==null||s.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw invalid();return JSON.readTree(s);}catch(Exception ex){throw invalid();}}
    static Set<String> allowlist(String raw){JsonNode a=parse(raw);if(!a.isArray()||a.size()>64)throw invalid();Set<String> out=new HashSet<>();for(JsonNode n:a)if(!n.isTextual()||n.textValue().length()>512||!ascii(n.textValue())||!out.add(n.textValue()))throw invalid();return Set.copyOf(out);}
    static URI endpoint(String s,Preset p){try{URI u=URI.create(s);String scheme=p.kind().equals("tcp")?"tcp":p.kind().equals("configuration_only")?"udp":null;if(s.length()>512||!ascii(s)||!ip(u.getHost())||u.getPort()<1||u.getPort()>65535||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!u.getRawPath().equals(p.path()==null?"":p.path())||!(scheme==null?Set.of("http","https").contains(u.getScheme()):scheme.equals(u.getScheme()))||!s.equals(u.getScheme()+"://"+u.getHost()+":"+u.getPort()+u.getRawPath()))throw invalid();return u;}catch(Exception ex){throw invalid();}}
    static boolean ascii(String s){return s!=null&&s.chars().allMatch(c->c>32&&c<127);}
    static boolean uuid(String s){return s!=null&&s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    static boolean ip(String s){if(s==null||!s.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))return false;int[] a=Arrays.stream(s.split("\\.")).mapToInt(Integer::parseInt).toArray();return Arrays.stream(a).allMatch(x->x<=255)&&!s.equals("0.0.0.0")&&!(a[0]>=224&&a[0]<=239)&&!(a[0]==169&&a[1]==254);}
    static String text(JsonNode n,String k){if(n==null||!n.has(k)||!n.get(k).isTextual())throw invalid();return n.get(k).textValue();}
    static String nullable(JsonNode n,String k){if(!n.has(k))throw invalid();return n.get(k).isNull()?null:text(n,k);}
    static int number(JsonNode n,int lo,int hi){if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<lo||n.intValue()>hi)throw invalid();return n.intValue();}
    static void fields(JsonNode n,Set<String> keys){if(n==null||!n.isObject()||n.size()!=keys.size())throw invalid();for(String k:keys)if(!n.has(k))throw invalid();}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid monitoring preset configuration");}
}
