package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only DRAFT snapshots. Configuration is explicit unapproved request content. */
public interface InspectionQuarterlyReportRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Creation(String label,String sourceCollectionId,String expectedSourceDocumentSha256,
                    String expectedSourceManifestSha256,InspectionQuarterlyReport.Period period,
                    InspectionQuarterlyReport.Configuration configuration) {
        public Creation {
            InspectionTemplate.text(label,200,false,false);
            if(sourceCollectionId==null||!sourceCollectionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();
            UUID.fromString(sourceCollectionId);
            if(!digest(expectedSourceDocumentSha256)||!digest(expectedSourceManifestSha256)||period==null||configuration==null)throw invalid();
        }
    }
    record Summary(String id,String tenantId,String createdBy,Instant recordedAt,long version,String label,
                   String sourceCollectionId,String sourceDocumentSha256,String sourceManifestSha256,
                   int year,int quarter,String reportSha256,String documentSha256) {}
    /** The frozen JSON contains both complete input and output; no live source lookup is needed. */
    record Stored(Summary summary,String frozenDocument) {}

    /** Explicit immutable worksheet revisions; the source collection is created in the report transaction. */
    record CaptureCreation(String label, List<InspectionSourceCollectionRepository.Source> sources,
                           InspectionQuarterlyReport.Period period,
                           InspectionQuarterlyReport.Configuration configuration) {
        public CaptureCreation {
            sources = new InspectionSourceCollectionRepository.Creation(label, sources).sources();
            if (period == null || configuration == null) {
                throw invalid();
            }
        }
    }

    /** Recheck every capture permission before both execution and idempotency receipt replay. */
    void authorizeCapture(Access access);

    /** Append the source collection, draft report and audits on the caller's protected command connection. */
    Summary capture(Access access, long expectedVersion, CaptureCreation input);

    void authorizeWrite(Access access);
    Summary create(Access access,long expectedVersion,Creation input);
    Stored get(Access access,String id);
    List<Summary> list(Access access,int limit,Instant beforeRecordedAt,String beforeId);
    List<String> readinessProblems();
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","明确来源集合、摘要、季度或配置无效");}
}
