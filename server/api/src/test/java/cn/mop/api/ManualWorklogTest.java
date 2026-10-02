package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.worklog.ManualWorklog;
import cn.mop.domain.worklog.ManualWorklog.Kind;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure manual time facts; does not prove persistence, a work actually performed, or cost accuracy. */
class ManualWorklogTest {
    private static final Instant START=Instant.parse("2026-10-02T00:00:00Z");
    @Test void allFourContractKindsKeepExplicitIntervalsAndDoNotApplyADemoTwentyFourHourCap(){
        for(Kind kind:Kind.values()){var entry=new ManualWorklog(kind,START,START.plus(Duration.ofHours(49)),null);assertEquals(49*3600000L,entry.elapsedMillis());assertNull(entry.note());}
        assertEquals(4,Kind.values().length);
    }
    @Test void explicitZeroIsDifferentFromMissingEndpointsAndNegativeDurationIsRejected(){
        assertEquals(0,new ManualWorklog(Kind.REMOTE,START,START,null).elapsedMillis());
        assertThrows(DomainException.class,()->new ManualWorklog(Kind.REMOTE,null,START,null));assertThrows(DomainException.class,()->new ManualWorklog(Kind.REMOTE,START,null,null));
        assertThrows(DomainException.class,()->new ManualWorklog(null,START,START,null));assertThrows(DomainException.class,()->new ManualWorklog(Kind.REMOTE,START,START.minusMillis(1),null));
    }
    @Test void physicalElapsedTimeHandlesOffsetsDstMidnightAndMonthBoundariesWithoutRounding(){
        assertEquals(3600000,new ManualWorklog(Kind.ONSITE,ManualWorklog.parseInstant("2026-03-08T01:30:00-05:00"),ManualWorklog.parseInstant("2026-03-08T03:30:00-04:00"),null).elapsedMillis());
        assertEquals(7200000,new ManualWorklog(Kind.COMMUTE,ManualWorklog.parseInstant("2026-11-01T00:30:00-04:00"),ManualWorklog.parseInstant("2026-11-01T01:30:00-05:00"),null).elapsedMillis());
        assertEquals(2,new ManualWorklog(Kind.INSPECTION,ManualWorklog.parseInstant("2026-09-30T23:59:59.999+08:00"),ManualWorklog.parseInstant("2026-10-01T00:00:00.001+08:00"),null).elapsedMillis());
    }
    @Test void absentOffsetsInvalidDatesAndHiddenPrecisionLossAreRejected(){
        for(String value:Arrays.asList(null,"","2026-10-02T08:00:00","2026-02-30T00:00:00Z","2026-10-02T00:00:00.0001Z","2026-10-02T00:00Z","0000-01-01T00:00:00Z","2026-10-02T00:00:00+99:00"))assertThrows(DomainException.class,()->ManualWorklog.parseInstant(value));
        assertThrows(DomainException.class,()->new ManualWorklog(Kind.REMOTE,START,START.plusNanos(1),null));
    }
    @Test void technicalTimestampRangeStaysExactlyRepresentableWithoutBusinessDateDefaults(){
        var entry=new ManualWorklog(Kind.REMOTE,ManualWorklog.parseInstant("0001-01-01T00:00:00Z"),ManualWorklog.parseInstant("9999-12-31T23:59:59.999Z"),null);
        assertTrue(entry.elapsedMillis()>0);assertTrue(entry.elapsedMillis()<9007199254740991L);
        assertThrows(DomainException.class,()->new ManualWorklog(Kind.REMOTE,Instant.MIN,START,null));
    }
    @Test void notesKeepOriginalTextWithExplicitNullAndRejectControlOrMalformedUnicode(){
        String note="  虚构实填说明\n第二行\t原文  ";assertEquals(note,new ManualWorklog(Kind.REMOTE,START,START,note).note());
        assertDoesNotThrow(()->new ManualWorklog(Kind.REMOTE,START,START,"😀".repeat(2000)));
        for(String value:List.of("","\u00a0\ufeff","bad\u0000","\uD800","x".repeat(2001)))assertThrows(DomainException.class,()->new ManualWorklog(Kind.REMOTE,START,START,value));
    }
}
