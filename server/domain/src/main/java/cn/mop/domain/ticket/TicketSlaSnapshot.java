package cn.mop.domain.ticket;

import cn.mop.domain.DomainException;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime;
import java.time.*;
import java.util.*;

/** Immutable, explicit opening-time policy. Missing inputs remain unknown, never a four-hour fallback. */
public record TicketSlaSnapshot(String priority, CustomerConfirmationWindow.Tier tier, Instant startedAt,
        Instant responseDueAt, Instant recoveryDueAt, Long confirmationHours,
        List<Interval> responseWindows, List<Interval> recoveryWindows,
        Map<String,String> missing, List<Reference> references) {
    public record Reference(String key, String releaseId, long version, String definitionHash, String source) {
        public Reference {
            if (key == null || key.isBlank() || definitionHash == null || !definitionHash.matches("[0-9a-f]{64}")
                    || !Set.of("CONTRACT_DEFAULT", "RUNTIME_RELEASE").contains(source == null ? "" : source)
                    || (source.equals("RUNTIME_RELEASE") ? releaseId == null || version < 1 : releaseId != null || version != 0))
                throw invalid();
        }
    }
    public TicketSlaSnapshot {
        if (!Set.of("P1", "P2", "P3").contains(priority == null ? "" : priority) || tier == null || startedAt == null
                || confirmationHours != null && (confirmationHours < 1 || confirmationHours > 8760)) throw invalid();
        time(startedAt); time(responseDueAt); time(recoveryDueAt);
        responseWindows = List.copyOf(responseWindows); recoveryWindows = List.copyOf(recoveryWindows);
        missing = Map.copyOf(missing); references = List.copyOf(references);
        checkWindows(startedAt, responseDueAt, responseWindows); checkWindows(startedAt, recoveryDueAt, recoveryWindows);
    }
    public static TicketSlaSnapshot freeze(String priority, CustomerConfirmationWindow.Tier tier, Instant start,
            Duration response, Duration recovery, Instant explicitNextDayRecovery, Long confirmationHours,
            WorkingTime.CalendarData calendar, List<Reference> refs, Map<String,String> unavailable) {
        var missing = new LinkedHashMap<>(unavailable);
        Clock responseClock = clock("response", tier, start, response, calendar, missing);
        Clock recoveryClock;
        if (explicitNextDayRecovery != null) {
            if (recovery != null || !"P3".equals(priority) || tier == CustomerConfirmationWindow.Tier.ADVANCED
                    || !explicitNextDayRecovery.isAfter(start)) throw invalid();
            time(explicitNextDayRecovery);
            if (tier == CustomerConfirmationWindow.Tier.BASIC) {
                if (calendar == null || !calendar.complete() || start.isBefore(calendar.coverage().start())
                        || explicitNextDayRecovery.isAfter(calendar.coverage().end())) {
                    missing.put("recovery", "CALENDAR_COVERAGE_INCOMPLETE"); recoveryClock = new Clock(null,List.of());
                } else {
                    List<Interval> windows = calendar.workingWindows().stream()
                        .filter(w -> w.end().isAfter(start) && w.start().isBefore(explicitNextDayRecovery))
                        .map(w -> new Interval(w.start().isBefore(start) ? start : w.start(),
                                w.end().isAfter(explicitNextDayRecovery) ? explicitNextDayRecovery : w.end())).toList();
                    if (windows.isEmpty() || !windows.get(windows.size()-1).end().equals(explicitNextDayRecovery)) {
                        missing.put("recovery", "NEXT_DAY_NOT_IN_WORKING_TIME"); recoveryClock = new Clock(null,List.of());
                    } else recoveryClock = new Clock(explicitNextDayRecovery,windows);
                }
            } else recoveryClock = new Clock(explicitNextDayRecovery,List.of(new Interval(start,explicitNextDayRecovery)));
        } else recoveryClock = clock("recovery", tier, start, recovery, calendar, missing);
        if (confirmationHours == null) missing.put("confirmation", "CONFIGURATION_UNAVAILABLE");
        return new TicketSlaSnapshot(priority,tier,start,responseClock.due,recoveryClock.due,confirmationHours,
                responseClock.windows,recoveryClock.windows,missing,refs);
    }
    private record Clock(Instant due,List<Interval> windows) {}
    private static Clock clock(String name,CustomerConfirmationWindow.Tier tier,Instant start,Duration duration,
            WorkingTime.CalendarData calendar,Map<String,String> missing) {
        if (duration == null) { missing.putIfAbsent(name,"CONFIGURATION_UNAVAILABLE"); return new Clock(null,List.of()); }
        if (start == null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofDays(366)) > 0) throw invalid();
        if (tier == CustomerConfirmationWindow.Tier.BASIC) {
            var result = WorkingTime.advance(calendar,start,duration);
            if (result.status() == WorkingTime.Status.UNRESOLVED) {
                missing.put(name,result.reason().name()); return new Clock(null,List.of());
            }
            return new Clock(result.deadline(),result.consumed());
        }
        try { Instant end=start.plus(duration); time(end); return new Clock(end,List.of(new Interval(start,end))); }
        catch (DateTimeException|ArithmeticException error) { throw invalid(); }
    }
    /** Percentages count working milliseconds, not wall-clock time across weekends or holidays. */
    public Instant threshold(String clock,int percent) {
        if (!Set.of(50,80,100).contains(percent)) throw invalid();
        List<Interval> windows = switch(clock) { case "response" -> responseWindows; case "recovery" -> recoveryWindows; default -> throw invalid(); };
        if (windows.isEmpty()) return null;
        long total=windows.stream().mapToLong(w->Duration.between(w.start(),w.end()).toMillis()).sum();
        long remaining=(Math.multiplyExact(total,percent)+99)/100;
        for (var window:windows) {
            long length=Duration.between(window.start(),window.end()).toMillis();
            if (remaining<=length) return window.start().plusMillis(remaining);
            remaining-=length;
        }
        throw new IllegalStateException("Frozen SLA windows do not cover duration");
    }
    private static void checkWindows(Instant start,Instant due,List<Interval> windows) {
        if (due == null) { if (!windows.isEmpty()) throw invalid(); return; }
        if (!due.isAfter(start) || windows.isEmpty() || !windows.get(windows.size()-1).end().equals(due)) throw invalid();
        Instant previous=start;
        for (var w:windows) { time(w.start()); time(w.end()); if (w.start().isBefore(previous) || !w.end().isAfter(w.start())) throw invalid(); previous=w.end(); }
    }
    public static void time(Instant value) {
        if (value != null && (value.getNano()%1_000_000!=0 || value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))) throw invalid();
    }
    private static DomainException invalid() { return new DomainException(400,"VALIDATION_ERROR","工单SLA输入或冻结来源无效"); }
}
