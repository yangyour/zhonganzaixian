package cn.mop.api;
import cn.mop.domain.sla.SlaNextDayRule;
import cn.mop.domain.time.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SlaNextDayRuleTest {
    private static final Instant OPEN=Instant.parse("2026-10-20T16:30:00Z");
    private static SlaNextDayRule.Rule rule(SlaNextDayRule.Kind kind){return new SlaNextDayRule.Rule(kind,LocalTime.of(18,0),"published-test-rule");}
    @Test void nextCalendarDayUsesShanghaiDateNotUtcDate(){assertEquals(Instant.parse("2026-10-22T10:00:00Z"),SlaNextDayRule.resolve(OPEN,rule(SlaNextDayRule.Kind.CALENDAR_DAY),null).deadline());}
    @Test void missingRuleNeverGetsDefault(){assertNull(SlaNextDayRule.resolve(OPEN,null,null).deadline());}
    @Test void workingDayNeedsExplicitCompleteCalendar(){assertEquals("CALENDAR_MISSING_OR_INCOMPLETE",SlaNextDayRule.resolve(OPEN,rule(SlaNextDayRule.Kind.WORKING_DAY),null).missing());}
    @Test void workingDaySkipsKnownEmptyDates(){
        var c=new WorkingTime.CalendarData("calendar",1,new IntervalSets.Interval(Instant.parse("2026-10-20T16:00:00Z"),Instant.parse("2026-10-25T16:00:00Z")),List.of(new IntervalSets.Interval(Instant.parse("2026-10-24T01:00:00Z"),Instant.parse("2026-10-24T09:00:00Z"))),true);
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"),SlaNextDayRule.resolve(OPEN,rule(SlaNextDayRule.Kind.WORKING_DAY),c).deadline());
    }
    @Test void incompleteDateCoverageCannotBeAssumedHoliday(){
        var c=new WorkingTime.CalendarData("calendar",1,new IntervalSets.Interval(Instant.parse("2026-10-22T01:00:00Z"),Instant.parse("2026-10-23T16:00:00Z")),List.of(),true);
        assertEquals("CALENDAR_COVERAGE_INCOMPLETE",SlaNextDayRule.resolve(OPEN,rule(SlaNextDayRule.Kind.WORKING_DAY),c).missing());
    }
    @Test void ruleRejectsUndocumentedSeconds(){assertThrows(IllegalArgumentException.class,()->new SlaNextDayRule.Rule(SlaNextDayRule.Kind.CALENDAR_DAY,LocalTime.of(18,0,1),"test"));}
}
