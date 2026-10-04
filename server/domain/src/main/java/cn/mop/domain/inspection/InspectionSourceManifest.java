package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.io.*;
import java.util.*;

/** Immutable explicit source collection, not an approved quarter, report metric or published report. */
public record InspectionSourceManifest(UUID tenantId,List<InspectionSourceSnapshot> sources) {
    public static final String FORMAT="MOP_INSPECTION_SOURCE_MANIFEST_V1";
    public static final int MAX_SOURCES=1000,MAX_CAPTURED_BYTES=16*1024*1024;
    public InspectionSourceManifest {
        if(tenantId==null||sources==null)throw invalid();
        if(sources.size()>MAX_SOURCES)throw tooLarge();
        var ordered=new ArrayList<>(sources);if(ordered.size()>MAX_SOURCES)throw tooLarge();if(ordered.stream().anyMatch(Objects::isNull))throw invalid();ordered.sort(Comparator.comparing((InspectionSourceSnapshot s)->s.worksheetId().toString()).thenComparingLong(InspectionSourceSnapshot::version));
        String previous=null;long total=0;
        for(var source:ordered){
            if(!tenantId.equals(source.tenantId()))throw new DomainException(404,"OBJECT_NOT_FOUND","巡检来源不属于当前租户");
            String key=source.worksheetId()+"/"+source.version();if(key.equals(previous))throw invalid();previous=key;
            total+=source.canonicalBytes().length;if(total>MAX_CAPTURED_BYTES)throw tooLarge();
        }
        sources=List.copyOf(ordered);
    }
    /** Technical selection count, not tenant population, completed inspections, or report metrics. */
    public int selectedSourceCount(){return sources.size();}
    public int distinctSelectedAssets(){return (int)sources.stream().map(s->s.content().worksheet().assetId()).distinct().count();}
    public List<InspectionSourceSnapshot.Reference> sourcesWithUnknownInspectionTime(){return sources.stream().filter(s->!s.inspectionTimeKnown()).map(InspectionSourceSnapshot::reference).toList();}
    public List<InspectionSourceSnapshot.Reference> incompletelyEnteredSources(){return sources.stream().filter(s->!s.allResultsEntered()).map(InspectionSourceSnapshot::reference).toList();}
    public List<InspectionSourceSnapshot.Reference> archivedSelectedRevisions(){return sources.stream().filter(InspectionSourceSnapshot::revisionArchived).map(InspectionSourceSnapshot::reference).toList();}
    /** Multiple candidates are preserved and surfaced; never silently pick newest, worst, or an archived/active one. */
    public Map<UUID,List<InspectionSourceSnapshot.Reference>> assetsWithMultipleSources(){
        var grouped=new TreeMap<UUID,List<InspectionSourceSnapshot.Reference>>(Comparator.comparing(UUID::toString));
        for(var source:sources)grouped.computeIfAbsent(source.content().worksheet().assetId(),ignored->new ArrayList<>()).add(source.reference());
        var result=new LinkedHashMap<UUID,List<InspectionSourceSnapshot.Reference>>();grouped.forEach((id,refs)->{if(refs.size()>1)result.put(id,List.copyOf(refs));});return Collections.unmodifiableMap(result);
    }
    public String periodTimeZone(){return null;}
    public String quarterSelectionRule(){return null;}
    public String archivedSourcePolicy(){return null;}
    public String metricVersion(){return null;}
    public String reportTemplateVersion(){return null;}
    public Integer tenantAssetPopulation(){return null;}
    public String generationStatus(){return "CONFIG_PENDING";}
    public boolean formalReportGeneratable(){return false;}
    public boolean reportPublished(){return false;}
    public byte[] canonicalBytes(){
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
            InspectionSourceSnapshot.field(out,FORMAT);InspectionSourceSnapshot.field(out,tenantId.toString());out.writeInt(sources.size());
            for(var source:sources){InspectionSourceSnapshot.field(out,source.worksheetId().toString());out.writeLong(source.version());InspectionSourceSnapshot.field(out,source.fingerprint());}
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException("Inspection source manifest encoding unavailable",impossible);}
    }
    public String fingerprint(){return InspectionSourceSnapshot.sha256(canonicalBytes());}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","来源清单无效或同一执行单修订重复；不会自动合并或选取最新版本");}
    private static DomainException tooLarge(){return new DomainException(413,"INSPECTION_SOURCE_TECHNICAL_LIMIT","来源清单超过1000项或16MiB技术保护；未截断或生成正式报告");}
}
