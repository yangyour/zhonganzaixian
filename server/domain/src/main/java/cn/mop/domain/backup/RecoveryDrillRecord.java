package cn.mop.domain.backup;
import cn.mop.domain.DomainException;
import cn.mop.domain.time.BusinessDates;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
/** Human-reported drill facts only. No device execution, caller authentication, object verification or file grant. */
public record RecoveryDrillRecord(UUID tenantId,UUID assetId,BackupRunState.StoredVersion backupVersion,UUID executorId,
                                  Instant reportedPerformedAt,Conclusion reportedConclusion,String instructions,String note,List<UUID> attachmentIds) {
    public static final String FORMAT="MOP_MANUAL_RECOVERY_DRILL_RECORD_V1";
    public static final int MAX_TEXT_CODE_POINTS=4000,MAX_ATTACHMENTS=10;
    public enum Conclusion { SUCCESS,FAILED,PARTIAL_SUCCESS }
    public RecoveryDrillRecord {
        if(tenantId==null||assetId==null||backupVersion==null||executorId==null||reportedConclusion==null)throw invalid();
        if(!tenantId.equals(backupVersion.tenantId())||!assetId.equals(backupVersion.assetId()))throw new DomainException(404,"OBJECT_NOT_FOUND","备份版本不存在或不可见");
        instant(reportedPerformedAt);if(backupVersion.persistedAt().isAfter(reportedPerformedAt))throw invalid();text(instructions);text(note);
        if(attachmentIds==null)throw invalid();if(attachmentIds.size()>MAX_ATTACHMENTS)throw new DomainException(413,"RECOVERY_DRILL_REFERENCE_LIMIT","演练附件引用超过10项技术保护，未截断保存");
        var refs=new ArrayList<>(attachmentIds);if(refs.size()>MAX_ATTACHMENTS||refs.stream().anyMatch(Objects::isNull)||new HashSet<>(refs).size()!=refs.size())throw invalid();attachmentIds=List.copyOf(refs);
    }
    /** Future persistence must supply an independently observed server time, not a browser assertion. */
    public void requireNotFuture(Instant recordedAt){instant(recordedAt);if(reportedPerformedAt.isAfter(recordedAt))throw invalid();}
    public String recordSource(){return "MANUAL_RECOVERY_DRILL_RECORD";}
    public String verificationStatus(){return "SELF_REPORTED_UNVERIFIED";}
    public String backupReferenceVerification(){return "NOT_VERIFIED_BY_THIS_MODEL";}
    public String attachmentBindingStatus(){return "NOT_VERIFIED_BY_THIS_MODEL";}
    public boolean evidenceAccessGranted(){return false;}
    public boolean automaticRestoreAllowed(){return false;}
    public String actualDeviceRecoveryState(){return null;}
    /** Stable credential-free encoding of supplied references/facts, not a signature or authentication proof. */
    public byte[] canonicalBytes(){
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
            field(out,FORMAT);field(out,tenantId.toString());field(out,assetId.toString());field(out,backupVersion.versionId().toString());field(out,backupVersion.encryptedObjectSha256());out.writeLong(backupVersion.encryptedBytes());out.writeLong(backupVersion.persistedAt().toEpochMilli());field(out,executorId.toString());out.writeLong(reportedPerformedAt.toEpochMilli());field(out,reportedConclusion.name());field(out,instructions);field(out,note);out.writeInt(attachmentIds.size());for(var id:attachmentIds)field(out,id.toString());out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException("Recovery drill encoding unavailable",impossible);}
    }
    public String fingerprint(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    private static void field(DataOutputStream out,String value)throws IOException{if(value==null){out.writeInt(-1);return;}byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);}
    private static void text(String value){if(value!=null&&(value.codePointCount(0,value.length())>MAX_TEXT_CODE_POINTS||value.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t')))throw invalid();}
    static void instant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();BusinessDates.localDate(value);}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","演练自述时间、结论、引用或文字无效；不会推断真实恢复成功或执行恢复命令");}
}
