package cn.mop.adapters.af;

import cn.mop.adapters.http.DeviceHttpsTransport;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.*;
import java.nio.file.Path;
import java.util.*;

/** AF documented read paths only. Login/logout are the only POST operations. */
public final class AfClient implements AutoCloseable {
    public static final class Failure extends RuntimeException {
        public final String reason;
        public Failure(String reason){super("AF connector: "+reason);this.reason=reason;}
    }
    @FunctionalInterface public interface Exchange {DeviceHttpsTransport.Response execute(String method,String path,Map<String,String> headers,byte[] body);}
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(32768).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String prefix,username,password;
    private final Exchange exchange;
    private final AutoCloseable transport;
    private String token,blocked;
    private boolean authenticated,logoutSucceeded;
    public static AfClient configured(String namespace,String username,String password,DeviceHttpsTransport.Mode mode,Path caFile){
        validate(namespace,username,password);
        var transport=new DeviceHttpsTransport(URI.create("https://192.169.11.1"),mode,caFile);
        return new AfClient(namespace,username,password,transport::execute,transport);
    }
    public AfClient(String namespace,String username,String password,Exchange exchange){this(namespace,username,password,exchange,null);}
    private AfClient(String namespace,String username,String password,Exchange exchange,AutoCloseable transport){
        validate(namespace,username,password);prefix="/api/v1/namespaces/"+namespace;this.username=username;this.password=password;this.exchange=Objects.requireNonNull(exchange);this.transport=transport;
    }
    private static void validate(String namespace,String username,String password){
        if(namespace==null||!namespace.matches("[A-Za-z0-9_-]{1,15}")||username==null||username.isBlank()||username.length()>60||username.chars().anyMatch(Character::isISOControl)
            ||password==null||password.isEmpty()||password.length()>512)throw new Failure("INVALID_PRIVATE_CONFIGURATION");
    }
    private JsonNode call(String method,String suffix,Object body){
        try{
            byte[] bytes=body==null?new byte[0]:JSON.writeValueAsBytes(body);
            return decode(exchange.execute(method,prefix+"/"+suffix,token==null?Map.of():Map.of("Cookie","token="+token),bytes));
        }catch(Failure failure){throw failure;}catch(DeviceHttpsTransport.Failure failure){throw new Failure(failure.reason);}
        catch(Exception failure){throw new Failure("DEVICE_TRANSPORT_UNAVAILABLE");}
    }
    private void login(){
        if(blocked!=null)throw new Failure(blocked);if(token!=null)return;
        blocked="AUTHENTICATION_RETRY_REQUIRES_EXPLICIT_CHECK";
        try{
            JsonNode data=call("POST","login",Map.of("name",username,"password",password));
            String value=text(data.path("loginResult"),"token",4096);
            if(!value.matches("[A-Za-z0-9._~-]{1,4096}"))throw new Failure("INVALID_SESSION");
            token=value;authenticated=true;blocked=null;
        }catch(Failure failure){blocked=failure.reason;throw failure;}
    }
    private JsonNode read(String path){
        login();
        try{return call("GET",path,null);}catch(Failure failure){blocked=failure.reason;throw failure;}
    }
    public Map<String,String> version(){JsonNode data=read("systemversion");return Map.of("full",text(data,"full",256));}
    /** Returns actual percent values only. Missing fields never become zero. Source time is not supplied. */
    public Map<String,String> cpuAndMemory(){
        JsonNode cpu=read("cpuusage"),memory=read("memoryusage");
        return Map.of("cpu_current_percent",percent(cpu,"cpuCurrent",true),"memory_percent",percent(memory,"memoryUsage",false));
    }
    static String percent(JsonNode node,String field,boolean string){
        JsonNode value=node.get(field);
        if(value==null||value.isNull())throw new Failure("METRIC_NOT_PROVIDED");
        String raw;
        if(string&&value.isTextual())raw=value.textValue();else if(!string&&value.isIntegralNumber())raw=value.asText();else throw new Failure("INVALID_METRIC");
        if(!raw.matches("(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,12})?"))throw new Failure("INVALID_METRIC");
        BigDecimal number=new BigDecimal(raw);if(number.compareTo(BigDecimal.valueOf(100))>0)throw new Failure("INVALID_METRIC");return number.toPlainString();
    }
    private static String text(JsonNode node,String key,int maximum){
        if(!node.isObject()||!node.path(key).isTextual()||node.get(key).textValue().isEmpty()||node.get(key).textValue().length()>maximum)throw new Failure("INVALID_SOURCE_FIELD");
        return node.get(key).textValue();
    }
    static JsonNode decode(DeviceHttpsTransport.Response response){
        if(response.status()==401)throw new Failure("AUTHENTICATION_REJECTED");if(response.status()==403)throw new Failure("ACCESS_DENIED");
        if(response.status()==429)throw new Failure("RATE_LIMITED");if(response.status()!=200)throw new Failure("HTTP_STATUS_REJECTED");
        if(response.bytes().length==0||response.bytes().length>DeviceHttpsTransport.MAX_BODY||response.contentType()==null
            ||!response.contentType().toLowerCase(Locale.ROOT).matches("(?:application/json|application/octet-stream|text/html)(?:;.*)?"))throw new Failure("INVALID_RESPONSE_FORMAT");
        try{
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(response.bytes()));
            JsonNode root=JSON.readTree(response.bytes());JsonNode envelope=root;
            for(int depth=0;depth<4;depth++){
                if(!envelope.isObject()||!envelope.path("code").isIntegralNumber()||!envelope.path("code").canConvertToInt()||!envelope.has("data"))throw new Failure("INVALID_ENVELOPE");
                int code=envelope.get("code").intValue();
                if(code!=0)throw new Failure(switch(code){case 1->"AUTHENTICATION_REJECTED";case 13->"ACCESS_DENIED";case 1010->"PASSWORD_EXPIRED";case 1003,1012->"AUTHENTICATION_REQUIRED";case 1002->"API_NOT_SUPPORTED";default->"PROVIDER_REJECTED";});
                JsonNode data=envelope.get("data");if(!data.isObject()||!data.has("code"))return data;envelope=data;
            }
            throw new Failure("INVALID_ENVELOPE");
        }catch(Failure failure){throw failure;}catch(Exception failure){throw new Failure("INVALID_JSON");}
    }
    public boolean authenticated(){return authenticated;}
    public boolean logoutSucceeded(){return logoutSucceeded;}
    @Override public void close(){
        if(token!=null){try{call("POST","logout",Map.of("loginResult",Map.of("token",token)));logoutSucceeded=true;}catch(Failure ignored){}finally{token=null;}}
        blocked="CLIENT_CLOSED";
        if(transport!=null)try{transport.close();}catch(Exception ignored){}
    }
    @Override public String toString(){return "AfClient[private configuration and session redacted]";}
}
