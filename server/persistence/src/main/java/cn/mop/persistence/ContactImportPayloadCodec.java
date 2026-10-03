package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.contact.*;
import cn.mop.domain.contact.CustomerContactImportPreview.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.security.MessageDigest;
import java.util.*;
/** Immutable bounded bytes with stable field/set ordering. Database-generated SHA is independently checked. */
final class ContactImportPayloadCodec {
    static final int MAX_BYTES=524288;
    private static final Set<String> FIELDS=Set.of("name","roles","mobile","email","wecom_user_id","preferred_priorities","quiet_window","enabled");
    private static final ObjectMapper MAPPER=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(2048).maxNumberLength(32).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ContactImportPayloadCodec(){}
    static byte[] encode(List<CustomerContact> rows){
        List<CustomerContact> stable;try{stable=List.copyOf(rows);}catch(RuntimeException failure){throw invalid();}if(stable.isEmpty()||stable.size()>100)throw invalid();
        try{byte[] bytes=MAPPER.writeValueAsBytes(Map.of("rows",stable.stream().map(ContactImportPayloadCodec::row).toList()));if(bytes.length>MAX_BYTES)throw invalid();return bytes;}catch(DomainException failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    static List<CustomerContact> decode(byte[] bytes,int count,String expectedHash){
        if(bytes==null||bytes.length<2||bytes.length>MAX_BYTES||count<1||count>100||expectedHash==null||!expectedHash.matches("[0-9a-f]{64}")||!hash(bytes).equals(expectedHash))throw invalid();
        try{try(var parser=MAPPER.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>8000)throw invalid();}
            JsonNode root=MAPPER.readTree(bytes);fields(root,Set.of("rows"));var values=root.get("rows");if(!values.isArray()||values.size()!=count)throw invalid();List<Row> rows=new ArrayList<>();
            for(JsonNode value:values){fields(value,FIELDS);var q=value.get("quiet_window");QuietInput quiet=null;if(!q.isNull()){fields(q,Set.of("enabled","start","end"));quiet=new QuietInput(bool(q.get("enabled")),text(q.get("start"),true),text(q.get("end"),true));}rows.add(new Row(text(value.get("name"),false),strings(value.get("roles"),3),text(value.get("mobile"),true),text(value.get("email"),true),text(value.get("wecom_user_id"),true),strings(value.get("preferred_priorities"),4),quiet,bool(value.get("enabled"))));}
            var preview=CustomerContactImportPreview.preview(rows);if(preview.invalidRows()!=0)throw invalid();var facts=preview.rows().stream().map(RowResult::contact).toList();if(!Arrays.equals(bytes,encode(facts)))throw invalid();return List.copyOf(facts);
        }catch(DomainException failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new IllegalStateException("Contact import hash unavailable");}}
    private static Map<String,Object> row(CustomerContact value){Map<String,Object> row=new LinkedHashMap<>();row.put("name",value.name());row.put("roles",value.roles().stream().map(Enum::name).sorted().toList());row.put("mobile",value.mobile());row.put("email",value.email());row.put("wecom_user_id",value.wecomUserId());row.put("preferred_priorities",value.preferredPriorities()==null?null:value.preferredPriorities().stream().map(Enum::name).sorted().toList());var q=value.quietWindow();Map<String,Object> quiet=null;if(q!=null){quiet=new LinkedHashMap<>();quiet.put("enabled",q.enabled());quiet.put("start",q.start()==null?null:q.start().toString());quiet.put("end",q.end()==null?null:q.end().toString());}row.put("quiet_window",quiet);row.put("enabled",value.enabled());return row;}
    private static void fields(JsonNode value,Set<String> expected){if(value==null||!value.isObject()||value.size()!=expected.size())throw invalid();value.fieldNames().forEachRemaining(key->{if(!expected.contains(key))throw invalid();});}
    private static String text(JsonNode value,boolean nullable){if(value.isNull()&&nullable)return null;if(!value.isTextual())throw invalid();return value.textValue();}
    private static Boolean bool(JsonNode value){if(value.isNull())return null;if(!value.isBoolean())throw invalid();return value.booleanValue();}
    private static List<String> strings(JsonNode value,int max){if(value.isNull())return null;if(!value.isArray()||value.size()>max)throw invalid();List<String> result=new ArrayList<>();for(var item:value)result.add(text(item,false));return result;}
    static DomainException invalid(){return new DomainException(400,"INVALID_CONTACT_IMPORT_PAYLOAD","联系人导入载荷、摘要或规范字段无效；没有执行部分导入");}
}
