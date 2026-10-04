package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Frozen selected worksheet revision. This value type proves neither current authorization nor a quarterly selection rule. */
public record InspectionSourceSnapshot(UUID tenantId,UUID worksheetId,long version,UUID createdBy,Instant createdAt,
                                       UUID recordedBy,Instant recordedAt,String action,boolean revisionArchived,
                                       InspectionWorksheetContent content) {
    public static final String FORMAT="MOP_INSPECTION_SOURCE_SNAPSHOT_V1";
    public InspectionSourceSnapshot {
        if(tenantId==null||worksheetId==null||createdBy==null||recordedBy==null||content==null||version<1||version>9_007_199_254_740_990L||action==null||!Set.of("CREATE","UPDATE","ARCHIVE","RESTORE").contains(action)||revisionArchived!=action.equals("ARCHIVE")||(version==1?!action.equals("CREATE")||!recordedBy.equals(createdBy):action.equals("CREATE")))throw invalid();
        bounded(createdAt);bounded(recordedAt);if(recordedAt.isBefore(createdAt))throw invalid();
        if(!tenantId.equals(content.worksheet().tenantId()))throw new DomainException(404,"OBJECT_NOT_FOUND","巡检来源不属于当前租户");
        content.requireNotFuture(recordedAt);
    }
    /** Drops mutable current-head updated_at. The selected revision's immutable recording facts remain bound. */
    public static InspectionSourceSnapshot fromEntry(InspectionWorksheetRepository.Entry entry){
        if(entry==null)throw invalid();bounded(entry.updatedAt());if(entry.recordedAt()==null||entry.updatedAt().isBefore(entry.recordedAt()))throw invalid();
        return new InspectionSourceSnapshot(uuid(entry.tenantId()),uuid(entry.id()),entry.version(),uuid(entry.createdBy()),entry.createdAt(),uuid(entry.recordedBy()),entry.recordedAt(),entry.action(),entry.archived(),entry.content());
    }
    public record Reference(UUID worksheetId,long version,String snapshotSha256,String contentSha256) {}
    public Reference reference(){return new Reference(worksheetId,version,fingerprint(),content.fingerprint());}
    public String sourceType(){return "IMMUTABLE_INSPECTION_WORKSHEET_REVISION";}
    public String verificationStatus(){return "SELF_REPORTED_UNVERIFIED";}
    public boolean evidenceAccessGranted(){return false;}
    public boolean inspectionTimeKnown(){return content.reportedPerformedAt()!=null;}
    public boolean allResultsEntered(){return content.worksheet().summary().allResultsEntered();}
    public byte[] canonicalBytes(){
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
            field(out,FORMAT);field(out,tenantId.toString());field(out,worksheetId.toString());out.writeLong(version);field(out,createdBy.toString());out.writeLong(createdAt.toEpochMilli());field(out,recordedBy.toString());out.writeLong(recordedAt.toEpochMilli());field(out,action);out.writeBoolean(revisionArchived);
            byte[] template=content.worksheet().template().definition().canonicalBytes();out.writeInt(template.length);out.write(template);
            byte[] payload=content.canonicalBytes();out.writeInt(payload.length);out.write(payload);out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException("Inspection source encoding unavailable",impossible);}
    }
    public String fingerprint(){return sha256(canonicalBytes());}
    static void field(DataOutputStream out,String value)throws IOException{if(value==null){out.writeInt(-1);return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Inspection source digest unavailable",impossible);}}
    private static UUID uuid(String value){if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();return UUID.fromString(value);}
    private static void bounded(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","巡检来源修订、记录身份或时间字段无效");}
}
