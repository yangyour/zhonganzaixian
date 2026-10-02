package cn.mop.domain.assetmetadata;

import cn.mop.domain.Asset;
import java.time.Instant;
import java.util.List;

/** Internal authorized manual-draft maintenance; no IP, lifecycle, credential or monitoring mutation. */
public interface ManualAssetMetadataRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Snapshot(Asset asset,ManualAssetMetadata metadata,Long metadataVersion) {
        public Snapshot { if(asset==null||(metadata==null)!=(metadataVersion==null)||metadataVersion!=null&&(metadataVersion<2||metadataVersion>asset.version()))throw new IllegalArgumentException("Inconsistent manual asset snapshot"); }
    }
    record Current(Snapshot snapshot,String recordedBy,Instant recordedAt,List<String> editBlockers) { public Current { editBlockers=List.copyOf(editBlockers); } }
    record Revision(long assetVersion,Long previousMetadataVersion,String actorId,Instant recordedAt,Snapshot before,ManualAssetMetadata metadata,String managementIp) {}
    record Reference(String id,String label) {}
    record References(List<Reference> sites,List<Reference> owners,boolean sitesTruncated,boolean ownersTruncated) { public References { sites=List.copyOf(sites);owners=List.copyOf(owners); } }
    /** Read-only bounded choices; selected references are rechecked during the actual write. */
    References references(Access access,String assetId,String query);
    /** Current tenant manual-source view; creation cursor is a technical keyset, not a frozen cross-page snapshot. */
    List<Current> search(Access access,ManualAssetFilter filters,int limit,Instant beforeCreatedAt,String beforeId);
    Current current(Access access,String assetId);
    List<Revision> history(Access access,String assetId,int limit,Long beforeVersion);
    /** Caller must use the platform idempotent transaction; implementation locks and reauthorizes all references. */
    Current update(Access access,String assetId,long expectedVersion,ManualAssetMetadata metadata);
    List<String> readinessProblems();
}
