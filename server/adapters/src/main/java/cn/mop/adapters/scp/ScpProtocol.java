package cn.mop.adapters.scp;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Janus 20180725 read protocol from the supplied SCP OpenAPI v6.11.1 PDF.
 * These DTOs are source observations, not MOP asset authorization or verified device health.
 */
public final class ScpProtocol {
    private ScpProtocol(){}
    public static final int MAX_BODY=2*1024*1024;
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(32768).maxNumberLength(80).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public static final class Failure extends RuntimeException {
        public final String reason;
        public Failure(String reason){super("SCP connector: "+reason);this.reason=reason;}
    }
    public enum Kind {CLUSTER("clusters"),HOST("hosts"),SERVER("servers");final String path;Kind(String path){this.path=path;}}
    public record Scope(String poolId,String projectId){public Scope{identifier(poolId);identifier(projectId);}}
    public static final class Request {
        private final String method,path;private final SortedMap<String,String> query;private final byte[] body;
        private Request(String method,String path,Map<String,String> query,byte[] body){this.method=method;this.path=path;this.query=Collections.unmodifiableSortedMap(new TreeMap<>(query));this.body=body.clone();}
        public String method(){return method;}public String path(){return path;}public byte[] body(){return body.clone();}
        public String target(){String queryString=String.join("&",query.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).toList());return path+(queryString.isEmpty()?"":"?"+queryString);}
        @Override public String toString(){return "ScpRequest["+method+", body and target redacted]";}
    }
    public record Observation(String id,String name,String sourceStatus,String poolId,String projectId,
                              Map<String,String> attributes,Map<String,String> quantities,
                              Instant receivedAt,Instant sampledAt){
        public Observation {attributes=Map.copyOf(attributes);quantities=Map.copyOf(quantities);if(sampledAt!=null)throw new IllegalArgumentException("Source sample time not provided");}
        @Override public String toString(){return "ScpObservation[identifiers and source values redacted]";}
    }
    public record Page(List<Observation> items,int pageNumber,int pageSize,long reportedTotal,Integer nextPage){
        public Page{items=List.copyOf(items);}
        public boolean completeInventoryVerified(){return false;}
    }
    public static URI origin(String value){
        try{URI uri=URI.create(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
            ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!Set.of("","/").contains(uri.getRawPath())
            ||uri.getPort()==0||uri.getPort()>65535)throw fail("INVALID_HTTPS_ORIGIN");
            return URI.create("https://"+uri.getRawAuthority());
        }catch(Exception failure){throw fail("INVALID_HTTPS_ORIGIN");}
    }
    public static Request publicKey(boolean v2){return new Request("GET",v2?"/janus/v2/public-key":"/janus/public-key",Map.of(),new byte[0]);}
    public static Request authenticate(String username,String encryptedPassword){
        if(username==null||username.isBlank()||username.length()>128||username.chars().anyMatch(Character::isISOControl)
            ||encryptedPassword==null||!encryptedPassword.matches("[0-9a-f]{256,2048}"))throw fail("INVALID_LOGIN_INPUT");
        try{return new Request("POST","/janus/authenticate",Map.of(),JSON.writeValueAsBytes(Map.of("auth",Map.of("passwordCredentials",Map.of("username",username,"password",encryptedPassword)))));}
        catch(Exception failure){throw fail("INVALID_LOGIN_INPUT");}
    }
    public static Request version(){return get("/janus/20180725/system/version",Map.of());}
    public static Request pool(Scope scope){return get("/janus/20180725/azs/"+identifier(scope.poolId()),Map.of());}
    public static Request page(Kind kind,Scope scope,int pageNumber,int pageSize){
        Objects.requireNonNull(kind);Objects.requireNonNull(scope);
        if(pageNumber<0||pageNumber>10000||pageSize<1||pageSize>100)throw fail("INVALID_PAGE_REQUEST");
        Map<String,String> query=new HashMap<>(Map.of("az_id",scope.poolId(),"page_num",""+pageNumber,"page_size",""+pageSize));
        if(kind==Kind.SERVER)query.put("project_id",scope.projectId());
        return get("/janus/20180725/"+kind.path,query);
    }
    private static Request get(String path,Map<String,String> query){return new Request("GET",path,query,new byte[0]);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    static String identifier(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw fail("INVALID_SOURCE_IDENTIFIER");return value;}
    public static JsonNode envelope(int status,String contentType,byte[] bytes){
        if(status==401)throw fail("AUTHENTICATION_REJECTED");if(status==403)throw fail("ACCESS_DENIED");
        if(status==429)throw fail("RATE_LIMITED");if(status!=200)throw fail("HTTP_STATUS_REJECTED");
        if(contentType==null||!contentType.toLowerCase(Locale.ROOT).matches("application/json(?:;.*)?")
            ||bytes==null||bytes.length==0||bytes.length>MAX_BODY)throw fail("INVALID_RESPONSE_FORMAT");
        try{
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
            JsonNode root=JSON.readTree(bytes);
            if(!root.isObject()||!root.path("message").isTextual()||!root.path("code").isIntegralNumber()||!root.has("data")||root.has("errcode"))throw fail("UNSUPPORTED_ENVELOPE");
            if(root.get("code").bigIntegerValue().signum()!=0)throw fail("PROVIDER_ERROR");
            return root.get("data");
        }catch(Failure failure){throw failure;}catch(Exception failure){throw fail("INVALID_JSON");}
    }
    public static String modulus(JsonNode data){return requiredText(data,"public_key",2050);}
    public static Page decodePage(JsonNode data,Kind kind,Scope scope,int pageNumber,int pageSize,Instant receivedAt){
        if(data==null||!data.isObject()||!data.path("data").isArray())throw fail("INVALID_PAGE_RESPONSE");
        long total=integer(data,"total_size"),actualPage=integer(data,"page_num"),actualSize=integer(data,"page_size");
        String next=requiredText(data,"next_page_num",12);JsonNode rows=data.get("data");
        if(actualPage!=pageNumber||actualSize!=pageSize||rows.size()>pageSize||total<rows.size())throw fail("PAGINATION_MISMATCH");
        Integer nextPage=null;
        if(!next.isEmpty()){
            if(!next.matches("0|[1-9][0-9]{0,4}"))throw fail("INVALID_NEXT_PAGE");nextPage=Integer.valueOf(next);
            if(nextPage<=pageNumber||nextPage>10000||rows.isEmpty())throw fail("INVALID_NEXT_PAGE");
        }
        List<Observation> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(JsonNode row:rows){
            String id=identifier(requiredText(row,"id",128)),pool=requiredText(row,"az_id",128);
            if(!ids.add(id)||!pool.equals(scope.poolId()))throw fail("SOURCE_SCOPE_MISMATCH");
            String project=optionalText(row,"project_id",128);
            if(kind==Kind.SERVER&&!scope.projectId().equals(project))throw fail("SOURCE_SCOPE_MISMATCH");
            Map<String,String> attributes=new LinkedHashMap<>(),quantities=new LinkedHashMap<>();
            for(String field:List.of("ip","type","version","host_id")){String value=optionalText(row,field,256);if(value!=null)attributes.put(field,value);}
            String suffix=kind==Kind.SERVER?"_status":"";
            quantities(row,"cpu"+suffix,"cpu",List.of("total_mhz","used_mhz","ratio"),quantities);
            quantities(row,"memory"+suffix,"memory",List.of("total_mb","used_mb","ratio"),quantities);
            quantities(row,"storage"+suffix,"storage",List.of("total_mb","used_mb","ratio"),quantities);
            result.add(new Observation(id,optionalText(row,"name",256),optionalText(row,"status",80),pool,project,attributes,quantities,receivedAt,null));
        }
        return new Page(result,pageNumber,pageSize,total,nextPage);
    }
    /** Missing values stay absent. Source ratios/MB are preserved, not converted into policy or billed units. */
    private static void quantities(JsonNode row,String field,String prefix,List<String> names,Map<String,String> result){
        JsonNode object=row.get(field);if(object==null||object.isNull())return;if(!object.isObject())throw fail("INVALID_QUANTITY_OBJECT");
        for(String name:names){JsonNode value=object.get(name);if(value==null||value.isNull())continue;
            if(!value.isNumber())throw fail("INVALID_QUANTITY");BigDecimal number=value.decimalValue();
            if(number.signum()<0||number.precision()>60||Math.abs(number.scale())>40)throw fail("INVALID_QUANTITY");
            result.put(prefix+"."+name,number.toPlainString());}
    }
    public static Map<String,String> decodePool(JsonNode data,Scope scope){
        if(!requiredText(data,"id",128).equals(scope.poolId()))throw fail("SOURCE_SCOPE_MISMATCH");
        Map<String,String> result=new LinkedHashMap<>();result.put("id",scope.poolId());
        for(String field:List.of("name","status","type","version")){String value=optionalText(data,field,256);if(value!=null)result.put(field,value);}
        return Map.copyOf(result);
    }
    static long integer(JsonNode node,String field){JsonNode value=node.get(field);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw fail("INVALID_INTEGER");return value.longValue();}
    static String requiredText(JsonNode node,String field,int maximum){String value=optionalText(node,field,maximum);if(value==null)throw fail("MISSING_SOURCE_FIELD");return value;}
    static String optionalText(JsonNode node,String field,int maximum){
        if(node==null||!node.isObject())throw fail("INVALID_SOURCE_OBJECT");JsonNode value=node.get(field);if(value==null||value.isNull())return null;
        if(!value.isTextual()||value.textValue().length()>maximum)throw fail("INVALID_SOURCE_FIELD");return value.textValue();
    }
    static Failure fail(String reason){return new Failure(reason);}
}
