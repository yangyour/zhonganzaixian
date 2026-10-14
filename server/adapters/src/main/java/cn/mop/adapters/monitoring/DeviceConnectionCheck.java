package cn.mop.adapters.monitoring;

import cn.mop.adapters.af.AfClient;
import cn.mop.adapters.http.DeviceHttpsTransport;
import cn.mop.adapters.scp.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Explicit single check, not a background poller. Private credentials arrive on stdin, never arguments. */
public final class DeviceConnectionCheck {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(8192).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DeviceConnectionCheck(){}
    public static Map<String,Object> run(byte[] input,boolean verifyIsolation){
        try{
            if(input==null||input.length>32768)throw new IllegalArgumentException();
            JsonNode root=JSON.readTree(input);
            if(!root.isObject()||root.size()!=3||!root.has("device")||!root.has("configuration")||!root.has("secrets"))throw new IllegalArgumentException();
            String device=root.path("device").asText();if(!Set.of("scp","firewall").contains(device))throw new IllegalArgumentException();
            if(!root.path("configuration").isObject()||!root.path("secrets").isObject()||root.path("secrets").size()>6)throw new IllegalArgumentException();
            ObjectNode config=(ObjectNode)root.get("configuration");Map<String,String> secrets=new HashMap<>();
            root.get("secrets").fields().forEachRemaining(e->{
                if(!e.getKey().matches("MOP_(?:SCP|AF)_(?:USERNAME|PASSWORD|ACCESS_KEY|SECRET_KEY|CA_FILE)")||!e.getValue().isTextual())throw new IllegalArgumentException();
                secrets.put(e.getKey(),e.getValue().textValue());
            });
            DeviceHttpsTransport.Mode mode=DeviceHttpsTransport.Mode.valueOf(config.path("tls_mode").asText("SYSTEM"));
            if(device.equals("scp"))ScpSettings.parse(JSON.writeValueAsBytes(config));
            else if(config.size()!=2||!config.has("namespace")||!config.has("tls_mode")||!config.path("namespace").isTextual())throw new IllegalArgumentException();
            String target=device.equals("scp")?"https://192.168.1.179":"https://192.169.11.1";
            if(device.equals("scp")&&!Set.of(target,target+":443").contains(config.path("base_url").asText()))throw new IllegalArgumentException();
            Map<String,Object> report=new LinkedHashMap<>();report.put("checked_at",Instant.now().toString());report.put("target",target);report.put("device",device);
            if(verifyIsolation)report.put("strict_before",strictProbe(device));
            report.put("selected_mode",device.equals("scp")?scp(config,secrets,mode):firewall(config,secrets,mode));
            if(verifyIsolation)report.put("strict_after",strictProbe(device));
            report.put("integration_ready",false);report.put("background_collection_enabled",false);report.put("resource_writes",0);report.put("credentials_output",false);
            return report;
        }catch(Exception failure){return Map.of("state","INVALID_PRIVATE_CONFIGURATION","integration_ready",false,"credentials_output",false);}
    }
    private static Map<String,Object> strictProbe(String device){
        String target=device.equals("scp")?"https://192.168.1.179":"https://192.169.11.1";
        String path=device.equals("scp")?"/janus/20180725/system/version":"/api/v1/namespaces/public/systemversion";
        try(var transport=new DeviceHttpsTransport(URI.create(target),DeviceHttpsTransport.Mode.SYSTEM,null)){
            var response=transport.execute("GET",path,Map.of(),new byte[0]);return Map.of("state","TLS_VERIFIED","http_status",response.status(),"authenticated",false);
        }catch(DeviceHttpsTransport.Failure failure){return Map.of("state",failure.reason,"authenticated",false);}
    }
    private static Map<String,Object> base(DeviceHttpsTransport.Mode mode){
        Map<String,Object> r=new LinkedHashMap<>();r.put("tls_mode",mode.name());r.put("certificate_verification_enabled",mode!=DeviceHttpsTransport.Mode.TEST_INSECURE);
        r.put("tls_trust_verified",false);r.put("authenticated",false);r.put("protected_read_verified",false);r.put("resource_metrics_verified",false);r.put("state","CONFIG_PENDING");return r;
    }
    private static Map<String,Object> scp(ObjectNode config,Map<String,String> secrets,DeviceHttpsTransport.Mode mode){
        Map<String,Object> r=base(mode);ScpClient client=null;
        try{
            client=ScpClient.configuredForConnectionCheck(ScpSettings.parse(JSON.writeValueAsBytes(config)),secrets::get);
            r.put("version",client.version());r.put("protected_read_verified",true);r.put("state","VERSION_READ_OK_RESOURCE_SCOPE_PENDING");
        }catch(ScpProtocol.Failure failure){r.put("state",failure.reason);}catch(Exception failure){r.put("state","INVALID_PRIVATE_CONFIGURATION");}
        finally{if(client!=null){boolean auth=client.passwordAuthenticationVerified();r.put("authenticated",auth);r.put("tls_trust_verified",mode!=DeviceHttpsTransport.Mode.TEST_INSECURE&&(auth||Boolean.TRUE.equals(r.get("protected_read_verified"))));client.close();}}
        return r;
    }
    private static Map<String,Object> firewall(ObjectNode config,Map<String,String> secrets,DeviceHttpsTransport.Mode mode){
        Map<String,Object> r=base(mode);AfClient client=null;
        try{
            Path ca=mode==DeviceHttpsTransport.Mode.CA_FILE?Path.of(Objects.requireNonNull(secrets.get("MOP_AF_CA_FILE"))):null;
            client=AfClient.configured(config.get("namespace").textValue(),secrets.get("MOP_AF_USERNAME"),secrets.get("MOP_AF_PASSWORD"),mode,ca);
            r.put("version",client.version());r.put("protected_read_verified",true);
            r.put("metrics",client.cpuAndMemory());r.put("resource_metrics_verified",true);r.put("state","READ_OK");r.put("source_sampled_at",null);
        }catch(AfClient.Failure failure){r.put("state",failure.reason);}catch(DeviceHttpsTransport.Failure failure){r.put("state",failure.reason);}catch(Exception failure){r.put("state","INVALID_PRIVATE_CONFIGURATION");}
        finally{if(client!=null){boolean auth=client.authenticated();r.put("authenticated",auth);r.put("tls_trust_verified",mode!=DeviceHttpsTransport.Mode.TEST_INSECURE&&auth);client.close();r.put("logout_verified",client.logoutSucceeded());}}
        return r;
    }
    public static void main(String[] args){
        int exit=1;
        try{
            if(args.length<1||args.length>2||!args[0].equals("--stdin")||args.length==2&&!args[1].equals("--verify-tls-isolation"))throw new IllegalArgumentException();
            var report=run(System.in.readNBytes(32769),args.length==2);System.out.println(JSON.writeValueAsString(report));
            exit=exitCode(report);
        }catch(Exception failure){System.out.println("{\"state\":\"CHECK_FAILED\",\"integration_ready\":false,\"credentials_output\":false}");}
        System.exit(exit);
    }
    static int exitCode(Map<String,Object> report){
        if(!(report.get("selected_mode") instanceof Map<?,?> selected))return 1;
        Object state=selected.get("state");
        if("INVALID_PRIVATE_CONFIGURATION".equals(state))return 1;
        return Boolean.TRUE.equals(selected.get("protected_read_verified"))
            &&("READ_OK".equals(state)||"VERSION_READ_OK_RESOURCE_SCOPE_PENDING".equals(state))?0:2;
    }
}
