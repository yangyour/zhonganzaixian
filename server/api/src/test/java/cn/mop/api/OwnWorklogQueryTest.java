package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.worklog.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OwnWorklogQueryTest {
    final Instant start=Instant.parse("2026-10-01T00:00:00Z"),end=Instant.parse("2026-11-01T00:00:00Z");
    @Test void unfilteredQueryPreservesExplicitAbsenceWithoutChoosingMonth(){var q=new OwnWorklogQuery(null,null,null,null);assertNull(q.startedFrom());assertNull(q.startedBefore());assertNull(q.kind());assertNull(q.ticketId());}
    @Test void pairedStrictIntervalAndProtocolPrecisionAreRequired(){for(Instant bad:new Instant[]{start,start.minusMillis(1)})assertThrows(DomainException.class,()->new OwnWorklogQuery(null,null,start,bad));assertThrows(DomainException.class,()->new OwnWorklogQuery(null,null,start,null));assertThrows(DomainException.class,()->new OwnWorklogQuery(null,null,null,end));assertThrows(DomainException.class,()->new OwnWorklogQuery(null,null,start.plusNanos(1),end));}
    @Test void knownKindTicketAndWideRangeArePreservedWithoutBusinessQuota(){UUID id=UUID.randomUUID();var q=new OwnWorklogQuery(id,ManualWorklog.Kind.COMMUTE,Instant.parse("0001-01-01T00:00:00Z"),Instant.parse("9999-12-31T23:59:59.999Z"));assertEquals(id,q.ticketId());assertEquals(ManualWorklog.Kind.COMMUTE,q.kind());}
    @Test void outOfProtocolDatesAndMissingCursorTimeAreRejected(){for(Instant bad:new Instant[]{null,Instant.parse("0000-12-31T23:59:59Z"),Instant.parse("+10000-01-01T00:00:00Z"),start.plusNanos(1)})assertThrows(DomainException.class,()->OwnWorklogQuery.requireProtocolInstant(bad));OwnWorklogQuery.requireProtocolInstant(start);}
}
