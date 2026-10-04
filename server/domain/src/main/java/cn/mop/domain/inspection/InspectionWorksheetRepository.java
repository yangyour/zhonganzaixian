package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Internal manual worksheet drafts. No inspection attestation, automatic scan or report publication. */
public interface InspectionWorksheetRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    enum View { CURRENT, ARCHIVED, ALL }
    record Content(Map<String,InspectionWorksheet.Observation> observations,Instant reportedPerformedAt) {
        public Content {
            if(observations==null||observations.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||!e.getValue().evidenceIds().isEmpty()))
                throw new DomainException(400,"VALIDATION_ERROR","执行单答案无效；证据文件绑定尚未实现");
            observations=Map.copyOf(observations);
        }
    }
    record Creation(String assetId,String templateId,long templateVersion,Content content) {}
    record Entry(String id,String tenantId,String createdBy,long version,Instant createdAt,Instant updatedAt,
                 String recordedBy,Instant recordedAt,String action,boolean archived,InspectionWorksheetContent content) {}
    record Revision(long version,Long previousVersion,String recordedBy,Instant recordedAt,String action,
                    boolean archived,InspectionWorksheetContent content) {}
    /** Must run before an original protected-command receipt is replayed. Source freshness is checked only for new execution. */
    void authorizeWrite(Access access,String id);
    Entry create(Access access,long expectedVersion,Creation creation);
    Entry update(Access access,String id,long expectedVersion,Content content);
    Entry archive(Access access,String id,long expectedVersion);
    Entry restore(Access access,String id,long expectedVersion,Content explicitContent);
    Entry get(Access access,String id);
    List<Entry> list(Access access,String assetId,View view,int limit,Instant beforeCreatedAt,String beforeId);
    List<Revision> history(Access access,String id,int limit,Long beforeVersion);
    List<String> readinessProblems();
}
