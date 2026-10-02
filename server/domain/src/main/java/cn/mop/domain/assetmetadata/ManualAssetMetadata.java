package cn.mop.domain.assetmetadata;

import cn.mop.domain.Asset;
import cn.mop.domain.DomainException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Manual draft facts only. No management-IP mutation, CMDB overwrite, credential or monitoring configuration. */
public record ManualAssetMetadata(String name,String type,String siteId,String brand,String model,String serialNumber,
                                  String location,String ownerId,String businessDescription,LocalDate purchaseDate,
                                  LocalDate warrantyExpiry,LocalDate overdueServiceDate,String collectionMethodNote,String note) {
    private static final Set<String> TYPES=Set.of("router","switch","firewall","ap","wireless_controller","other");
    public ManualAssetMetadata {
        required(name,100,false);if(!TYPES.contains(type==null?"":type))throw invalid();required(siteId,64,false);
        optional(brand,100);optional(model,100);optional(serialNumber,128);optional(location,200);optional(businessDescription,200);optional(collectionMethodNote,100);required(note,2000,true);
        if(ownerId!=null){if(!ownerId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();ownerId=UUID.fromString(ownerId).toString();}
        for(LocalDate date:new LocalDate[]{purchaseDate,warrantyExpiry,overdueServiceDate})if(date!=null&&(date.getYear()<1||date.getYear()>9999))throw invalid();
    }
    public static LocalDate parseDate(String value){if(value==null)return null;if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();try{LocalDate date=LocalDate.parse(value);if(date.getYear()<1||date.getYear()>9999)throw invalid();return date;}catch(DateTimeParseException invalid){throw invalid();}}
    /** Eligibility alone is not tenant/role authorization; repository must lock/recheck both before writing. */
    public static List<String> editBlockers(Asset asset){
        if(asset==null)return List.of("ASSET_NOT_AVAILABLE");List<String> reasons=new ArrayList<>();
        if(!"manual".equals(asset.source())||!"DRAFT".equals(asset.lifecycleStatus()))reasons.add("MANUAL_DRAFT_REQUIRED");
        if(!"unknown".equals(asset.status())||asset.sampledAt()!=null||!"UNKNOWN".equals(asset.availability())||!"PENDING_ONBOARDING".equals(asset.syncState()))reasons.add("MONITORING_STATE_NOT_PRISTINE");
        return List.copyOf(reasons);
    }
    private static void optional(String value,int max){if(value!=null)required(value,max,false);}
    private static void required(String value,int max,boolean multiline){
        if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&!(multiline&&(cp=='\n'||cp=='\r'||cp=='\t'))))throw invalid();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","手工草稿资料需要明确名称、既有类型、站点与备注；可选字段用null，日期为严格ISO日期，引用和文本须符合技术边界");}
}
