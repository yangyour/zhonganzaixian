package cn.mop.api;

import cn.mop.domain.notification.*;
import cn.mop.domain.notification.NotificationRouter.*;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft.LevelPreference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationRouterTest {
    final UUID tenant=UUID.randomUUID(),pool=UUID.randomUUID(),eventId=UUID.randomUUID(),receiver=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-20T12:00:00Z");
    Event event(Priority p){return new Event(tenant,pool,eventId,p,"SLA_50",now);}
    Matrix matrix(Priority p,Set<Channel> channels){return new Matrix(tenant,"matrix:v1",p,channels,Duration.ZERO,Duration.ofMinutes(5));}
    QuietHours.Policy disabled(){return new QuietHours.Policy("quiet:v1",false,null,null,null);}
    Preference pref(LevelPreference l,Set<Channel> c,QuietHours.Policy q){return new Preference(tenant,receiver,"preference:v1",l,c,q);}
    Binding binding(Channel c){return new Binding(tenant,receiver,c,"binding:v1",now.plusSeconds(3600));}
    Plan route(Priority p,Matrix m,Preference pref,List<Binding>b,List<Prior>h){return NotificationRouter.plan(event(p),receiver,m,pref,b,h,now);}
    Plan standard(Channel c,List<Prior> history){return route(Priority.P3,matrix(Priority.P3,Set.of(c)),pref(LevelPreference.ALL,Set.of(c),disabled()),List.of(binding(c)),history);}
    Deduplication key(Channel c){return new Deduplication(pool,eventId,c,receiver,"SLA_50");}
    @ParameterizedTest @EnumSource(Channel.class) void allSixChannelsRemainPlansNotSends(Channel c){var p=standard(c,List.of());assertEquals(Outcome.PLANNED,p.outcome());assertFalse(p.dispatchAuthorized());assertFalse(p.decisions().get(0).dispatched());assertFalse(p.decisions().get(0).delivered());assertEquals(key(c),p.decisions().get(0).key());}
    @Test void unknownHistoryIsNotEmptyHistory(){assertEquals(Outcome.CONFIG_PENDING,standard(Channel.EMAIL,null).outcome());}
    @Test void unknownPriorityIsPending(){assertEquals(Outcome.CONFIG_PENDING,route(null,matrix(Priority.P3,Set.of(Channel.EMAIL)),pref(LevelPreference.ALL,Set.of(Channel.EMAIL),disabled()),List.of(binding(Channel.EMAIL)),List.of()).outcome());}
    @Test void unpublishedPreferenceIsNotEffective(){var p=new Preference(tenant,receiver,null,LevelPreference.ALL,Set.of(Channel.EMAIL),disabled());assertEquals(Outcome.CONFIG_PENDING,route(Priority.P3,matrix(Priority.P3,Set.of(Channel.EMAIL)),p,List.of(binding(Channel.EMAIL)),List.of()).outcome());}
    @Test void levelAndChannelFiltersAreExplicit(){assertEquals(Outcome.FILTERED_LEVEL,route(Priority.P3,matrix(Priority.P3,Set.of(Channel.EMAIL)),pref(LevelPreference.P1_P2,Set.of(Channel.EMAIL),disabled()),List.of(binding(Channel.EMAIL)),List.of()).outcome());assertEquals(Outcome.FILTERED_CHANNEL,route(Priority.P3,matrix(Priority.P3,Set.of(Channel.EMAIL)),pref(LevelPreference.ALL,Set.of(),disabled()),List.of(),List.of()).outcome());}
    @Test void quietBlocksP3ButP1BypassesOnlyQuiet(){var quiet=new QuietHours.Policy("q",true,ZoneOffset.UTC,LocalTime.of(11,0),LocalTime.of(13,0));for(var p:List.of(Priority.P1,Priority.P3)){var plan=route(p,matrix(p,Set.of(Channel.EMAIL)),pref(LevelPreference.ALL,Set.of(Channel.EMAIL),quiet),List.of(binding(Channel.EMAIL)),List.of());assertEquals(p==Priority.P1?Outcome.PLANNED:Outcome.QUIET,plan.outcome());}assertEquals(Outcome.CONFIG_PENDING,route(Priority.P1,matrix(Priority.P1,Set.of(Channel.EMAIL)),pref(LevelPreference.ALL,Set.of(Channel.EMAIL),quiet),List.of(),List.of()).outcome());}
    @Test void missingQuietPolicyDoesNotBecomeDisabled(){assertEquals(Outcome.CONFIG_PENDING,route(Priority.P3,matrix(Priority.P3,Set.of(Channel.EMAIL)),pref(LevelPreference.ALL,Set.of(Channel.EMAIL),null),List.of(binding(Channel.EMAIL)),List.of()).outcome());}
    @Test void unresolvedPriorNeverBecomesRetryAfterFiveMinutes(){assertEquals(Outcome.RESULT_UNKNOWN,standard(Channel.EMAIL,List.of(new Prior(tenant,key(Channel.EMAIL),now.minusSeconds(3600),true))).outcome());}
    @Test void exactDedupeWindowBoundary(){assertEquals(Outcome.DUPLICATE,standard(Channel.EMAIL,List.of(new Prior(tenant,key(Channel.EMAIL),now.minusSeconds(299),false))).outcome());assertEquals(Outcome.PLANNED,standard(Channel.EMAIL,List.of(new Prior(tenant,key(Channel.EMAIL),now.minusSeconds(300),false))).outcome());}
    @Test void historyForOtherChannelDoesNotSuppress(){assertEquals(Outcome.PLANNED,standard(Channel.EMAIL,List.of(new Prior(tenant,key(Channel.SMS),now,false))).outcome());}
    @Test void bindingExpiryIsExclusive(){var b=new Binding(tenant,receiver,Channel.EMAIL,"binding:v1",now);assertEquals(Outcome.CONFIG_PENDING,route(Priority.P3,matrix(Priority.P3,Set.of(Channel.EMAIL)),pref(LevelPreference.ALL,Set.of(Channel.EMAIL),disabled()),List.of(b),List.of()).outcome());}
    @Test void aggregationUsesEventTimeAndRequiresBindingStillValid(){var m=new Matrix(tenant,"m",Priority.P3,Set.of(Channel.EMAIL),Duration.ofMinutes(5),Duration.ofMinutes(5));var p=pref(LevelPreference.ALL,Set.of(Channel.EMAIL),disabled());assertEquals(now.plusSeconds(300),route(Priority.P3,m,p,List.of(binding(Channel.EMAIL)),List.of()).decisions().get(0).notBefore());assertEquals(Outcome.CONFIG_PENDING,route(Priority.P3,m,p,List.of(new Binding(tenant,receiver,Channel.EMAIL,"b",now.plusSeconds(300))),List.of()).outcome());}
    @Test void crossTenantOrReceiverInputsRejected(){var p=pref(LevelPreference.ALL,Set.of(Channel.EMAIL),disabled());assertThrows(IllegalArgumentException.class,()->route(Priority.P3,new Matrix(UUID.randomUUID(),"m",Priority.P3,Set.of(),Duration.ZERO,Duration.ofMinutes(5)),p,List.of(),List.of()));assertThrows(IllegalArgumentException.class,()->standard(Channel.EMAIL,List.of(new Prior(UUID.randomUUID(),key(Channel.EMAIL),now,false))));assertThrows(IllegalArgumentException.class,()->route(Priority.P3,matrix(Priority.P3,Set.of(Channel.EMAIL)),p,List.of(new Binding(tenant,UUID.randomUUID(),Channel.EMAIL,"b",now.plusSeconds(60))),List.of()));}
    @Test void futureHistoryAndOtherPhaseRejected(){assertThrows(IllegalArgumentException.class,()->standard(Channel.EMAIL,List.of(new Prior(tenant,key(Channel.EMAIL),now.plusSeconds(1),false))));assertThrows(IllegalArgumentException.class,()->standard(Channel.EMAIL,List.of(new Prior(tenant,new Deduplication(pool,eventId,Channel.EMAIL,receiver,"OTHER"),now,false))));}
    @Test void duplicateBindingsRejected(){assertThrows(IllegalArgumentException.class,()->route(Priority.P3,matrix(Priority.P3,Set.of(Channel.EMAIL)),pref(LevelPreference.ALL,Set.of(Channel.EMAIL),disabled()),List.of(binding(Channel.EMAIL),binding(Channel.EMAIL)),List.of()));}
    @Test void immutableAndDeterministic(){var mutable=new HashSet<>(Set.of(Channel.EMAIL,Channel.SMS));var m=matrix(Priority.P3,mutable);mutable.clear();assertEquals(2,m.channels().size());var p=route(Priority.P3,m,pref(LevelPreference.ALL,m.channels(),disabled()),List.of(binding(Channel.SMS),binding(Channel.EMAIL)),List.of());assertEquals(List.of(Channel.EMAIL,Channel.SMS),p.decisions().stream().map(Decision::channel).toList());assertThrows(UnsupportedOperationException.class,()->p.decisions().clear());}
    @Test void missingAndInvalidFixedWindowFailClosed(){assertThrows(IllegalArgumentException.class,()->new Matrix(tenant,"m",Priority.P3,Set.of(Channel.EMAIL),Duration.ZERO,Duration.ofSeconds(1)));var m=new Matrix(tenant,"m",Priority.P3,Set.of(Channel.EMAIL),Duration.ZERO,null);assertEquals(Outcome.CONFIG_PENDING,route(Priority.P3,m,pref(LevelPreference.ALL,Set.of(Channel.EMAIL),disabled()),List.of(binding(Channel.EMAIL)),List.of()).outcome());}
}
