package cn.mop.domain.assetmetadata;
import cn.mop.domain.DomainException;
import java.time.LocalDate;
/** Optional explicitly recorded warranty facts. A missing wrapper means legacy/unrecorded, not an explicit clear. */
public record ManualWarrantyDetails(LocalDate warrantyStart,String warrantySupplier) {
    public ManualWarrantyDetails {validDate(warrantyStart);if(warrantySupplier!=null&&(warrantySupplier.isEmpty()||warrantySupplier.codePointCount(0,warrantySupplier.length())>200||warrantySupplier.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||warrantySupplier.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)))throw invalid();}
    public void validateAgainstExpiry(LocalDate expiry){validDate(expiry);if(warrantyStart!=null&&expiry!=null&&warrantyStart.isAfter(expiry))throw new DomainException(400,"VALIDATION_ERROR","维保起始日不能晚于已明确登记的维保到期日；未知日期不会自动补齐");}
    /** Existing callers omit both new fields; that must preserve known values rather than silently null them. */
    public static ManualWarrantyDetails forUpdate(ManualWarrantyDetails current,ManualWarrantyDetails requested,boolean supplied){if(supplied!=(requested!=null))throw invalid();return supplied?requested:current;}
    public boolean coverageVerified(){return false;}
    public String verificationStatus(){return "SELF_REPORTED";}
    private static void validDate(LocalDate value){if(value!=null&&(value.getYear()<1||value.getYear()>9999))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","补充维保资料需明确的可空起始日和可空供应商；日期0001至9999年，供应商1至200个非空白Unicode字符，拒绝控制字符；未提供字段不能冒充显式清空");}
}
