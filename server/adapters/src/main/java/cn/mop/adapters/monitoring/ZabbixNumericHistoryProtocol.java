package cn.mop.adapters.monitoring;

import cn.mop.domain.monitoring.MetricSeriesProjection.Scope;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Pure single-item Zabbix 7.0 numeric history codec. No HTTP, auth token or runtime/provider binding is installed. */
public final class ZabbixNumericHistoryProtocol {
    public static final int MAX_POINTS=10_000,MAX_RESPONSE_BYTES=2*1024*1024;
    public static final long MAX_REQUEST_ID=9_007_199_254_740_991L;
    public static final long MAX_SOURCE_CLOCK=253_402_271_999L; // Last supported Shanghai business-date second.
    private static final BigInteger UINT64_MAX=new BigInteger("18446744073709551615");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(256).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ZabbixNumericHistoryProtocol(){}
    public enum ValueType { NUMERIC_FLOAT(0),NUMERIC_UNSIGNED(3);private final int code;ValueType(int code){this.code=code;}public int code(){return code;} }
    public enum Outcome { POINTS_DECODED, HTTP_ERROR, API_ERROR, PROTOCOL_ERROR, RESPONSE_TOO_LARGE, POINT_LIMIT_EXCEEDED, UNSUPPORTED_NUMERIC_ENCODING }
    /** Supplied internal mapping identity only. Construction does not authorize it or verify a real mapping record. */
    public record ExplicitBinding(Scope scope,UUID resourcePoolId,String providerItemId,String mappingRevision){
        public ExplicitBinding{if(scope==null||resourcePoolId==null||!providerId(providerItemId)||mappingRevision==null||mappingRevision.length()>128||!mappingRevision.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))throw invalid();}
    }
    /** Both clock bounds are inclusive whole-second buckets, exactly matching provider time_from/time_till. */
    public record Query(ExplicitBinding binding,ValueType type,long fromClock,long throughClock,long requestId){
        public Query{if(binding==null||type==null||fromClock<0||throughClock<fromClock||throughClock>MAX_SOURCE_CLOCK||requestId<1||requestId>MAX_REQUEST_ID)throw invalid();}
    }
    public record Sample(String providerItemId,ValueType type,long clock,int nanoseconds,String originalValue){
        public Sample{if(!providerId(providerItemId)||type==null||clock<0||clock>MAX_SOURCE_CLOCK||nanoseconds<0||nanoseconds>999_999_999)throw invalid();numeric(originalValue,type);}
        /** Zabbix history clock/ns means received time; it is not proof of physical probe time or synchronized clocks. */
        public Instant receivedAt(){return Instant.ofEpochSecond(clock,nanoseconds);}
        public String sourceRecordKey(){return providerItemId+":"+clock+":"+nanoseconds;}
    }
    public static final class Result {
        private final Outcome outcome;private final Query query;private final List<Sample> samples;
        private Result(Outcome outcome,Query query,List<Sample> samples){this.outcome=outcome;this.query=query;this.samples=samples==null?null:List.copyOf(samples);}
        public Outcome outcome(){return outcome;}public Query query(){return query;}public List<Sample> samples(){return samples;}
        public Integer decodedCount(){return samples==null?null:samples.size();}
        public String scope(){return "SINGLE_EXPLICIT_ITEM_NUMERIC_HISTORY_CODEC_ONLY";}
        public String timestampMeaning(){return "SOURCE_RECEIVED_TIME_NOT_VERIFIED_PROBE_TIME";}
        public boolean sourceVerified(){return false;}public boolean mappingVerified(){return false;}public boolean authorizationVerified(){return false;}
        public boolean credentialsUsed(){return false;}public boolean transportPerformed(){return false;}public boolean completeWindowClaimed(){return false;}
        public Boolean monitoringReady(){return null;}
    }
    public static byte[] request(Query query){
        Objects.requireNonNull(query);var root=JSON.createObjectNode();root.put("jsonrpc","2.0");root.put("method","history.get");root.put("id",query.requestId());var params=root.putObject("params");params.put("history",query.type().code());params.putArray("itemids").add(query.binding().providerItemId());
        var output=params.putArray("output");for(String field:List.of("itemid","clock","ns","value"))output.add(field);
        params.put("time_from",query.fromClock());params.put("time_till",query.throughClock());params.putArray("sortfield").add("clock").add("ns");params.put("sortorder","ASC");params.put("limit",MAX_POINTS+1);
        try{return JSON.writeValueAsBytes(root);}catch(Exception failure){throw new IllegalStateException("Numeric history request encoding failed");}
    }
    public static Result decode(Query query,int httpStatus,String contentType,byte[] body){
        Objects.requireNonNull(query);
        if(httpStatus!=200)return result(Outcome.HTTP_ERROR,query);
        if(body!=null&&body.length>MAX_RESPONSE_BYTES)return result(Outcome.RESPONSE_TOO_LARGE,query);
        if(!jsonType(contentType)||body==null||body.length==0)return result(Outcome.PROTOCOL_ERROR,query);
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JsonNode root=JSON.readTree(text);
            if(root==null||!root.isObject()||!"2.0".equals(string(root.get("jsonrpc")))||!root.has("id")||!root.get("id").isIntegralNumber()||!root.get("id").canConvertToLong()||root.get("id").longValue()!=query.requestId())throw invalid();
            if(root.has("error")){
                JsonNode error=root.get("error");
                if(!fields(root,Set.of("jsonrpc","error","id"))
                        ||!(fields(error,Set.of("code","message"))||fields(error,Set.of("code","message","data")))
                        ||!error.get("code").isIntegralNumber()||!error.get("code").canConvertToInt()||string(error.get("message"))==null)throw invalid();
                return result(Outcome.API_ERROR,query); // Never echo provider error text/data, URLs or possible credentials.
            }
            if(!fields(root,Set.of("jsonrpc","result","id"))||!root.get("result").isArray())throw invalid();
            JsonNode rows=root.get("result");if(rows.size()>MAX_POINTS)return result(Outcome.POINT_LIMIT_EXCEEDED,query);
            List<Sample> samples=new ArrayList<>(rows.size());Instant previous=null;
            for(JsonNode row:rows){
                if(!fields(row,Set.of("itemid","clock","ns","value"))||!query.binding().providerItemId().equals(string(row.get("itemid"))))throw invalid();
                long clock=whole(row.get("clock"),MAX_SOURCE_CLOCK);int ns=(int)whole(row.get("ns"),999_999_999);
                if(clock<query.fromClock()||clock>query.throughClock())throw invalid();
                String value=string(row.get("value"));numeric(value,query.type());
                Sample sample=new Sample(query.binding().providerItemId(),query.type(),clock,ns,value);Instant time=sample.receivedAt();
                if(previous!=null&&!time.isAfter(previous))throw invalid();previous=time;samples.add(sample);
            }
            return new Result(Outcome.POINTS_DECODED,query,samples);
        }catch(UnsupportedNumeric failure){return result(Outcome.UNSUPPORTED_NUMERIC_ENCODING,query);}
        catch(Exception failure){return result(Outcome.PROTOCOL_ERROR,query);}
    }
    private static void numeric(String value,ValueType type){
        if(value==null||value.length()>82)throw new UnsupportedNumeric();
        if(type==ValueType.NUMERIC_UNSIGNED){if(!value.matches("(?:0|[1-9][0-9]{0,19})")||new BigInteger(value).compareTo(UINT64_MAX)>0)throw new UnsupportedNumeric();}
        else if(!value.matches("-?(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?")||value.startsWith("-0")&&new BigDecimal(value).signum()==0)throw new UnsupportedNumeric();
    }
    private static long whole(JsonNode node,long max){
        String value=string(node);if(value==null||value.length()>12||!value.matches("(?:0|[1-9][0-9]*)"))throw invalid();long parsed=Long.parseLong(value);if(parsed>max)throw invalid();return parsed;
    }
    private static String string(JsonNode node){return node!=null&&node.isTextual()?node.textValue():null;}
    private static boolean fields(JsonNode node,Set<String> keys){if(node==null||!node.isObject()||node.size()!=keys.size())return false;var names=node.fieldNames();while(names.hasNext())if(!keys.contains(names.next()))return false;return true;}
    private static boolean providerId(String value){return value!=null&&value.matches("[1-9][0-9]{0,19}")&&new BigInteger(value).compareTo(UINT64_MAX)<=0;}
    private static boolean jsonType(String value){if(value==null)return false;String[] parts=value.split(";",-1);if(parts.length<1||parts.length>2||!Set.of("application/json","application/json-rpc").contains(parts[0].trim().toLowerCase(Locale.ROOT)))return false;return parts.length==1||parts[1].trim().matches("(?i)charset\\s*=\\s*(?:utf-8|\"utf-8\")");}
    private static Result result(Outcome status,Query query){return new Result(status,query,null);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid explicit numeric-history protocol input");}
    private static final class UnsupportedNumeric extends IllegalArgumentException{UnsupportedNumeric(){super("Numeric history value is outside the supported bounded decimal encoding");}}
}
