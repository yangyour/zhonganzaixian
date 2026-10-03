package cn.mop.domain.assetmetadata;

import cn.mop.domain.Asset;
import java.time.Instant;
import java.util.List;

/** Internal authorized manual-draft maintenance; no IP, lifecycle, credential or monitoring mutation. */
public interface ManualAssetMetadataRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Snapshot(Asset asset,ManualAssetMetadata metadata,Long metadataVersion,ManualWarrantyDetails warrantyDetails) {
        public Snapshot(Asset asset,ManualAssetMetadata metadata,Long metadataVersion){this(asset,metadata,metadataVersion,null);}
        public Snapshot { if(asset==null||(metadata==null)!=(metadataVersion==null)||metadataVersion!=null&&(metadataVersion<2||metadataVersion>asset.version()))throw new IllegalArgumentException("Inconsistent manual asset snapshot"); if(warrantyDetails!=null){if(metadata==null)throw new IllegalArgumentException("Warranty requires a metadata revision");warrantyDetails.validateAgainstExpiry(metadata.warrantyExpiry());} }
    }
    record Current(Snapshot snapshot,String recordedBy,Instant recordedAt,List<String> editBlockers) { public Current { editBlockers=List.copyOf(editBlockers); } }
    record Revision(long assetVersion,Long previousMetadataVersion,String actorId,Instant recordedAt,Snapshot before,ManualAssetMetadata metadata,String managementIp,ManualWarrantyDetails warrantyDetails) {
        public Revision(long assetVersion,Long previousMetadataVersion,String actorId,Instant recordedAt,Snapshot before,ManualAssetMetadata metadata,String managementIp){this(assetVersion,previousMetadataVersion,actorId,recordedAt,before,metadata,managementIp,null);}
    }
    record Reference(String id,String label) {}
    record References(List<Reference> sites,List<Reference> owners,boolean sitesTruncated,boolean ownersTruncated) { public References { sites=List.copyOf(sites);owners=List.copyOf(owners); } }
    /** Read-only bounded choices; selected references are rechecked during the actual write. */
    References references(Access access,String assetId,String query);
    /** Current tenant manual-source view; creation cursor is a technical keyset, not a frozen cross-page snapshot. */
    List<Current> search(Access access,ManualAssetFilter filters,int limit,Instant beforeCreatedAt,String beforeId);
    /** Same authorized snapshot and keyset as manual search; date predicate uses only the explicit supplied reference day. */
    List<Current> searchDates(Access access,ManualAssetFilter filters,AssetDateFilter dates,int limit,Instant beforeCreatedAt,String beforeId);
    Current current(Access access,String assetId);
    List<Revision> history(Access access,String assetId,int limit,Long beforeVersion);
    /** Caller must use the platform idempotent transaction; implementation locks and reauthorizes all references. */
    Current update(Access access,String assetId,long expectedVersion,ManualAssetMetadata metadata);
    /** Authorization runs inside the idempotent transaction before any cached response is returned. */
    void authorizeCurrentAsset(Access access,String assetId);
    /** Explicit supplemental declaration and coupled dates; unrelated base facts are preserved under the asset lock. */
    Current updateWarranty(Access access,String assetId,long expectedVersion,ManualWarrantyUpdate warranty);
    List<String> readinessProblems();
}
