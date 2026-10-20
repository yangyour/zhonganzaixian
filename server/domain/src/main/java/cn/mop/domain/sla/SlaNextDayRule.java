package cn.mop.domain.sla;

import cn.mop.domain.time.WorkingTime;
import java.time.*;

/** Explicit Q08 date rule. No default day kind, cutoff, holiday or calendar. */
public final class SlaNextDayRule {
    private SlaNextDayRule() {}
    public enum Kind { CALENDAR_DAY, WORKING_DAY }
    public record Rule(Kind kind,LocalTime cutoff,String reference) {
        public Rule {
            if(kind==null||cutoff==null||cutoff.getSecond()!=0||cutoff.getNano()!=0||reference==null||reference.isBlank())
                throw new IllegalArgumentException("Explicit minute cutoff, day kind and publication reference required");
        }
    }
    public record Result(Instant deadline,String missing) {}
    public static Result resolve(Instant openedAt,Rule rule,WorkingTime.CalendarData calendar){
        if(openedAt==null)return new Result(null,"OPENED_AT_MISSING");
        if(rule==null)return new Result(null,"NEXT_DAY_RULE_MISSING");
        ZoneId zone=ZoneId.of("Asia/Shanghai");LocalDate day=openedAt.atZone(zone).toLocalDate().plusDays(1);
        if(rule.kind()==Kind.CALENDAR_DAY)return new Result(day.atTime(rule.cutoff()).atZone(zone).toInstant(),null);
        if(calendar==null||!calendar.complete())return new Result(null,"CALENDAR_MISSING_OR_INCOMPLETE");
        // Technical search bound, never an implicit workday/holiday fallback.
        for(int i=0;i<3660;i++,day=day.plusDays(1)){
            Instant start=day.atStartOfDay(zone).toInstant(),end=day.plusDays(1).atStartOfDay(zone).toInstant();
            if(start.isBefore(calendar.coverage().start())||end.isAfter(calendar.coverage().end()))return new Result(null,"CALENDAR_COVERAGE_INCOMPLETE");
            boolean working=calendar.workingWindows().stream().anyMatch(w->w.start().isBefore(end)&&w.end().isAfter(start));
            if(working)return new Result(day.atTime(rule.cutoff()).atZone(zone).toInstant(),null);
        }
        return new Result(null,"NEXT_WORKING_DAY_SEARCH_LIMIT");
    }
}
