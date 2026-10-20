package cn.mop.domain.sla;

import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.time.*;
import cn.mop.domain.time.IntervalSets.Interval;
import java.time.*;
import java.util.*;

/** Service-side projection of a complete immutable lifecycle history. Never a delivery receipt. */
public final class SlaClockState {
    private SlaClockState() {}
    public enum Status { RUNNING, PAUSED, COMPLETED, CONFIG_PENDING }
    public record Clock(Status status,Instant deadline,Instant stoppedAt,Duration elapsed,Duration budget,Boolean breached,String missing) {}
    public record Projection(Instant observedAt,long lifecycleVersion,Clock response,Clock recovery,Instant pausedAt,List<Interval> completedPauses,String configurationReference) {
        public Projection {completedPauses=List.copyOf(completedPauses);}
    }
    public static Projection project(SlaClockCalculator.OpeningSnapshot opening,List<TicketLifecycle.Snapshot> history,
                                     Instant observedAt,SlaClockCalculator.ReopenPolicy policy){
        if(opening==null||opening.openedAt()==null||observedAt==null||history==null||history.isEmpty()||history.size()>10000)throw new IllegalArgumentException("Explicit complete bounded clock history required");
        if(observedAt.isBefore(opening.openedAt()))throw new IllegalArgumentException("Observation predates opening");
        var first=history.get(0);if(first.version()!=1||first.state()!=TicketLifecycle.State.NEW||!first.lastTransitionAt().equals(opening.openedAt()))throw new IllegalArgumentException("History must begin with the opening");
        Instant responded=null,pauseStart=null;boolean reopened=false;var pauses=new ArrayList<Interval>();TicketLifecycle.Snapshot prior=null;
        for(var row:history){
            if(row==null||!row.tenantId().equals(first.tenantId())||row.lastTransitionAt().isAfter(observedAt)||!Objects.equals(row.responseDueAt(),first.responseDueAt())||!Objects.equals(row.recoveryDueAt(),first.recoveryDueAt()))throw new IllegalArgumentException("Clock history scope or snapshot mismatch");
            if(prior!=null){
                if(row.version()!=prior.version()+1||row.lastTransitionAt().isBefore(prior.lastTransitionAt())||!allowed(prior.state(),row.state()))throw new IllegalArgumentException("Clock history is incomplete or unordered");
                if(prior.state()==TicketLifecycle.State.NEW&&row.state()==TicketLifecycle.State.IN_PROGRESS&&responded==null)responded=row.lastTransitionAt();
                if(row.state()==TicketLifecycle.State.PENDING_CUSTOMER&&prior.state()!=TicketLifecycle.State.PENDING_CUSTOMER)pauseStart=row.lastTransitionAt();
                if(pauseStart!=null&&(row.state()==TicketLifecycle.State.IN_PROGRESS||row.state()==TicketLifecycle.State.REOPENED)){
                    if(row.lastTransitionAt().isAfter(pauseStart))pauses.add(new Interval(pauseStart,row.lastTransitionAt()));pauseStart=null;reopened=true;
                }
            }
            prior=row;
        }
        var last=history.get(history.size()-1);var initial=SlaClockCalculator.initial(opening);
        if(!Objects.equals(initial.response().at(),first.responseDueAt())||!Objects.equals(initial.recovery().at(),first.recoveryDueAt()))throw new IllegalArgumentException("Stored deadlines differ from immutable opening calculation");
        Instant responseEnd=responded==null?observedAt:responded;
        Clock response=clock(opening,responded==null?Status.RUNNING:Status.COMPLETED,initial.response(),responseEnd,responded,List.of(),opening.responseBudget());
        boolean complete=last.state()==TicketLifecycle.State.CLOSED||last.state()==TicketLifecycle.State.RESOLVED;
        boolean paused=last.state()==TicketLifecycle.State.PENDING_CUSTOMER;
        Instant recoveryEnd=complete||paused?last.resolvedAt():observedAt;
        if(recoveryEnd==null)throw new IllegalArgumentException("Resolved clock needs its authoritative instant");
        Clock recovery;
        if(reopened&&policy==null)recovery=new Clock(Status.CONFIG_PENDING,null,null,null,opening.recoveryBudget(),null,"REOPEN_POLICY_MISSING");
        else{
            var calculation=reopened?SlaClockCalculator.resume(opening,pauses,policy):initial;
            var exclusions=policy==SlaClockCalculator.ReopenPolicy.EXCLUDE_CONFIRMED_PAUSES?pauses:List.<Interval>of();
            recovery=clock(opening,complete?Status.COMPLETED:paused?Status.PAUSED:Status.RUNNING,calculation.recovery(),recoveryEnd,complete?last.resolvedAt():null,exclusions,opening.recoveryBudget());
        }
        return new Projection(observedAt,last.version(),response,recovery,paused?pauseStart:null,pauses,opening.configurationReleaseRef());
    }
    private static Clock clock(SlaClockCalculator.OpeningSnapshot opening,Status status,SlaClockCalculator.Deadline deadline,
                               Instant end,Instant stopped,List<Interval> pauses,Duration budget){
        if(deadline.at()==null)return new Clock(Status.CONFIG_PENDING,null,stopped,null,budget,null,deadline.reason());
        Duration elapsed=elapsed(opening,end,pauses);
        if(elapsed==null)return new Clock(Status.CONFIG_PENDING,null,stopped,null,budget,null,"OBSERVATION_OUTSIDE_CALENDAR_COVERAGE");
        // A configured calendar-date target can have no duration budget. Do not invent progress for it.
        Boolean breached=end.isAfter(deadline.at());
        return new Clock(status,status==Status.PAUSED?null:deadline.at(),stopped,elapsed,budget,breached,null);
    }
    private static Duration elapsed(SlaClockCalculator.OpeningSnapshot opening,Instant end,List<Interval> pauses){
        if(end.isBefore(opening.openedAt()))throw new IllegalArgumentException("Clock end predates opening");
        if(end.equals(opening.openedAt()))return Duration.ZERO;
        List<Interval> span=List.of(new Interval(opening.openedAt(),end));
        if(opening.tier()==SlaClockCalculator.Tier.BASIC){
            var calendar=opening.calendar();if(calendar==null||!calendar.complete()||opening.openedAt().isBefore(calendar.coverage().start())||end.isAfter(calendar.coverage().end()))return null;
            span=IntervalSets.intersection(span,calendar.workingWindows());
        }
        return IntervalSets.duration(IntervalSets.subtract(span,pauses));
    }
    private static boolean allowed(TicketLifecycle.State from,TicketLifecycle.State to){
        if(from==to)return Set.of(TicketLifecycle.State.NEW,TicketLifecycle.State.IN_PROGRESS,TicketLifecycle.State.WAITING_VENDOR,TicketLifecycle.State.REOPENED).contains(from);
        return switch(from){
            case NEW->to==TicketLifecycle.State.IN_PROGRESS;
            case IN_PROGRESS,REOPENED->to==TicketLifecycle.State.WAITING_VENDOR||to==TicketLifecycle.State.PENDING_CUSTOMER;
            case WAITING_VENDOR->to==TicketLifecycle.State.IN_PROGRESS;
            case PENDING_CUSTOMER->to==TicketLifecycle.State.CLOSED||to==TicketLifecycle.State.REOPENED;
            case CLOSED->to==TicketLifecycle.State.IN_PROGRESS;
            case RESOLVED->false;
        };
    }
}
