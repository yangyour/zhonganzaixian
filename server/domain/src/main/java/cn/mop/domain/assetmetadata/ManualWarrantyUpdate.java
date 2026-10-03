package cn.mop.domain.assetmetadata;

import cn.mop.domain.Asset;
import cn.mop.domain.DomainException;
import java.time.LocalDate;

/** Explicit atomic warranty form. The note is the asset metadata note, not an invisible side comment. */
public record ManualWarrantyUpdate(ManualWarrantyDetails details,LocalDate warrantyExpiry,LocalDate overdueServiceDate,String note) {
    public ManualWarrantyUpdate {
        if(details==null)throw new DomainException(400,"VALIDATION_ERROR","必须明确提供补充维保资料，未知值使用null");
        details.validateAgainstExpiry(warrantyExpiry);
        // Reuse existing exact date/note boundaries; this validation object is never persisted.
        new ManualAssetMetadata("validation","other","validation",null,null,null,null,null,null,null,warrantyExpiry,overdueServiceDate,null,note);
    }
    public ManualAssetMetadata applyTo(Asset asset,ManualAssetMetadata current){
        if(asset==null)throw new IllegalArgumentException("Asset is required");
        if(current==null)return new ManualAssetMetadata(asset.name(),asset.type(),asset.siteId(),null,null,null,null,null,null,null,warrantyExpiry,overdueServiceDate,null,note);
        return new ManualAssetMetadata(current.name(),current.type(),current.siteId(),current.brand(),current.model(),current.serialNumber(),current.location(),current.ownerId(),current.businessDescription(),current.purchaseDate(),warrantyExpiry,overdueServiceDate,current.collectionMethodNote(),note);
    }
}
