package cn.mop.domain.ticket;

import cn.mop.domain.DomainException;
import cn.mop.domain.TicketCustomerActionRules;
import java.time.*;
import java.util.*;

/** Local-authority workflow. Persistence must supply current authorization and commit state/event/outbox atomically. */
public final class WorkflowLifecycle {
    private WorkflowLifecycle() {}
    public enum State { OPEN, ACCEPTED, IN_PROGRESS, RESOLVED, CLOSED, CANCELLED }
    public enum Action { ACCEPT, REASSIGN, START, RESOLVE, CLOSE, REOPEN, CANCEL }
    public record Operator(String id,boolean internal,boolean administrator,boolean eligibleCustomer) {
        public Operator { if(id==null||id.isBlank()||internal&&eligibleCustomer||administrator&&!internal)throw invalid(); }
    }
    public record Command(Action action,long expectedVersion,String assigneeId,String note,String reasonCode,boolean retainPrevious) {
        public Command { if(action==null||expectedVersion<1)throw invalid(); }
    }
    public record Snapshot(long version,State state,TicketSlaSnapshot sla,String assigneeId,List<String> collaborators,
            Instant openedAt,Instant acceptedAt,Instant resolvedAt,Instant closedAt,Instant confirmDueAt,
            String closeMode,int reopenCount,int confirmationCycle,String resolutionNote,Instant updatedAt) {
        public Snapshot {
            if(version<1||version>9007199254740990L||state==null||sla==null||openedAt==null||updatedAt==null
                    ||updatedAt.isBefore(openedAt)||reopenCount<0||confirmationCycle<0)throw invalid();
            collaborators=List.copyOf(collaborators);
            if(new HashSet<>(collaborators).size()!=collaborators.size()||assigneeId!=null&&collaborators.contains(assigneeId))throw invalid();
            for(Instant t:new Instant[]{openedAt,acceptedAt,resolvedAt,closedAt,confirmDueAt,updatedAt})TicketSlaSnapshot.time(t);
            if(!openedAt.equals(sla.startedAt())||state==State.OPEN&&(assigneeId!=null||acceptedAt!=null)
                    ||Set.of(State.ACCEPTED,State.IN_PROGRESS,State.RESOLVED,State.CLOSED).contains(state)&&(assigneeId==null||acceptedAt==null)
                    ||(state==State.CLOSED)!=(closedAt!=null&&closeMode!=null)
                    ||closeMode!=null&&!Set.of("customer_confirm","timeout_auto").contains(closeMode)
                    ||(state==State.RESOLVED||state==State.CLOSED)&&(resolvedAt==null||resolutionNote==null||confirmationCycle<1)
                    ||confirmDueAt!=null&&(resolvedAt==null||!confirmDueAt.isAfter(resolvedAt))
                    ||acceptedAt!=null&&(acceptedAt.isBefore(openedAt)||acceptedAt.isAfter(updatedAt))
                    ||resolvedAt!=null&&(acceptedAt==null||resolvedAt.isBefore(acceptedAt)||resolvedAt.isAfter(updatedAt))
                    ||closedAt!=null&&(resolvedAt==null||closedAt.isBefore(resolvedAt)||closedAt.isAfter(updatedAt)))throw invalid();
        }
    }
    public record Transition(Snapshot snapshot,List<String> effects) {
        public Transition { effects=List.copyOf(effects); }
    }
    public static Snapshot open(TicketSlaSnapshot sla,Instant now) {
        if(now==null||now.isBefore(sla.startedAt()))throw invalid();
        return new Snapshot(1,State.OPEN,sla,null,List.of(),sla.startedAt(),null,null,null,null,null,0,0,null,now);
    }
    public static Transition apply(Snapshot s,Command c,Operator who,Instant now) {
        if(s==null||c==null||who==null||now==null)throw invalid();
        TicketSlaSnapshot.time(now);
        if(c.expectedVersion()!=s.version())throw new DomainException(409,"VERSION_CONFLICT","工单版本已变化，请刷新后重试");
        if(now.isBefore(s.updatedAt()))throw new DomainException(503,"CLOCK_UNAVAILABLE","服务器时间早于已保存工单时间");
        if(s.version()>=9007199254740990L)throw forbiddenState();
        var state=s.state();String assignee=s.assigneeId(),close=s.closeMode(),resolution=s.resolutionNote();
        var collaborators=new LinkedHashSet<>(s.collaborators());Instant accepted=s.acceptedAt(),resolved=s.resolvedAt(),closed=s.closedAt(),due=s.confirmDueAt();
        int reopen=s.reopenCount(),cycle=s.confirmationCycle();var effects=new ArrayList<String>();
        switch(c.action()) {
            case ACCEPT -> { internal(who);require(state,State.OPEN);state=State.ACCEPTED;assignee=who.id();accepted=now;effects.add("ticket.accepted"); }
            case START -> { responsible(s,who);require(state,State.ACCEPTED);state=State.IN_PROGRESS;effects.add("ticket.started"); }
            case REASSIGN -> {
                responsible(s,who);require(state,State.ACCEPTED,State.IN_PROGRESS);reason(c.note());reason(c.reasonCode());
                if(c.assigneeId()==null||c.assigneeId().isBlank()||c.assigneeId().equals(assignee))throw invalid();
                if(c.retainPrevious())collaborators.add(assignee);else collaborators.remove(assignee);
                collaborators.remove(c.assigneeId());assignee=c.assigneeId();state=State.ACCEPTED;effects.add("ticket.reassigned");
            }
            case RESOLVE -> {
                responsible(s,who);require(state,State.IN_PROGRESS);reason(c.note());TicketCustomerActionRules.requireSolution(c.note());
                state=State.RESOLVED;resolved=now;resolution=c.note();cycle=Math.addExact(cycle,1);
                due=s.sla().confirmationHours()==null?null:plusHours(now,s.sla().confirmationHours());effects.add("ticket.resolved");
            }
            case CLOSE -> { customer(who);require(state,State.RESOLVED);state=State.CLOSED;closed=now;close="customer_confirm";effects.add("ticket.closed"); }
            case REOPEN -> {
                customer(who);require(state,State.RESOLVED,State.CLOSED);reason(c.note());
                if(state==State.CLOSED)TicketCustomerActionRules.requireReopenWindow(closed,now);
                state=State.IN_PROGRESS;closed=null;close=null;due=null;resolved=null;resolution=null;reopen=Math.addExact(reopen,1);
                effects.add("ticket.reopened");if(reopen>=2)effects.add("ticket.manager_review_required");
            }
            case CANCEL -> { if(!who.administrator())throw forbidden();require(state,State.OPEN,State.ACCEPTED,State.IN_PROGRESS);reason(c.note());state=State.CANCELLED;effects.add("ticket.cancelled"); }
        }
        return new Transition(new Snapshot(s.version()+1,state,s.sla(),assignee,List.copyOf(collaborators),s.openedAt(),accepted,resolved,closed,due,close,reopen,cycle,resolution,now),effects);
    }
    /** Only a server worker can call this; HTTP actions cannot select system auto-close. */
    public static Optional<Transition> autoClose(Snapshot s,Instant now) {
        TicketSlaSnapshot.time(now);
        if(now==null||now.isBefore(s.updatedAt()))throw invalid();
        if(s.state()!=State.RESOLVED||s.confirmDueAt()==null||now.isBefore(s.confirmDueAt()))return Optional.empty();
        return Optional.of(new Transition(new Snapshot(s.version()+1,State.CLOSED,s.sla(),s.assigneeId(),s.collaborators(),s.openedAt(),s.acceptedAt(),s.resolvedAt(),now,s.confirmDueAt(),"timeout_auto",s.reopenCount(),s.confirmationCycle(),s.resolutionNote(),now),List.of("ticket.auto_closed")));
    }
    public record ClockNotice(String key,String eventType,Instant dueAt,int cycle) {}
    /** Due facts only. The repository uniquely persists each key before any delivery attempt. */
    public static List<ClockNotice> dueNotices(Snapshot s,Instant now) {
        return dueNotices(s,now,false,null);
    }
    public static List<ClockNotice> dueNotices(Snapshot s,Instant now,boolean hasMonitoringEvents,Instant confirmedRecoveryAt) {
        if(now==null||now.isBefore(s.updatedAt()))throw invalid();TicketSlaSnapshot.time(now);
        if(s.state()==State.CANCELLED)return List.of();
        var result=new ArrayList<ClockNotice>();
        for(String clock:List.of("response","recovery")) {
            Instant completed=clock.equals("response")?s.acceptedAt():hasMonitoringEvents?confirmedRecoveryAt:s.resolvedAt();
            for(int percent:List.of(50,80,100)) {
                Instant due=s.sla().threshold(clock,percent);
                if(due!=null&&!now.isBefore(due)&&(completed==null||completed.isAfter(due)))
                    result.add(new ClockNotice(clock+":"+percent,"ticket.sla_"+percent,due,0));
            }
        }
        if(s.state()==State.RESOLVED&&s.confirmDueAt()!=null&&now.isBefore(s.confirmDueAt())) {
            Instant reminder=s.confirmDueAt().minus(Duration.ofHours(12));
            if(reminder.isBefore(s.resolvedAt()))reminder=s.resolvedAt();
            if(!now.isBefore(reminder))result.add(new ClockNotice("confirmation:"+s.confirmationCycle(),"ticket.confirmation_reminder",reminder,s.confirmationCycle()));
        }
        return List.copyOf(result);
    }
    private static Instant plusHours(Instant now,long hours) { try { Instant result=now.plus(Duration.ofHours(hours));TicketSlaSnapshot.time(result);return result; }catch(DateTimeException|ArithmeticException e){throw invalid();} }
    private static void internal(Operator o){if(!o.internal())throw forbidden();}
    private static void customer(Operator o){if(!o.eligibleCustomer()||o.internal())throw forbidden();}
    private static void responsible(Snapshot s,Operator o){internal(o);if(!o.administrator()&&!o.id().equals(s.assigneeId()))throw forbidden();}
    private static void require(State current,State... allowed){if(!List.of(allowed).contains(current))throw forbiddenState();}
    private static void reason(String value){TicketCustomerActionRules.requireUnresolvedReason(value);if(value.codePointCount(0,value.length())>3000)throw invalid();}
    private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前身份不能执行此工单动作");}
    private static DomainException forbiddenState(){return new DomainException(422,"STATE_NOT_ALLOWED","当前工单状态不允许此动作");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","工单动作或状态输入无效");}
}
