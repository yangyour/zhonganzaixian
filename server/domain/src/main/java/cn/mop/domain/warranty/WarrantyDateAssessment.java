package cn.mop.domain.warranty;

import cn.mop.domain.DomainException;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** M3-10 date arithmetic only. Dates do not prove coverage, provider, hazard creation or notification delivery. */
public final class WarrantyDateAssessment {
    private WarrantyDateAssessment(){}
    public static final ZoneId DISPLAY_ZONE=ZoneId.of("Asia/Shanghai");
    public enum Position { UNKNOWN,PAST_DATE,DUE_TODAY,WITHIN_30_DAYS,FUTURE_DATE }
    public record DateResult(LocalDate recordedDate,Long daysUntil,Position position,Boolean within7Days,Boolean within30Days,Boolean within90Days,Boolean inReferenceQuarter) {}
    public record Assessment(LocalDate asOfDate,DateResult warrantyExpiry,DateResult overdueServiceDate) {
        public String scope(){return "RECORDED_DATE_ASSESSMENT_ONLY";}
        public String policyVersion(){return "contract-m3-10-date-v1";}
        public String timezone(){return DISPLAY_ZONE.getId();}
        public boolean coverageVerified(){return false;}
        public String hazardCreationStatus(){return "NOT_REQUESTED";}
        public String notificationStatus(){return "NOT_REQUESTED";}
    }
    /** Explicit server instant converted once; caller must not substitute a browser clock as server authority. */
    public static LocalDate referenceDate(Instant observedAt){if(observedAt==null)throw invalid();try{LocalDate result=observedAt.atZone(DISPLAY_ZONE).toLocalDate();valid(result);return result;}catch(DateTimeException failure){throw invalid();}}
    public static Assessment assess(LocalDate asOfDate,LocalDate warrantyExpiry,LocalDate overdueServiceDate){valid(asOfDate);return new Assessment(asOfDate,date(asOfDate,warrantyExpiry),date(asOfDate,overdueServiceDate));}
    private static DateResult date(LocalDate reference,LocalDate value){
        if(value==null)return new DateResult(null,null,Position.UNKNOWN,null,null,null,null);
        valid(value);long days=ChronoUnit.DAYS.between(reference,value);
        Position position=days<0?Position.PAST_DATE:days==0?Position.DUE_TODAY:days<=30?Position.WITHIN_30_DAYS:Position.FUTURE_DATE;
        return new DateResult(value,days,position,days>=0&&days<=7,days>=0&&days<=30,days>=0&&days<=90,value.getYear()==reference.getYear()&&(value.getMonthValue()-1)/3==(reference.getMonthValue()-1)/3);
    }
    private static void valid(LocalDate value){if(value==null||value.getYear()<1||value.getYear()>9999)throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","日期对照需要明确参考日；仅接受0001至9999年，缺失到期日保持未知，不代替维保核验或通知送达");}
}
