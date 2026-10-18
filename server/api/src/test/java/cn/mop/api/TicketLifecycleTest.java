package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.TicketLifecycle.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.time.IntervalSets.Interval;
import cn.mop.domain.time.WorkingTime.CalendarData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TicketLifecycleTest {
    static final Instant T=Instant.parse("2026-10-16T08:00:00Z");
    static final Operator ENGINEER=new Operator("engineer",true,false,false),OTHER=new Operator("other",true,false,false),ADMIN=new Operator("admin",true,true,false),CUSTOMER=new Operator("reporter",false,false,true);
    static final String SOLUTION="已核对现场网络线路并更换故障设备完成连通性验证，等待客户复核。";
    static TicketSlaSnapshot sla(Tier tier,Long hours){return TicketSlaSnapshot.freeze("P1",tier,T,Duration.ofMinutes(10),Duration.ofHours(2),null,hours,null,List.of(),Map.of());}
    static Snapshot open(){return TicketLifecycle.open(sla(Tier.STANDARD,48L),T);}
    static Transition act(Snapshot s,Action action,Operator who,Instant now){return TicketLifecycle.apply(s,new Command(action,s.version(),null,action==Action.RESOLVE?SOLUTION:"说明",null,false),who,now);}
    static Snapshot accepted(){return act(open(),Action.ACCEPT,ENGINEER,T.plusSeconds(60)).snapshot();}
    static Snapshot progress(){return act(accepted(),Action.START,ENGINEER,T.plusSeconds(120)).snapshot();}
    static Snapshot resolved(){return act(progress(),Action.RESOLVE,ENGINEER,T.plusSeconds(180)).snapshot();}
    static Snapshot closed(){return act(resolved(),Action.CLOSE,CUSTOMER,T.plusSeconds(240)).snapshot();}
    @Test void completeWorkflowFreezesDeadlinesAndFirstResponse(){
        Snapshot s=closed();assertEquals(State.CLOSED,s.state());assertEquals("customer_confirm",s.closeMode());assertEquals(T.plusSeconds(60),s.acceptedAt());
        assertEquals(T.plusSeconds(600),s.sla().responseDueAt());assertEquals(T.plusSeconds(7200),s.sla().recoveryDueAt());assertEquals(5,s.version());
        assertEquals(T.plusSeconds(180).plus(Duration.ofHours(48)),s.confirmDueAt());assertEquals(1,s.confirmationCycle());
    }
    @ParameterizedTest @EnumSource(Action.class) void customerCannotPerformInternalActions(Action action){
        if(Set.of(Action.CLOSE,Action.REOPEN).contains(action))return;
        assertEquals(403,assertThrows(DomainException.class,()->act(progress(),action,CUSTOMER,T.plusSeconds(300))).status);
    }
    @ParameterizedTest @EnumSource(value=Action.class,names={"CLOSE","REOPEN"}) void staffCannotImpersonateCustomer(Action action){
        assertEquals(403,assertThrows(DomainException.class,()->act(resolved(),action,ADMIN,T.plusSeconds(300))).status);
    }
    @ParameterizedTest @EnumSource(value=Action.class,names={"START","REASSIGN","RESOLVE"}) void unrelatedEngineerCannotHandleTicket(Action action){
        assertEquals(403,assertThrows(DomainException.class,()->act(accepted(),action,OTHER,T.plusSeconds(300))).status);
    }
    @Test void transferPreservesTimeAndRecordsCollaborator(){
        Snapshot before=progress();Snapshot after=TicketLifecycle.apply(before,new Command(Action.REASSIGN,before.version(),OTHER.id(),"技能分工调整","SKILL_REQUIRED",true),ENGINEER,T.plusSeconds(150)).snapshot();
        assertEquals(State.ACCEPTED,after.state());assertEquals(OTHER.id(),after.assigneeId());assertEquals(List.of(ENGINEER.id()),after.collaborators());
        assertSame(before.sla(),after.sla());assertEquals(before.acceptedAt(),after.acceptedAt());assertEquals(before.openedAt(),after.openedAt());
        Snapshot second=act(after,Action.START,OTHER,T.plusSeconds(151)).snapshot();assertEquals(State.IN_PROGRESS,second.state());
        assertEquals(403,assertThrows(DomainException.class,()->act(after,Action.START,ENGINEER,T.plusSeconds(151))).status);
    }
    @Test void transferBackRemovesCurrentAssigneeFromCollaborators(){
        Snapshot first=TicketLifecycle.apply(progress(),new Command(Action.REASSIGN,3,OTHER.id(),"原因","TEAM",true),ADMIN,T.plusSeconds(150)).snapshot();
        Snapshot second=TicketLifecycle.apply(first,new Command(Action.REASSIGN,4,ENGINEER.id(),"原因","TEAM",true),ADMIN,T.plusSeconds(151)).snapshot();
        assertEquals(List.of(OTHER.id()),second.collaborators());
    }
    @Test void rejectAndSecondReopenKeepOriginalClockAndRequireManagerEffect(){
        Snapshot initial=resolved();Transition rejection=act(initial,Action.REOPEN,CUSTOMER,T.plusSeconds(200));
        assertEquals(State.IN_PROGRESS,rejection.snapshot().state());assertEquals(1,rejection.snapshot().reopenCount());assertNull(rejection.snapshot().confirmDueAt());
        Snapshot again=act(rejection.snapshot(),Action.RESOLVE,ENGINEER,T.plusSeconds(300)).snapshot();assertEquals(2,again.confirmationCycle());
        Transition second=act(again,Action.REOPEN,CUSTOMER,T.plusSeconds(400));assertEquals(2,second.snapshot().reopenCount());assertTrue(second.effects().contains("ticket.manager_review_required"));
        assertEquals(initial.sla(),second.snapshot().sla());assertEquals(initial.acceptedAt(),second.snapshot().acceptedAt());assertNull(second.snapshot().resolutionNote());
    }
    @Test void reopeningAt72HoursIsAllowedWithoutResettingSla(){
        Snapshot s=closed();Snapshot reopened=act(s,Action.REOPEN,CUSTOMER,s.closedAt().plus(Duration.ofHours(72))).snapshot();
        assertEquals(State.IN_PROGRESS,reopened.state());assertNull(reopened.closedAt());assertNull(reopened.closeMode());assertSame(s.sla(),reopened.sla());
        assertEquals(422,assertThrows(DomainException.class,()->act(s,Action.REOPEN,CUSTOMER,s.closedAt().plus(Duration.ofHours(72)).plusMillis(1))).status);
    }
    @ParameterizedTest @EnumSource(value=Action.class,names={"ACCEPT","START","REASSIGN","RESOLVE","CANCEL"}) void closedTicketRejectsStaffTransitions(Action action){
        assertEquals(422,assertThrows(DomainException.class,()->act(closed(),action,ADMIN,T.plusSeconds(400))).status);
    }
    @Test void staleVersionWinsBeforeStateMutation(){
        Snapshot s=accepted();assertEquals(409,assertThrows(DomainException.class,()->TicketLifecycle.apply(s,new Command(Action.ACCEPT,1,null,null,null,false),OTHER,T.plusSeconds(300))).status);
        assertEquals(2,s.version());assertEquals(ENGINEER.id(),s.assigneeId());
    }
    @Test void twentyUnicodeCodepointsAndNonemptyReasonsAreRequired(){
        for(String note:List.of("已修复"," ","\uFEFF","x".repeat(19)))assertEquals(400,assertThrows(DomainException.class,()->TicketLifecycle.apply(progress(),new Command(Action.RESOLVE,3,null,note,null,false),ENGINEER,T.plusSeconds(200))).status);
        assertEquals(State.RESOLVED,TicketLifecycle.apply(progress(),new Command(Action.RESOLVE,3,null,"😀".repeat(20),null,false),ENGINEER,T.plusSeconds(200)).snapshot().state());
        assertEquals(400,assertThrows(DomainException.class,()->TicketLifecycle.apply(resolved(),new Command(Action.REOPEN,4,null,"\u00a0",null,false),CUSTOMER,T.plusSeconds(200))).status);
    }
    @Test void autoCloseIsDueInclusiveAndOnlyWorkerUsesTimeoutMode(){
        Snapshot s=resolved();assertTrue(TicketLifecycle.autoClose(s,s.confirmDueAt().minusMillis(1)).isEmpty());
        Snapshot done=TicketLifecycle.autoClose(s,s.confirmDueAt()).orElseThrow().snapshot();assertEquals(State.CLOSED,done.state());assertEquals("timeout_auto",done.closeMode());
        assertTrue(TicketLifecycle.autoClose(done,done.closedAt().plusSeconds(1)).isEmpty());assertEquals(State.IN_PROGRESS,act(done,Action.REOPEN,CUSTOMER,done.closedAt().plusSeconds(1)).snapshot().state());
    }
    @Test void missingConfirmationConfigurationNeverAutoCloses(){
        Snapshot s=TicketLifecycle.open(sla(Tier.STANDARD,null),T);s=act(s,Action.ACCEPT,ENGINEER,T.plusSeconds(1)).snapshot();s=act(s,Action.START,ENGINEER,T.plusSeconds(2)).snapshot();s=act(s,Action.RESOLVE,ENGINEER,T.plusSeconds(3)).snapshot();
        assertNull(s.confirmDueAt());assertTrue(TicketLifecycle.autoClose(s,T.plus(Duration.ofDays(30))).isEmpty());assertEquals(State.CLOSED,act(s,Action.CLOSE,CUSTOMER,T.plusSeconds(4)).snapshot().state());
    }
    @Test void reminderIsOneStableKeyPerConfirmationCycle(){
        Snapshot s=resolved();Instant at=s.confirmDueAt().minus(Duration.ofHours(12));
        assertTrue(TicketLifecycle.dueNotices(s,at.minusMillis(1)).stream().noneMatch(n->n.key().startsWith("confirmation")));
        assertEquals(List.of("confirmation:1"),TicketLifecycle.dueNotices(s,at).stream().filter(n->n.key().startsWith("confirmation")).map(ClockNotice::key).toList());
        assertTrue(TicketLifecycle.dueNotices(s,s.confirmDueAt()).stream().noneMatch(n->n.key().startsWith("confirmation")));
    }
    @Test void completionExactlyAtDeadlineIsNotAnOverdueNotice(){
        Snapshot s=act(open(),Action.ACCEPT,ENGINEER,T.plusSeconds(600)).snapshot();
        assertFalse(TicketLifecycle.dueNotices(s,T.plusSeconds(601)).stream().anyMatch(n->n.key().equals("response:100")));
        Snapshot late=act(open(),Action.ACCEPT,ENGINEER,T.plusSeconds(600).plusMillis(1)).snapshot();assertTrue(TicketLifecycle.dueNotices(late,T.plusSeconds(601)).stream().anyMatch(n->n.key().equals("response:100")));
    }
    @Test void cancelIsTerminalAndStopsClockTasks(){
        Snapshot s=act(progress(),Action.CANCEL,ADMIN,T.plusSeconds(300)).snapshot();assertEquals(State.CANCELLED,s.state());assertEquals(List.of(),TicketLifecycle.dueNotices(s,T.plus(Duration.ofDays(7))));
        assertEquals(422,assertThrows(DomainException.class,()->act(s,Action.ACCEPT,ADMIN,T.plusSeconds(301))).status);
    }
    @Test void clockRegressionIsNotSuccessfulAction(){assertEquals(503,assertThrows(DomainException.class,()->act(progress(),Action.RESOLVE,ENGINEER,T.plusSeconds(119))).status);}
    @Test void basicCalendarCountsActualWorkingTimeAcrossWeekend(){
        var friday=new Interval(T,T.plus(Duration.ofHours(2)));Instant monday=Instant.parse("2026-10-19T08:00:00Z");
        var calendar=new CalendarData("calendar",7,new Interval(T,monday.plus(Duration.ofDays(1))),List.of(friday,new Interval(monday,monday.plus(Duration.ofHours(8)))),true);
        var snapshot=TicketSlaSnapshot.freeze("P2",Tier.BASIC,T,Duration.ofHours(4),Duration.ofHours(8),null,48L,calendar,List.of(),Map.of());
        assertEquals(monday.plus(Duration.ofHours(2)),snapshot.responseDueAt());assertEquals(monday.plus(Duration.ofHours(6)),snapshot.recoveryDueAt());
        assertEquals(T.plus(Duration.ofHours(2)),snapshot.threshold("response",50));assertEquals(monday.plus(Duration.ofMinutes(72)),snapshot.threshold("response",80));
    }
    @Test void missingOrIncompleteCalendarIsUnknownNotSevenByTwentyFour(){
        var s=sla(Tier.BASIC,48L);assertNull(s.responseDueAt());assertNull(s.recoveryDueAt());assertEquals("CALENDAR_MISSING",s.missing().get("response"));assertNull(s.threshold("response",50));
        var calendar=new CalendarData("calendar",1,new Interval(T,T.plusSeconds(3000)),List.of(new Interval(T,T.plusSeconds(3000))),false);
        assertNull(TicketSlaSnapshot.freeze("P1",Tier.BASIC,T,Duration.ofMinutes(10),null,null,48L,calendar,List.of(),Map.of()).responseDueAt());
    }
    @Test void nextDayIsExplicitNotTwentyFourHours(){
        var absent=TicketSlaSnapshot.freeze("P3",Tier.STANDARD,T,Duration.ofHours(2),null,null,48L,null,List.of(),Map.of("recovery","NEXT_DAY_RULE_UNCONFIGURED"));assertNull(absent.recoveryDueAt());
        Instant next=T.plus(Duration.ofHours(30));var configured=TicketSlaSnapshot.freeze("P3",Tier.STANDARD,T,Duration.ofHours(2),null,next,48L,null,List.of(),Map.of());assertEquals(next,configured.recoveryDueAt());
    }
    @Test void frozenCollectionsCannotBeChangedByCaller(){
        var refs=new ArrayList<TicketSlaSnapshot.Reference>();refs.add(new TicketSlaSnapshot.Reference("test",null,0,"a".repeat(64),"CONTRACT_DEFAULT"));
        var s=TicketSlaSnapshot.freeze("P1",Tier.STANDARD,T,Duration.ofMinutes(10),Duration.ofHours(1),null,48L,null,refs,Map.of());refs.clear();assertEquals(1,s.references().size());assertThrows(UnsupportedOperationException.class,()->s.responseWindows().clear());
    }
}
