package cn.mop.domain.assetmetadata;

import cn.mop.domain.DomainException;
import java.util.*;

/** Explicit conjunctive filters for the internal manual-draft view; no inferred warranty state. */
public record ManualAssetFilter(Map<Field,String> values,Set<Field> unknownFields) {
    public enum Field {
        NAME("name",100,false),TYPE("type",32,false),SITE_ID("site_id",64,false),BRAND("brand",100,true),MODEL("model",100,true),SERIAL_NUMBER("serial_number",128,true),LOCATION("location",200,true),OWNER_ID("owner_id",36,true),BUSINESS_DESCRIPTION("business_description",200,true),PURCHASE_DATE("purchase_date",10,true),WARRANTY_EXPIRY("warranty_expiry",10,true),OVERDUE_SERVICE_DATE("overdue_service_date",10,true),COLLECTION_METHOD_NOTE("collection_method_note",100,true),NOTE("note",2000,true);
        private final String key;private final int max;private final boolean nullable;
        Field(String key,int max,boolean nullable){this.key=key;this.max=max;this.nullable=nullable;}
        public String key(){return key;}
        public boolean nullable(){return nullable;}
        public boolean date(){return this==PURCHASE_DATE||this==WARRANTY_EXPIRY||this==OVERDUE_SERVICE_DATE;}
        public boolean exact(){return this==TYPE||this==SITE_ID||this==OWNER_ID||date();}
        public static Field from(String key){return Arrays.stream(values()).filter(field->field.key.equals(key)).findFirst().orElseThrow(ManualAssetFilter::invalid);}
    }
    public ManualAssetFilter {
        if(values==null||unknownFields==null)throw invalid();EnumMap<Field,String> normalized=new EnumMap<>(Field.class);EnumSet<Field> absent=EnumSet.noneOf(Field.class);
        for(var entry:values.entrySet()){
            Field field=entry.getKey();String value=entry.getValue();if(field==null||value==null||value.isEmpty()||value.codePointCount(0,value.length())>field.max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.isISOControl(cp)&&!(field==Field.NOTE&&(cp=='\n'||cp=='\r'||cp=='\t'))||Character.getType(cp)==Character.SURROGATE))throw invalid();
            if(field==Field.TYPE&&!Set.of("router","switch","firewall","ap","wireless_controller","other").contains(value))throw invalid();
            if(field==Field.OWNER_ID){if(!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();value=UUID.fromString(value).toString();}
            if(field.date())ManualAssetMetadata.parseDate(value);normalized.put(field,value);
        }
        for(Field field:unknownFields){if(field==null||!field.nullable||normalized.containsKey(field))throw invalid();absent.add(field);}
        values=Collections.unmodifiableMap(normalized);unknownFields=Collections.unmodifiableSet(absent);
    }
    public static ManualAssetFilter fromQuery(Map<String,String> query){
        if(query==null)throw invalid();EnumMap<Field,String> values=new EnumMap<>(Field.class);EnumSet<Field> unknown=EnumSet.noneOf(Field.class);
        query.forEach((key,value)->{if("unknown_fields".equals(key)){if(value==null||value.isEmpty()||value.length()>256)throw invalid();for(String item:value.split(",",-1)){Field field=Field.from(item);if(!unknown.add(field))throw invalid();}}else values.put(Field.from(key),value);});return new ManualAssetFilter(values,unknown);
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","资料筛选仅接受明确字段、字面文本、严格日期和可空字段；不能同时指定值与未知，也不推断维保状态");}
}
