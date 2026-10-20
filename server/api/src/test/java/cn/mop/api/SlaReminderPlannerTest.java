package cn.mop.api;

import cn.mop.domain.sla.SlaReminderPlanner;
import cn.mop.domain.sla.SlaReminderPlanner.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SlaReminderPlannerTest {
    private static final UUID TENANT=UUID.fromString("74000000-0000-4000-8000-000000000001");
    private static final UUID TICKET=UUID.fromString("74000000-0000-4000-8000-000000000002");
    private static final List<BigDecimal> NODES=List.of(new BigDecimal("0.5"),new BigDecimal("0.8"),BigDecimal.ONE);
    private static Plan plan(Duration elapsed,Set<Key> recorded) {
        return SlaReminderPlanner.plan(TENANT,TICKET,Clock.RESPONSE,State.RUNNING,elapsed,Duration.ofSeconds(100),NODES,recorded);
    }
    @Test void exactHalfThresholdDoesNotRoundAnEarlierNanosecondUp(){
        assertTrue(plan(Duration.ofSeconds(50).minusNanos(1),Set.of()).due().isEmpty());
        assertEquals(1,plan(Duration.ofSeconds(50),Set.of()).due().size());
    }
    @Test void delayedTickPlansAllUnclaimedReachedNodes(){
        var p=plan(Duration.ofSeconds(110),Set.of());
        assertEquals(NODES,p.due().stream().map(Key::threshold).toList());
        assertTrue(p.escalationRequired());assertFalse(p.delivered());assertFalse(p.claimed());
    }
    @Test void durableKeysPreventRepeatedReminderAndEscalationPlans(){
        var first=plan(Duration.ofSeconds(100),Set.of());
        var recorded=new HashSet<>(first.due());
        assertTrue(plan(Duration.ofSeconds(200),recorded).due().isEmpty());
        assertFalse(plan(Duration.ofSeconds(200),recorded).escalationRequired());
        assertEquals(3,recorded.size());
    }
    @Test void partialClaimsDoNotSuppressOtherNodes(){
        var first=plan(Duration.ofSeconds(50),Set.of());
        var later=plan(Duration.ofSeconds(80),Set.copyOf(first.due()));
        assertEquals(List.of(new BigDecimal("0.8")),later.due().stream().map(Key::threshold).toList());
    }
    @Test void pausedAndCompletedClocksDoNotProduceIntents(){
        for(State state:List.of(State.PAUSED,State.COMPLETED)){
            var p=SlaReminderPlanner.plan(TENANT,TICKET,Clock.RECOVERY,state,Duration.ofDays(3),Duration.ofSeconds(1),NODES,Set.of());
            assertTrue(p.due().isEmpty());assertFalse(p.escalationRequired());
        }
    }
    @Test void missingTimingRulesOrDeduplicationStateNeverMeanEmptySuccessfulLookup(){
        assertEquals("CONFIG_PENDING",SlaReminderPlanner.plan(TENANT,TICKET,Clock.RESPONSE,State.RUNNING,null,Duration.ofSeconds(1),NODES,Set.of()).status());
        assertEquals("CONFIG_PENDING",SlaReminderPlanner.plan(TENANT,TICKET,Clock.RESPONSE,State.RUNNING,Duration.ZERO,null,NODES,Set.of()).status());
        assertEquals("CONFIG_PENDING",SlaReminderPlanner.plan(TENANT,TICKET,Clock.RESPONSE,State.RUNNING,Duration.ZERO,Duration.ofSeconds(1),null,Set.of()).status());
        assertEquals("CONFIG_PENDING",plan(Duration.ofSeconds(100),null).status());
    }
    @Test void numericEquivalentKeysDeduplicateAndDuplicateConfigurationIsRejected(){
        assertEquals(new Key(TENANT,TICKET,Clock.RESPONSE,new BigDecimal(".50")),
                new Key(TENANT,TICKET,Clock.RESPONSE,new BigDecimal(".5")));
        assertThrows(IllegalArgumentException.class,()->SlaReminderPlanner.plan(TENANT,TICKET,Clock.RESPONSE,State.RUNNING,
                Duration.ofSeconds(100),Duration.ofSeconds(100),List.of(new BigDecimal(".5"),new BigDecimal(".50")),Set.of()));
    }
    @Test void crossTenantTicketAndClockKeysAreRejected(){
        for(Key key:List.of(new Key(TICKET,TICKET,Clock.RESPONSE,BigDecimal.ONE),
                new Key(TENANT,TENANT,Clock.RESPONSE,BigDecimal.ONE),new Key(TENANT,TICKET,Clock.RECOVERY,BigDecimal.ONE)))
            assertThrows(IllegalArgumentException.class,()->plan(Duration.ofSeconds(100),Set.of(key)));
    }
    @Test void largeDurationsKeepExactPrecisionWithoutLongNanosecondOverflow(){
        var p=SlaReminderPlanner.plan(TENANT,TICKET,Clock.RESPONSE,State.RUNNING,Duration.ofSeconds(Long.MAX_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE),NODES,Set.of());
        assertEquals(3,p.due().size());
    }
    @Test void rejectsInvalidTimingAndThresholdsAndReturnsImmutablePlans(){
        assertThrows(IllegalArgumentException.class,()->plan(Duration.ofSeconds(-1),Set.of()));
        for(BigDecimal v:List.of(BigDecimal.ZERO,new BigDecimal("1.01"),new BigDecimal("0.0000000000000000001")))
            assertThrows(IllegalArgumentException.class,()->SlaReminderPlanner.plan(TENANT,TICKET,Clock.RESPONSE,State.RUNNING,Duration.ZERO,Duration.ofSeconds(1),List.of(v),Set.of()));
        assertThrows(UnsupportedOperationException.class,()->plan(Duration.ofSeconds(100),Set.of()).due().clear());
    }
}
