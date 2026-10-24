package cn.mop.domain.assetmetadata;

import java.time.Instant;
import java.util.List;

/** Public ledger fields only; internal notes, credentials, prices and arbitrary metadata are excluded. */
public interface AssetLedgerFactsRepository {
    record Access(String actorId, String tenantId, long permissionVersion) {}

    record Owner(String id, String displayName, String status) {}

    /** A manual declaration is not a verified CMDB/business-system assignment. */
    record Facts(String assetId, long assetVersion, String siteId, String siteName, Long metadataVersion,
                 Instant metadataRecordedAt, String metadataSource, String businessDescription, Owner owner) {}

    record Snapshot(String tenantId, Instant observedAt, List<Facts> items) {
        public Snapshot {
            items = List.copyOf(items);
        }
    }

    /** One authorized snapshot; any foreign/missing explicit asset makes the entire request a 404. */
    Snapshot read(Access access, List<String> assetIds);
}
