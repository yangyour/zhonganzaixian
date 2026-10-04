package cn.mop.domain.assetexport;

import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.Current;
import java.time.Instant;
import java.util.List;

/** Complete bounded main-ledger selection at one authorized database snapshot, not a published export. */
public interface AssetExportSnapshotRepository {
    record Selection(AssetExportJobState.Command command,Instant transactionStartedAt,List<Current> rows) {
        public Selection {
            if(command==null||transactionStartedAt==null||rows==null||rows.size()>AssetExportJobState.MAX_DATA_ROWS)throw new IllegalArgumentException("Invalid export snapshot");
            rows=List.copyOf(rows);
            var ids=new java.util.HashSet<String>();
            for(Current row:rows){
                if(row.snapshot()==null||!command.acceptedAccess().tenantId().toString().equals(row.snapshot().asset().tenantId()))throw new cn.mop.domain.DomainException(404,"OBJECT_NOT_FOUND","Export snapshot not found");
                if(!ids.add(row.snapshot().asset().id()))throw new IllegalArgumentException("Duplicate export asset");
            }
        }
    }
    Selection select(AssetExportJobState.Command command);
}
