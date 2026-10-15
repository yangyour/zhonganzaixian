package cn.mop.api;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

/** Complete Q01-Q31 catalog. Business decisions stay null; schema bounds come from the registered contract. */
public final class RuntimeConfigurationCatalog {
    private final ObjectMapper mapper;
    private final Map<String,Map<String,Object>> entries = new LinkedHashMap<>();
    public RuntimeConfigurationCatalog(ObjectMapper mapper){this.mapper=mapper;try(var input=getClass().getResourceAsStream("/config-baseline.json")){
        if(input==null)throw new IllegalStateException("Configuration catalog missing");
        for(JsonNode row:mapper.readTree(input).path("settings")){Map<String,Object> entry=mapper.convertValue(row,LinkedHashMap.class);String key=(String)entry.get("key");if(entries.put(key,entry)!=null)throw new IllegalStateException("Duplicate configuration key");}
        if(entries.size()!=185)throw new IllegalStateException("Unexpected configuration catalog");
    }catch(Exception e){throw new IllegalStateException("Configuration catalog unavailable",e);}}
    public Map<String,Object> entry(String key){var e=entries.get(key);if(e==null)throw new DomainException(404,"OBJECT_NOT_FOUND","配置键不存在或不可见");return e;}
    public List<Map<String,Object>> definitions(){return entries.keySet().stream().map(this::definition).toList();}
    public Map<String,Object> definition(String key){var e=entry(key);Map<String,Object> d=new LinkedHashMap<>();for(String field:List.of("key","description","type","scope","unit","allowed_range","contract_value_kind","question_ids","source"))d.put(field,e.get(field));d.put("editable",!"fixed_constraint".equals(e.get("contract_value_kind")));d.put("schema_version",1);d.put("definition_hash",hash(key));d.put("schema",schema(key));d.put("registered_value",e.get("value"));d.put("runtime_scope","CURRENT_TENANT");d.put("customer_confirmation_status","UNVERIFIED");return d;}
    public Map<String,Object> schema(String key){var e=entry(key);var s=new LinkedHashMap<String,Object>();s.put("type",e.get("type"));String type=(String)e.get("type"),range=String.valueOf(e.get("allowed_range"));
        if(type.equals("integer")||type.equals("number")){
            if(range.contains("非负"))s.put("minimum",0);else if(range.contains("正整数")||range.contains("正数"))s.put("exclusiveMinimum",0);
            if(range.contains("[0,1]")){s.put("minimum",0);s.put("maximum",1);}
            if(range.contains("0—100")){s.put("minimum",0);s.put("maximum",100);}
            var bound=java.util.regex.Pattern.compile("([≤≥])([0-9]+(?:\\.[0-9]+)?)").matcher(range);while(bound.find())s.put(bound.group(1).equals("≤")?"maximum":"minimum",new BigDecimal(bound.group(2)));
        }
        if(type.equals("string")){s.put("maxLength",2000);s.put("minLength",1);s.put("pattern",".*\\S.*");}
        if(type.equals("integer")){BigDecimal safe=new BigDecimal("9007199254740990");if(!s.containsKey("maximum")||new BigDecimal(s.get("maximum").toString()).compareTo(safe)>0)s.put("maximum",safe);s.put("x-technical-number-limit",true);}
        if("monitor.sample_interval_sec".equals(key))s.put("enum",List.of(30,60,120,300));
        if("monitor.page_refresh_sec".equals(key)){s.put("minimum",1);s.put("maximum",3600);s.put("x-technical-consumer-range",true);}
        // The registry describes these three keys in one sentence. Apply each key's own unit,
        // not every bound from that shared sentence (bytes > 0, ratio 0..1, multiplier >= 1).
        if(Set.of("capacity.bytes_per_value","capacity.trend_ratio","capacity.headroom").contains(key)){
            s.remove("minimum");s.remove("maximum");s.remove("exclusiveMinimum");
            if("capacity.bytes_per_value".equals(key))s.put("exclusiveMinimum",0);
            if("capacity.trend_ratio".equals(key)){s.put("minimum",0);s.put("maximum",1);}
            if("capacity.headroom".equals(key))s.put("minimum",1);
        }
        if(Set.of("oncall.handoff_local_time","backup.default_local_time").contains(key))s.put("pattern","^(?:[01][0-9]|2[0-3]):[0-5][0-9]$");
        if("itsm.local_authority_mode".equals(key))s.put("enum",List.of("MOP","EXTERNAL"));
        if("itsm.retry_backoff_sec".equals(key)){s.put("minItems",5);s.put("maxItems",5);s.put("items",Map.of("type","integer","minimum",1));}
        s.put("description",range);s.put("x-max-depth",12);s.put("x-max-container-items",1000);s.put("x-number-precision","SAFE_INTEGERS");return s;
    }
    public void validate(String key,JsonNode value){if(value!=null&&!value.isNull())depth(value,0);ConfigurationValueValidator.validate(entry(key),value);Map<String,Object> s=schema(key);
        if(s.containsKey("enum")&&((List<?>)s.get("enum")).stream().noneMatch(v->mapper.valueToTree(v).equals(value)))throw invalid("值不在明确允许的枚举范围内");
        if(s.containsKey("pattern")&&!value.asText().matches((String)s.get("pattern")))throw invalid("字符串为空或不符合登记格式");
        if(value.isNumber()){BigDecimal n=value.decimalValue();if(s.containsKey("minimum")&&n.compareTo(new BigDecimal(s.get("minimum").toString()))<0||s.containsKey("exclusiveMinimum")&&n.compareTo(BigDecimal.ZERO)<=0||s.containsKey("maximum")&&n.compareTo(new BigDecimal(s.get("maximum").toString()))>0)throw invalid("数值超出登记范围");}
        if(s.containsKey("minItems")&&(value.size()!=5||!allPositiveIntegers(value)))throw invalid("重试退避必须为5个正整数秒");
        depth(value,0);
    }
    private boolean allPositiveIntegers(JsonNode v){for(JsonNode n:v)if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<=0)return false;return true;}
    private void depth(JsonNode value,int depth){if(depth>12||value.isContainerNode()&&value.size()>1000)throw invalid("配置超过12层或单层1000项的技术上限");if(value.isIntegralNumber()&&value.bigIntegerValue().abs().compareTo(new java.math.BigInteger("9007199254740990"))>0)throw invalid("整数超过无损传输范围；精确大整数请使用定义支持的十进制字符串");if(value.isContainerNode())value.forEach(v->depth(v,depth+1));}
    public String hash(String key){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(mapper.valueToTree(Map.of("registry",entry(key),"schema",schema(key),"validator_version","runtime-config-v1"))))));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode canonical(JsonNode value){if(value.isObject()){var object=mapper.createObjectNode();TreeSet<String> names=new TreeSet<>();value.fieldNames().forEachRemaining(names::add);names.forEach(k->object.set(k,canonical(value.get(k))));return object;}if(value.isArray()){var array=mapper.createArrayNode();value.forEach(v->array.add(canonical(v)));return array;}return value;}
    private static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
}
