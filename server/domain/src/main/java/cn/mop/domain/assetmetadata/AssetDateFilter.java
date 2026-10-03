package cn.mop.domain.assetmetadata;
import cn.mop.domain.DomainException;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
/** Explicit date-window selection; caller supplies a server-derived reference day. Never a coverage or delivery fact. */
public record AssetDateFilter(Field field,Window window,LocalDate referenceDate) {
    public enum Field { WARRANTY_EXPIRY("warranty_expiry"),OVERDUE_SERVICE_DATE("overdue_service_date");private final String key;Field(String key){this.key=key;}public String key(){return key;} }
    public enum Window { UNKNOWN,PAST_DATE,WITHIN_30_DAYS,WITHIN_90_DAYS,REFERENCE_QUARTER }
    private static final LocalDate MAX=LocalDate.of(9999,12,31);
    public AssetDateFilter {if(field==null||window==null||referenceDate==null||referenceDate.getYear()<1||referenceDate.getYear()>9999)throw invalid();}
    public LocalDate lowerInclusive(){return switch(window){case WITHIN_30_DAYS,WITHIN_90_DAYS->referenceDate;case REFERENCE_QUARTER->LocalDate.of(referenceDate.getYear(),((referenceDate.getMonthValue()-1)/3)*3+1,1);default->null;};}
    public LocalDate upperInclusive(){return switch(window){case WITHIN_30_DAYS->boundedPlus(30);case WITHIN_90_DAYS->boundedPlus(90);case REFERENCE_QUARTER->lowerInclusive().plusMonths(2).with(TemporalAdjusters.lastDayOfMonth());default->null;};}
    private LocalDate boundedPlus(int days){LocalDate value=referenceDate.plusDays(days);return value.isAfter(MAX)?MAX:value;}
    public boolean matches(LocalDate value){if(value!=null&&(value.getYear()<1||value.getYear()>9999))throw invalid();return switch(window){case UNKNOWN->value==null;case PAST_DATE->value!=null&&value.isBefore(referenceDate);default->value!=null&&!value.isBefore(lowerInclusive())&&!value.isAfter(upperInclusive());};}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","日期筛选仅接受明确日期字段、窗口及0001至9999年的参考日，不推断维保覆盖");}
}
