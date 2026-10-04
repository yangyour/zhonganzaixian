package cn.mop.domain.worklog;
import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.UUID;
/** Explicit filters on current self-entered revisions. No implicit period, clipping, totals or team scope. */
public record OwnWorklogQuery(UUID ticketId,ManualWorklog.Kind kind,Instant startedFrom,Instant startedBefore) {
    public OwnWorklogQuery {
        if((startedFrom==null)!=(startedBefore==null))throw invalid();
        if(startedFrom!=null){requireProtocolInstant(startedFrom);requireProtocolInstant(startedBefore);if(!startedBefore.isAfter(startedFrom))throw invalid();}
    }
    public static void requireProtocolInstant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","本人工时查询起始范围须成对明确、毫秒精度且前闭后开；不默认月份或裁剪记录时长");}
}
