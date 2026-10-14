package cn.mop.adapters.af;

import cn.mop.adapters.http.DeviceHttpsTransport;
import cn.mop.adapters.monitoring.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Project worker: read AF, publish only owned trapper samples, never write device configuration. */
public final class AfCollectorMain {
    public static final Set<String> STATES=Set.of("OK","PASSWORD_EXPIRED","ACCESS_DENIED","AUTHENTICATION_REJECTED","AUTHENTICATION_REQUIRED","AUTHENTICATION_UNKNOWN","VERSION_CHANGED","SOURCE_UNAVAILABLE","SOURCE_DATA_INVALID");
    private static final Path HEALTH=Path.of("/tmp/af-collector-health.json");
    public record Sample(String state,Map<String,String> metrics,Instant receivedAt){public Sample{metrics=Map.copyOf(metrics);}}
    @FunctionalInterface public interface Source {Map<String,String> collect();}
    public static Sample collect(Source source,Clock clock){
        try{
            var metrics=source.collect();if(!metrics.keySet().equals(Set.of("cpu","memory")))throw new IllegalStateException();
            for(var value:metrics.values())if(!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,12})?")||new java.math.BigDecimal(value).compareTo(java.math.BigDecimal.valueOf(100))>0)throw new IllegalStateException();
            return new Sample("OK",metrics,clock.instant());
        }catch(AfClient.Failure failure){return new Sample(STATES.contains(failure.reason)?failure.reason:"SOURCE_DATA_INVALID",Map.of(),clock.instant());}
        catch(Exception failure){return new Sample("SOURCE_UNAVAILABLE",Map.of(),clock.instant());}
    }
    public static Map<String,String> values(Sample sample,Map<String,String> items){
        Map<String,String> result=new LinkedHashMap<>();result.put(items.get("state"),sample.state());
        if(sample.state().equals("OK")){result.put(items.get("cpu"),sample.metrics().get("cpu"));result.put(items.get("memory"),sample.metrics().get("memory"));}return result;
    }
    private static String required(String key){String v=System.getenv(key);if(v==null||v.isEmpty()||v.length()>8192)throw new IllegalArgumentException("Private collector configuration missing");return v;}
    public static void main(String[] args){
        if(args.length==1&&args[0].equals("--health")){
            try{var v=ZabbixReadClient.JSON.readTree(Files.readAllBytes(HEALTH));long age=Duration.between(Instant.parse(v.path("at").asText()),Instant.now()).getSeconds();boolean ok=v.path("state").asText().equals("OK")&&age>=0&&age<180;System.exit(ok?0:1);}catch(Exception failure){System.exit(1);}return;
        }
        try{
            if(args.length!=0||!required("MOP_AF_COLLECTOR_ENABLED").equals("true"))throw new IllegalArgumentException();
            var node=ZabbixReadClient.JSON.readTree(required("MOP_AF_ITEMS"));Map<String,String> items=new HashMap<>();
            if(!node.isObject()||node.size()!=3)throw new IllegalArgumentException();for(String key:List.of("cpu","memory","state")){if(!node.path(key).isTextual())throw new IllegalArgumentException();items.put(key,node.get(key).textValue());}
            String username=required("MOP_AF_USERNAME"),password=required("MOP_AF_PASSWORD"),version=required("MOP_AF_EXPECTED_VERSION");
            var mode=DeviceHttpsTransport.Mode.valueOf(required("MOP_AF_TLS_MODE"));
            Path ca=mode==DeviceHttpsTransport.Mode.CA_FILE?Path.of(required("MOP_AF_CA_FILE")):null;
            Supplier<AfClient> factory=()->AfClient.configured("public",username,password,mode,ca);
            try(var writer=new ZabbixHistoryWriter(required("MOP_AF_ZABBIX_ENDPOINT"),required("MOP_AF_ZABBIX_TOKEN"),Set.copyOf(items.values()))){
                String paused=null;
                while(!Thread.currentThread().isInterrupted()){
                    long start=System.nanoTime();Sample sample;
                    if(paused!=null)sample=new Sample(paused,Map.of(),Instant.now());
                    else{
                        final boolean[] authenticated={false};
                        sample=collect(()->{
                            try(var client=factory.get()){
                                try{if(!client.version().get("full").equals(version))throw new AfClient.Failure("VERSION_CHANGED");var m=client.cpuAndMemory();return Map.of("cpu",m.get("cpu_current_percent"),"memory",m.get("memory_percent"));}
                                finally{authenticated[0]=client.authenticated();}
                            }
                        },Clock.systemUTC());
                        if(!sample.state().equals("OK")&&(!authenticated[0]||Set.of("PASSWORD_EXPIRED","ACCESS_DENIED","AUTHENTICATION_REJECTED","AUTHENTICATION_REQUIRED","VERSION_CHANGED").contains(sample.state())))paused=sample.state();
                    }
                    String state=sample.state();try{writer.push(values(sample,items),sample.receivedAt());}catch(Exception failure){state="PUBLISH_UNAVAILABLE";}
                    String health=ZabbixReadClient.JSON.writeValueAsString(Map.of("state",state,"at",Instant.now().toString(),"tls_mode",mode.name()));
                    Files.writeString(HEALTH,health);System.out.println(health);
                    long remaining=60_000-(System.nanoTime()-start)/1_000_000;if(remaining>0)Thread.sleep(remaining);
                }
            }
        }catch(InterruptedException done){Thread.currentThread().interrupt();}
        catch(Exception failure){System.out.println("{\"state\":\"COLLECTOR_CONFIGURATION_OR_RUNTIME_FAILED\",\"details\":\"omitted\"}");System.exit(1);}
    }
}
