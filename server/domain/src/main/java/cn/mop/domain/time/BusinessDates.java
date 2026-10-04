package cn.mop.domain.time;
import cn.mop.domain.DomainException;
import java.time.*;
/** Contract-fixed business.timezone, not the JVM/browser zone or a default working calendar. */
public final class BusinessDates {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private BusinessDates(){}
    /** An explicit observation instant is required; the caller owns its authority. */
    public static LocalDate localDate(Instant observedAt){
        if(observedAt==null)throw invalid();
        try{LocalDate date=observedAt.atZone(ZONE).toLocalDate();if(date.getYear()<1||date.getYear()>9999)throw invalid();return date;}
        catch(DateTimeException|ArithmeticException failure){throw invalid();}
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","业务日期需要明确参考时刻，且上海业务日期须处于0001至9999年");}
}
