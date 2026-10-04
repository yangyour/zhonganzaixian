package cn.mop.domain.backup;
import cn.mop.domain.DomainException;
import cn.mop.domain.time.BusinessDates;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
/** Explicit selected self-report date comparison. Selection is not proof of the latest stored or actual drill. */
public final class RecoveryDrillAgeAssessment {
    public static final int WARNING_DAYS=90; // M6-5: strictly greater than90, not greater-than-or-equal.
    public enum Position { UNKNOWN_SOURCE,RECORDED_WITHIN_90_DAYS,RECORDED_OVER_90_DAYS }
    public record Assessment(UUID tenantId,UUID assetId,Instant observedAt,LocalDate referenceDate,String selectedRecordSha256,
                             Instant reportedPerformedAt,LocalDate reportedBusinessDate,Long daysSinceSelectedReportedDrill,Boolean exceeds90Days,Position position) {
        public String timezone(){return BusinessDates.ZONE.getId();}
        public String scope(){return "SELECTED_SELF_REPORTED_DRILL_DATE_ONLY";}
        public String policyVersion(){return "contract-m6-5-recorded-date-v1";}
        public Boolean selectedRecordIsLatestStoredDrill(){return null;}
        public boolean actualDrillVerified(){return false;}
        public boolean automaticRestoreAllowed(){return false;}
        public String notificationActionThisAssessment(){return "NOT_REQUESTED";}
    }
    private RecoveryDrillAgeAssessment(){}
    /** Missing supplied record is unknown, not zero days, never-drilled or overdue. */
    public static Assessment assessSelected(UUID tenantId,UUID assetId,RecoveryDrillRecord record,Instant observedAt){
        if(tenantId==null||assetId==null)throw new DomainException(400,"VALIDATION_ERROR","演练日期对照范围无效");
        if(record!=null&&(!tenantId.equals(record.tenantId())||!assetId.equals(record.assetId())))throw new DomainException(404,"OBJECT_NOT_FOUND","演练记录不存在或不可见");
        RecoveryDrillRecord.instant(observedAt);LocalDate reference=BusinessDates.localDate(observedAt);
        if(record==null)return new Assessment(tenantId,assetId,observedAt,reference,null,null,null,null,null,Position.UNKNOWN_SOURCE);
        record.requireNotFuture(observedAt);LocalDate reported=BusinessDates.localDate(record.reportedPerformedAt());long days=ChronoUnit.DAYS.between(reported,reference);boolean over=days>WARNING_DAYS;
        return new Assessment(tenantId,assetId,observedAt,reference,record.fingerprint(),record.reportedPerformedAt(),reported,days,over,over?Position.RECORDED_OVER_90_DAYS:Position.RECORDED_WITHIN_90_DAYS);
    }
}
