package cn.mop.domain.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only current-tenant audit access. No platform namespace or write/edit/delete port. */
public interface AuditReader {
    record Access(String actorId,String tenantId,long permissionVersion) {}
    record Filters(String actorId,String action,String result,String resourceType,String resourceId,String traceId,Instant occurredFrom,Instant occurredBefore) {}
    record Page(List<Map<String,Object>> items,long snapshotSequence,Long nextBeforeSequence,boolean hasMore) {}
    record Integrity(String status,Boolean valid,long snapshotSequence,Long fromSequence,Long toSequence,int verifiedRecords,Long failureSequence,String failure,String anchorHash,String finalHash,boolean hasMore,Long nextFromSequence,Instant checkedAt) {}
    Page list(Access access,Filters filters,int size,Long snapshotSequence,Long beforeSequence);
    Integrity verify(Access access,long fromSequence,Long toSequence);
}
