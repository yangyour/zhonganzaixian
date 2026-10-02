package cn.mop.domain.worklog;

import cn.mop.domain.DomainException;
import java.time.*;

/** Explicit manual facts only. No default hours, billing rounding, calendar or cost rule is selected. */
public record ManualWorklog(Kind kind,Instant startedAt,Instant endedAt,String note) {
    public enum Kind { REMOTE, ONSITE, INSPECTION, COMMUTE }
    public static final int MAX_NOTE_CODE_POINTS=2000;
    private static final Instant MIN=Instant.parse("0001-01-01T00:00:00Z"),MAX=Instant.parse("9999-12-31T23:59:59.999Z");
    public ManualWorklog {
        if(kind==null||startedAt==null||endedAt==null||endedAt.isBefore(startedAt))throw invalid();
        for(Instant instant:new Instant[]{startedAt,endedAt})if(instant.isBefore(MIN)||instant.isAfter(MAX)||instant.getNano()%1_000_000!=0)throw invalid();
        if(note!=null&&(note.isEmpty()||note.codePointCount(0,note.length())>MAX_NOTE_CODE_POINTS||note.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||note.codePoints().anyMatch(cp->(Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t')||Character.getType(cp)==Character.SURROGATE)))throw invalid();
    }
    /** Elapsed physical time, not billable/rounded time; explicit equal endpoints yield an actual zero. */
    public long elapsedMillis(){return Duration.between(startedAt,endedAt).toMillis();}
    public static Instant parseInstant(String value){
        if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?(?:Z|[+-][0-9]{2}:[0-9]{2})"))throw invalid();
        try{Instant instant=OffsetDateTime.parse(value).toInstant();if(instant.isBefore(MIN)||instant.isAfter(MAX))throw invalid();return instant;}
        catch(DateTimeException failure){throw invalid();}
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","实填工时必须有明确类型、带时区的毫秒精度起止时间且结束不早于开始；备注不符合技术文本边界");}
}
