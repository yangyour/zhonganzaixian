package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Input validation only; customer identity and object visibility come from the trusted session. */
final class CustomerReportValidator {
    static final Set<String> CATEGORIES=Set.of("网络不通","网速慢","WiFi问题","设备异常","其他");
    private static final Set<String> FIELDS=Set.of("title","description","source","asset_id","incident_id","category","impact_scope","contact_name","contact_phone","urgency","attachment_ids");
    private CustomerReportValidator() {}
    static CustomerReport parse(JsonNode body,String role){
        if(body==null||!body.isObject())throw invalid("请求体必须为JSON对象");
        body.fieldNames().forEachRemaining(field->{if(!FIELDS.contains(field))throw invalid("存在未知或禁止字段");});
        String source=enumeration(body,"source",Set.of("customer_report","manual","incident")),description=text(body,"description",3000,true);
        if(!source.equals("customer_report")){
            if(!Capabilities.internal(role))throw new DomainException(403,"ACTION_FORBIDDEN","客户只能提交 customer_report 报障");
            if(source.equals("incident"))uuid(body,"incident_id");
            throw pending(List.of("Q07","Q09"),"内部手工或事件工单入口尚未确认，未受理");
        }
        if(!Capabilities.customer(role))throw new DomainException(403,"ACTION_FORBIDDEN","内部角色不能代客户报障");
        if(body.has("incident_id"))throw invalid("客户报障不能指定 incident_id");
        String category=enumeration(body,"category",CATEGORIES),impact=enumeration(body,"impact_scope",Set.of("company","partial_area","individual")),urgency=enumeration(body,"urgency",Set.of("normal","urgent"));
        String name=text(body,"contact_name",100,false),phone=text(body,"contact_phone",64,false);
        if(!phone.matches("[0-9 +()\\-]+")||!phone.matches(".*[0-9].*"))throw invalid("contact_phone 仅允许数字、空格及+-()，必须含数字");
        String title=body.has("title")?text(body,"title",200,false):null,asset=body.has("asset_id")?uuid(body,"asset_id"):null;
        if(body.has("attachment_ids")){
            JsonNode attachments=body.get("attachment_ids");if(!attachments.isArray()||attachments.size()>3)throw invalid("attachment_ids 必须为数组且最多3张图片");
            Set<String> ids=new HashSet<>();for(JsonNode id:attachments){if(!ids.add(uuidValue(id,"attachment_ids")))throw invalid("attachment_ids 不得重复");}
            if(!ids.isEmpty())throw pending(List.of("Q28"),"文件服务尚未可用，无法验证图片归属、内容和大小，未受理附件报障");
        }
        return new CustomerReport(title,description,category,impact,name,phone,urgency,asset);
    }
    static String enumeration(JsonNode body,String field,Set<String> values){
        JsonNode value=body.get(field);if(value==null||!value.isTextual()||!values.contains(value.asText()))throw invalid(field+" 不在允许枚举中");return value.asText();
    }
    static String text(JsonNode body,String field,int maximum,boolean multiline){
        JsonNode value=body.get(field);if(value==null||!value.isTextual())throw invalid(field+" 必须为字符串");String raw=value.asText(),result=raw.strip();
        if(result.isEmpty()||raw.codePointCount(0,raw.length())>maximum||raw.codePoints().anyMatch(cp->ConfigurationValueValidator.invalidCharacter(cp)&&!(multiline&&(cp=='\n'||cp=='\r'||cp=='\t'))))throw invalid(field+" 为空、过长或含非法字符");return result;
    }
    private static String uuid(JsonNode body,String field){return uuidValue(body.get(field),field);}
    private static String uuidValue(JsonNode value,String field){if(value==null||!value.isTextual()||!value.asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid(field+" 必须为UUID");return UUID.fromString(value.asText()).toString();}
    static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
    private static DomainException pending(List<String> questions,String message){return new DomainException(422,"CONFIG_PENDING",message,List.of(ApiResponse.map("question_ids",questions)));}
}
