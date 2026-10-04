package cn.mop.domain.inspection;
import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.*;
/** Explicit immutable source drafts only. No approved quarter, reporting metric or publication. */
public interface InspectionSourceCollectionRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId){}
    record Source(String worksheetId,long version,String expectedSourceSha256){
        public Source{var selected=new InspectionWorksheetRepository.Selection(worksheetId,version);worksheetId=selected.worksheetId();if(expectedSourceSha256==null||!expectedSourceSha256.matches("[0-9a-f]{64}"))throw invalid();}
    }
    record Creation(String label,List<Source> sources){
        public Creation{InspectionTemplate.text(label,200,false,false);if(sources==null)throw invalid();if(sources.size()>InspectionSourceManifest.MAX_SOURCES)throw new DomainException(413,"INSPECTION_SOURCE_TECHNICAL_LIMIT","来源集合超过1000项技术保护");var values=new ArrayList<>(sources);if(values.size()>InspectionSourceManifest.MAX_SOURCES)throw invalid();var keys=new HashSet<String>();for(var s:values)if(s==null||!keys.add(s.worksheetId()+"/"+s.version()))throw invalid();sources=List.copyOf(values);}
    }
    /** List projection deliberately does not claim that the document body was read and verified. */
    record Summary(String id,String tenantId,String createdBy,Instant recordedAt,long version,String label,int sourceCount,String documentSha256,String sourceManifestSha256){}
    record Stored(Summary summary,InspectionSourceManifest manifest){}
    void authorizeWrite(Access access);
    Summary create(Access access,long expectedVersion,Creation input);
    Stored get(Access access,String id);
    List<Summary> list(Access access,int limit,Instant beforeRecordedAt,String beforeId);
    List<String> readinessProblems();
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","来源集合标签、明确修订或预期摘要无效；不会自动改选最新版本");}
}
