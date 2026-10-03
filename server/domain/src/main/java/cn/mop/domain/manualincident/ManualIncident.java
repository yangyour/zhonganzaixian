package cn.mop.domain.manualincident;

import cn.mop.domain.DomainException;
import java.util.UUID;

/** Explicit M5-6 manual-source facts only; never a monitoring sample, formal ticket state or recovery attestation. */
public record ManualIncident(String title,Severity severity,String assetId,long observedAssetVersion) {
    public static final int MAX_TITLE_CODE_POINTS=200;
    /** The five contract severity labels; no default, inferred priority or implicit unclassified value. */
    public enum Severity {
        DISASTER("disaster","灾难"),HIGH("high","严重"),AVERAGE("average","一般"),WARNING("warning","警告"),INFO("info","信息");
        private final String code,label;
        Severity(String code,String label){this.code=code;this.label=label;}
        public String code(){return code;}public String label(){return label;}
        public static Severity fromCode(String value){for(Severity item:values())if(item.code.equals(value))return item;throw invalid();}
    }
    public ManualIncident {
        if(title==null||title.isEmpty()||title.codePointCount(0,title.length())>MAX_TITLE_CODE_POINTS||title.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||title.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)||severity==null||assetId==null||!assetId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")||observedAssetVersion<1||observedAssetVersion>9007199254740991L)throw invalid();
        assetId=UUID.fromString(assetId).toString();
    }
    public String sourceState(){return "MANUAL";}
    public String monitorState(){return null;}
    public String dispositionState(){return null;}
    public String ticketPriority(){return null;}
    public java.time.Instant observedAt(){return null;}
    public java.time.Instant recoveredAt(){return null;}
    public String manualRecoveryStatus(){return "NOT_CONFIRMED";}
    public String registrationStatus(){return "REGISTERED_ONLY";}
    public String verificationStatus(){return "SELF_REPORTED";}
    public boolean monitoringRecoveryApplicable(){return false;}
    public boolean ticketAutomaticallyCreated(){return false;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","手工事件需明确原始标题、合同严重级别、关联资产和所见资产版本；不接受缺值或默认级别");}
}
