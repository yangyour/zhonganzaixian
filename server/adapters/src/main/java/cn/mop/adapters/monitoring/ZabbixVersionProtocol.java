package cn.mop.adapters.monitoring;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Pure codec only. No HTTP, credentials, database, monitoring observation or readiness decision. */
public final class ZabbixVersionProtocol {
    public static final int MAX_RESPONSE_BYTES=16384;
    public static final long MAX_REQUEST_ID=9007199254740991L;
    private static final String VERSION="(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,3})";
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(256).maxNumberLength(32).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ZabbixVersionProtocol(){}
    public enum Outcome { VERSION_READ, UNSUPPORTED_VERSION, HTTP_ERROR, PROTOCOL_ERROR, API_ERROR }
    public record Result(Outcome outcome,String apiVersion) {
        public Result {if(outcome==null)throw invalid();boolean version=outcome==Outcome.VERSION_READ||outcome==Outcome.UNSUPPORTED_VERSION;if(version?(apiVersion==null||!apiVersion.matches(VERSION)||(outcome==Outcome.VERSION_READ)!=apiVersion.startsWith("7.0.")):apiVersion!=null)throw invalid();}
        public String scope(){return "UNAUTHENTICATED_API_VERSION_ONLY";}
        public Boolean monitoringReady(){return null;}
        public boolean credentialsUsed(){return false;}
        public boolean observationsRead(){return false;}
        public boolean serverSchedulerVerified(){return false;}
        public boolean tenantMappingVerified(){return false;}
        public boolean providerPermissionsVerified(){return false;}
    }
    /** Format validation is not network authorization. Future callers must still require the approved private target. */
    public static URI requireLoopbackEndpoint(URI endpoint){
        if(endpoint==null||!Set.of("http","https").contains(endpoint.getScheme()==null?"":endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()<1||endpoint.getPort()>65535||!Objects.equals(endpoint.getRawAuthority(),"127.0.0.1:"+endpoint.getPort())||endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getRawFragment()!=null||!Set.of("/api_jsonrpc.php","/zabbix/api_jsonrpc.php").contains(endpoint.getRawPath()))throw invalid();
        return endpoint;
    }
    /** Zabbix 7.0 apiinfo.version is unauthenticated and takes an empty array, not an auth token or generic method. */
    public static byte[] request(long id){id(id);try{return JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","method","apiinfo.version","params",List.of(),"id",id));}catch(Exception failure){throw new IllegalStateException("Version request encoding failed");}}
    public static Result decode(int status,String contentType,byte[] bytes,long expectedId){
        id(expectedId);if(status!=200)return new Result(Outcome.HTTP_ERROR,null);
        if(!jsonType(contentType)||bytes==null||bytes.length==0||bytes.length>MAX_RESPONSE_BYTES)return new Result(Outcome.PROTOCOL_ERROR,null);
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(var parser=JSON.getFactory().createParser(text)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>64)return protocol();}
            JsonNode root=JSON.readTree(text);if(root==null||!root.isObject()||!root.path("jsonrpc").isTextual()||!root.path("jsonrpc").textValue().equals("2.0")||!root.path("id").isIntegralNumber()||!root.path("id").canConvertToLong()||root.path("id").longValue()!=expectedId)return protocol();
            Set<String> fields=new HashSet<>();root.fieldNames().forEachRemaining(fields::add);
            if(fields.equals(Set.of("jsonrpc","id","error"))){JsonNode error=root.get("error");if(!error.isObject()||!error.path("code").isIntegralNumber()||!error.path("code").canConvertToInt()||!error.path("message").isTextual())return protocol();return new Result(Outcome.API_ERROR,null);}
            if(!fields.equals(Set.of("jsonrpc","id","result"))||!root.path("result").isTextual())return protocol();String version=root.get("result").textValue();if(!version.matches(VERSION))return protocol();return new Result(version.startsWith("7.0.")?Outcome.VERSION_READ:Outcome.UNSUPPORTED_VERSION,version);
        }catch(Exception malformed){return protocol();}
    }
    private static Result protocol(){return new Result(Outcome.PROTOCOL_ERROR,null);}
    private static boolean jsonType(String value){if(value==null)return false;String[] parts=value.split(";",-1);if(parts.length<1||parts.length>2||!Set.of("application/json","application/json-rpc").contains(parts[0].trim().toLowerCase(Locale.ROOT)))return false;return parts.length==1||parts[1].trim().matches("(?i)charset\\s*=\\s*(?:utf-8|\"utf-8\")");}
    private static void id(long value){if(value<1||value>MAX_REQUEST_ID)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid configured loopback version-probe input");}
}
