package cn.mop.domain.manualincident;

import cn.mop.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;

/** Human-authored recovery assertion only. Never a monitoring sample or a formal event/ticket transition. */
public record ManualRecoveryConfirmation(String note,Instant reportedRecoveredAt) {
    public static final int MAX_NOTE_CODE_POINTS=2000;
    private static final Instant MIN=Instant.parse("0001-01-01T00:00:00Z"),MAX=Instant.parse("9999-12-31T23:59:59.999Z");
    public ManualRecoveryConfirmation {
        if(note==null||note.isEmpty()||note.codePointCount(0,note.length())>MAX_NOTE_CODE_POINTS||note.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||note.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t'))throw invalid();
        if(reportedRecoveredAt!=null)validTime(reportedRecoveredAt);
    }
    /** Commit must supply server time; unknown actual recovery time remains null, not this audit timestamp. */
    public void validateRecordedAt(Instant recordedAt){if(recordedAt==null)throw invalid();validTime(recordedAt);if(reportedRecoveredAt!=null&&reportedRecoveredAt.isAfter(recordedAt))throw invalid();}
    private static void validTime(Instant value){if(value.getNano()%1000000!=0||value.isBefore(MIN)||value.isAfter(MAX))throw invalid();}
    public String noteSha256(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(note.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable");}}
    public String manualRecoveryStatus(){return "HUMAN_CONFIRMED";}
    public String verificationStatus(){return "SELF_REPORTED";}
    public String recordSource(){return "MANUAL_RECOVERY_CONFIRMATION";}
    public String monitorState(){return null;}
    public String dispositionState(){return null;}
    public boolean monitoringRecoveryApplicable(){return false;}
    public boolean ticketStateChanged(){return false;}
    public boolean slaChanged(){return false;}
    /** Version zero is technical absence of a confirmation, never an inferred business state. */
    public static void requireConfirmable(long expectedVersion,long currentVersion){
        if(expectedVersion<0||expectedVersion>1)throw invalid();
        if(currentVersion<0||currentVersion>1)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","人工恢复确认版本不一致，未确认结果");
        if(expectedVersion!=currentVersion)throw new DomainException(409,"VERSION_CONFLICT","人工恢复确认已变化，请刷新核对");
        if(currentVersion==1)throw new DomainException(422,"MANUAL_RECOVERY_ALREADY_CONFIRMED","已有不可变人工恢复确认，不能重复确认或覆盖原证据");
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","人工恢复须明确非空白说明；恢复发生时间可保持未知，若填写须为有效毫秒时间且不晚于确认时间");}
}
