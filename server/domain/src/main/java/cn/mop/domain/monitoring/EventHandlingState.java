package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.*;
import cn.mop.domain.monitoring.RawMonitoringEvent.Phase;

/** Explicit immutable event handling only; never authenticates sources, authorizes actors, persists or sends. */
public final class EventHandlingState {
    public enum Handling { NEW, ACKNOWLEDGED, IN_PROGRESS, PENDING_REVIEW, RESOLVED, CLOSED }
    public enum Action { OPEN, ACKNOWLEDGE, START, MONITOR_SIGNAL, RESOLVE, CLOSE }
    public enum ReviewMode { REQUIRE_RECOVERED_AND_MANUAL_ROOT_CAUSE }
    public record ReviewPolicy(String publicationReference,ReviewMode mode) {}
    public record Signal(UUID rawId,UUID tenantId,UUID assetId,Phase phase,Instant occurredAt) {
        public Signal { if(rawId==null||tenantId==null||assetId==null||phase==null||occurredAt==null)throw invalid(); }
        @Override public String toString(){return "EventSignal[unverified source; redacted]";}
    }
    public record Change(long version,Action action,Handling handling,Phase monitorState,UUID actorId,
                         UUID rawId,Instant recordedAt,String reviewPublication,String rootCause) {
        @Override public String toString(){return "EventChange[version="+version+", action="+action+", details redacted]";}
    }
    private final UUID tenantId,eventId,assetId;
    private final Handling handling;
    private final Phase monitorState;
    private final Instant problemAt,firstAckAt,firstRecoveredAt,observedAt;
    private final List<Signal> signals;
    private final List<Change> history;
    private EventHandlingState(UUID tenantId,UUID eventId,UUID assetId,Handling handling,Phase monitorState,
                               Instant problemAt,Instant firstAckAt,Instant firstRecoveredAt,Instant observedAt,
                               List<Signal> signals,List<Change> history){
        this.tenantId=tenantId;this.eventId=eventId;this.assetId=assetId;this.handling=handling;this.monitorState=monitorState;
        this.problemAt=problemAt;this.firstAckAt=firstAckAt;this.firstRecoveredAt=firstRecoveredAt;this.observedAt=observedAt;
        this.signals=List.copyOf(signals);this.history=List.copyOf(history);
    }
    public static EventHandlingState open(UUID eventId,Signal problem,Instant at){
        if(eventId==null||problem==null||at==null||problem.phase()!=Phase.PROBLEM||problem.occurredAt().isAfter(at))throw invalid();
        return new EventHandlingState(problem.tenantId(),eventId,problem.assetId(),Handling.NEW,Phase.PROBLEM,
                problem.occurredAt(),null,null,at,List.of(problem),List.of(new Change(1,Action.OPEN,Handling.NEW,Phase.PROBLEM,null,problem.rawId(),at,null,null)));
    }
    public EventHandlingState acknowledge(long expected,UUID actor,Instant at){
        manual(expected,actor,at);if(handling!=Handling.NEW)throw state();
        return append(Action.ACKNOWLEDGE,Handling.ACKNOWLEDGED,monitorState,actor,null,at,null,null,at,firstRecoveredAt,signals);
    }
    public EventHandlingState start(long expected,UUID actor,Instant at){
        manual(expected,actor,at);if(handling!=Handling.ACKNOWLEDGED)throw state();
        return append(Action.START,Handling.IN_PROGRESS,monitorState,actor,null,at,null,null,firstAckAt,firstRecoveredAt,signals);
    }
    /** Recovery records an observation, never an automatic resolution or close. */
    public EventHandlingState observe(long expected,Signal signal,Instant at){
        if(signal==null||at==null)throw invalid();
        if(!tenantId.equals(signal.tenantId())||!assetId.equals(signal.assetId()))throw new DomainException(404,"NOT_FOUND","Event not found");
        for(var old:signals)if(old.rawId().equals(signal.rawId())){
            if(!old.equals(signal))throw new DomainException(409,"SOURCE_KEY_CONFLICT","Raw event identity has conflicting content");
            return this;
        }
        change(expected,at);
        if(signal.occurredAt().isAfter(at)||signal.occurredAt().isBefore(signals.get(signals.size()-1).occurredAt()))throw invalid();
        if(signal.occurredAt().equals(signals.get(signals.size()-1).occurredAt())&&signal.phase()!=monitorState)
            throw new DomainException(422,"CONFIG_PENDING","Simultaneous opposite source phases require ordering evidence");
        if(handling==Handling.CLOSED||handling==Handling.RESOLVED)throw state();
        // Recurrent PROBLEM after recovery requires a separate merge/new-incident decision and Q29; do not guess it here.
        if(signal.phase()==Phase.PROBLEM&&monitorState==Phase.RECOVERED)throw new DomainException(422,"CONFIG_PENDING","Recurring problem disposition requires explicit policy");
        Handling next=signal.phase()==Phase.RECOVERED?Handling.PENDING_REVIEW:handling;
        var nextSignals=new ArrayList<>(signals);nextSignals.add(signal);
        return append(Action.MONITOR_SIGNAL,next,signal.phase(),null,signal.rawId(),at,null,null,firstAckAt,
                firstRecoveredAt==null&&signal.phase()==Phase.RECOVERED?signal.occurredAt():firstRecoveredAt,nextSignals);
    }
    public EventHandlingState resolve(long expected,UUID actor,ReviewPolicy policy,boolean rootCauseConfirmed,String rootCause,Instant at){
        manual(expected,actor,at);
        if(policy==null||policy.mode()!=ReviewMode.REQUIRE_RECOVERED_AND_MANUAL_ROOT_CAUSE||policy.publicationReference()==null||!policy.publicationReference().matches("release:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[1-9][0-9]{0,14}"))
            throw new DomainException(422,"CONFIG_PENDING","Published review policy required");
        if(handling!=Handling.PENDING_REVIEW||monitorState!=Phase.RECOVERED||!rootCauseConfirmed)throw state();
        if(rootCause==null||rootCause.isBlank()||!rootCause.equals(rootCause.strip())||rootCause.codePointCount(0,rootCause.length())>500||rootCause.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff))throw invalid();
        return append(Action.RESOLVE,Handling.RESOLVED,monitorState,actor,null,at,policy.publicationReference(),rootCause,firstAckAt,firstRecoveredAt,signals);
    }
    public EventHandlingState close(long expected,UUID actor,Instant at){
        manual(expected,actor,at);if(handling!=Handling.RESOLVED||monitorState!=Phase.RECOVERED)throw state();
        return append(Action.CLOSE,Handling.CLOSED,monitorState,actor,null,at,null,null,firstAckAt,firstRecoveredAt,signals);
    }
    private void manual(long expected,UUID actor,Instant at){if(actor==null)throw invalid();change(expected,at);}
    private void change(long expected,Instant at){
        if(expected!=version())throw new DomainException(409,"VERSION_CONFLICT","Event version changed");
        if(at==null||at.isBefore(observedAt))throw invalid();
        if(history.size()>=10000)throw new DomainException(422,"HISTORY_LIMIT","Event history exceeds calculation budget");
    }
    private EventHandlingState append(Action action,Handling next,Phase monitor,UUID actor,UUID raw,Instant at,
                                      String policy,String cause,Instant ack,Instant recovered,List<Signal> nextSignals){
        var changes=new ArrayList<>(history);changes.add(new Change(version()+1,action,next,monitor,actor,raw,at,policy,cause));
        return new EventHandlingState(tenantId,eventId,assetId,next,monitor,problemAt,ack,recovered,at,nextSignals,changes);
    }
    public UUID tenantId(){return tenantId;}public UUID eventId(){return eventId;}public UUID assetId(){return assetId;}
    public Handling handling(){return handling;}public Phase monitorState(){return monitorState;}public long version(){return history.size();}
    public Instant problemAt(){return problemAt;}public Instant firstAckAt(){return firstAckAt;}public Instant firstRecoveredAt(){return firstRecoveredAt;}public Instant observedAt(){return observedAt;}
    public List<Signal> signals(){return signals;}public List<Change> history(){return history;}
    public boolean durablyAccepted(){return false;}public boolean notificationDispatched(){return false;}
    @Override public String toString(){return "EventHandlingState[handling="+handling+", version="+version()+", details redacted]";}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","Invalid event handling input");}
    private static DomainException state(){return new DomainException(422,"STATE_NOT_ALLOWED","Event action is not allowed in the current state");}
}
