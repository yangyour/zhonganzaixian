package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.TicketCustomerActionRules.Relationships;
import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.TicketLifecycle.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Pure synthetic transition checks, not authenticated API or persistence evidence. */
class TicketLifecycleTest {
    private static final Instant T = Instant.parse("2026-10-20T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final Policy POLICY = new Policy(20, Duration.ofHours(48), Duration.ofHours(72), 2);
    private static final Membership CUSTOMER = new Membership("reporter", "tenant-a", "viewer", 1, true);
    private static final Membership ENGINEER = new Membership("engineer", "tenant-a", "msp", 1, true);
    private static final Relationships RELATIONSHIPS = new Relationships("tenant-a", "reporter", Set.of(), true);
    private static Snapshot at(State state) {
        boolean resolved = Set.of(State.PENDING_CUSTOMER, State.RESOLVED, State.CLOSED).contains(state);
        return new Snapshot("tenant-a", state, 1, 0, T, resolved ? T : null, state == State.CLOSED ? T : null,
                resolved ? HASH : null, state == State.CLOSED ? CloseMode.CUSTOMER_CONFIRM : null,
                T.plusSeconds(60), T.plusSeconds(120));
    }
    private static Command command(Action action) { return new Command(action, 1, T.plusSeconds(1), "处置说明".repeat(6), HASH); }
    private static Decision customer(Snapshot s, Command c, Policy p) {
        return TicketLifecycle.customer(s, c, p, CUSTOMER, RELATIONSHIPS);
    }
    private static Decision engineer(Snapshot s, Command c, Policy p) {
        return TicketLifecycle.engineer(s, c, p, ENGINEER);
    }

    @Test void completeCandidateMatrixRejectsAllUnlistedEdges() {
        Map<Action, Set<State>> allowed = Map.of(
                Action.ACCEPT, Set.of(State.NEW),
                Action.TRANSFER, Set.of(State.NEW, State.IN_PROGRESS, State.WAITING_VENDOR, State.REOPENED),
                Action.WAIT_FOR_VENDOR, Set.of(State.IN_PROGRESS, State.REOPENED),
                Action.RESUME, Set.of(State.WAITING_VENDOR),
                Action.SUBMIT_RESOLUTION, Set.of(State.IN_PROGRESS, State.REOPENED),
                Action.CONFIRM, Set.of(State.PENDING_CUSTOMER),
                Action.NOT_RESOLVED, Set.of(State.PENDING_CUSTOMER),
                Action.AUTO_CLOSE, Set.of(State.PENDING_CUSTOMER),
                Action.REOPEN, Set.of(State.CLOSED));
        for (State state : State.values()) for (Action action : Action.values()) {
            assertEquals(allowed.get(action).contains(state), TicketLifecycle.allows(state, action), state + "/" + action);
            if (!allowed.get(action).contains(state)) {
                assertThrows(DomainException.class, () -> dispatch(at(state), command(action)), state + "/" + action);
            }
        }
    }
    private static Decision dispatch(Snapshot s, Command c) {
        return switch (c.action()) {
            case CONFIRM, NOT_RESOLVED, REOPEN -> customer(s, c, POLICY);
            case AUTO_CLOSE -> TicketLifecycle.timeoutCandidate(s, c.expectedVersion(), c.occurredAt(), POLICY);
            default -> engineer(s, c, POLICY);
        };
    }

    @Test void acceptAndVendorWaitResumeAreCandidateTransitionsOnly() {
        Decision accepted = engineer(at(State.NEW), command(Action.ACCEPT), null);
        assertEquals(State.IN_PROGRESS, accepted.after().state());
        assertEquals(2, accepted.after().version());
        assertFalse(accepted.persisted()); assertFalse(accepted.productionApproved());
        assertEquals(State.WAITING_VENDOR, engineer(at(State.IN_PROGRESS), command(Action.WAIT_FOR_VENDOR), null).after().state());
        assertEquals(State.IN_PROGRESS, engineer(at(State.WAITING_VENDOR), command(Action.RESUME), null).after().state());
    }
    @Test void transferPreservesStateAndBothOriginalDeadlinesAndRequiresReason() {
        for (State state : Set.of(State.NEW, State.IN_PROGRESS, State.WAITING_VENDOR, State.REOPENED)) {
            Snapshot before = at(state);
            Decision d = engineer(before, command(Action.TRANSFER), null);
            assertEquals(state, d.after().state());
            assertEquals(before.responseDueAt(), d.after().responseDueAt());
            assertEquals(before.recoveryDueAt(), d.after().recoveryDueAt());
            assertEquals(ClockEffect.UNCHANGED, d.clockEffect());
            assertEquals(1, before.version());
        }
        assertThrows(DomainException.class, () -> engineer(at(State.NEW), new Command(Action.TRANSFER, 1, T, "  ", null), null));
    }
    @Test void solutionRequiresExplicitMinimumAndSnapshotAndPlansPause() {
        Snapshot before = at(State.IN_PROGRESS);
        assertEquals("CONFIG_PENDING", assertThrows(DomainException.class, () -> engineer(before, command(Action.SUBMIT_RESOLUTION), null)).code);
        assertThrows(DomainException.class, () -> engineer(before, new Command(Action.SUBMIT_RESOLUTION, 1, T, "短说明", HASH), POLICY));
        assertThrows(DomainException.class, () -> engineer(before, new Command(Action.SUBMIT_RESOLUTION, 1, T, "字".repeat(20), "invalid"), POLICY));
        Decision d = engineer(before, command(Action.SUBMIT_RESOLUTION), POLICY);
        assertEquals(State.PENDING_CUSTOMER, d.after().state());
        assertEquals(HASH, d.after().solutionSnapshotHash());
        assertEquals(T.plusSeconds(1), d.after().resolvedAt());
        assertEquals(ClockEffect.PAUSE_RECOVERY, d.clockEffect());
        assertEquals(before.recoveryDueAt(), d.after().recoveryDueAt());
    }
    @Test void configuredMinimumCountsUnicodeNotWhitespaceOrUtf16Units() {
        Policy p = new Policy(21, null, null, null);
        assertThrows(DomainException.class, () -> engineer(at(State.IN_PROGRESS),
                new Command(Action.SUBMIT_RESOLUTION, 1, T, "\u00a0" + "😀".repeat(20) + "\ufeff", HASH), p));
        assertDoesNotThrow(() -> engineer(at(State.IN_PROGRESS),
                new Command(Action.SUBMIT_RESOLUTION, 1, T, "😀".repeat(21), HASH), p));
    }
    @Test void customerConfirmationBindsSolutionSnapshotAndCloseMode() {
        Decision d = customer(at(State.PENDING_CUSTOMER), command(Action.CONFIRM), null);
        assertEquals(State.CLOSED, d.after().state());
        assertEquals(CloseMode.CUSTOMER_CONFIRM, d.after().closeMode());
        assertEquals(HASH, d.after().solutionSnapshotHash());
        assertEquals("SOLUTION_SNAPSHOT_CONFLICT", assertThrows(DomainException.class, () ->
                customer(at(State.PENDING_CUSTOMER), new Command(Action.CONFIRM, 1, T, null, "b".repeat(64)), null)).code);
    }
    @Test void internalActorsCannotConfirmAndCustomersCannotAccept() {
        assertEquals(403, assertThrows(DomainException.class, () -> engineer(at(State.PENDING_CUSTOMER), command(Action.CONFIRM), POLICY)).status);
        assertEquals(403, assertThrows(DomainException.class, () -> customer(at(State.NEW), command(Action.ACCEPT), POLICY)).status);
        assertEquals(403, assertThrows(DomainException.class, () -> TicketLifecycle.customer(at(State.PENDING_CUSTOMER), command(Action.CONFIRM), POLICY, ENGINEER, RELATIONSHIPS)).status);
    }
    @Test void crossTenantRevokedAndUnrelatedActorsFailClosed() {
        assertEquals(404, assertThrows(DomainException.class, () -> TicketLifecycle.engineer(at(State.NEW), command(Action.ACCEPT), POLICY, new Membership("e","tenant-b","msp",1,true))).status);
        assertEquals(401, assertThrows(DomainException.class, () -> TicketLifecycle.engineer(at(State.NEW), command(Action.ACCEPT), POLICY, new Membership("e","tenant-a","msp",1,false))).status);
        assertEquals(403, assertThrows(DomainException.class, () -> TicketLifecycle.customer(at(State.CLOSED), command(Action.REOPEN), POLICY, new Membership("other","tenant-a","viewer",1,true), RELATIONSHIPS)).status);
    }
    @Test void timeoutUsesExplicitDurationAndExactBoundary() {
        Snapshot before = at(State.PENDING_CUSTOMER);
        assertThrows(DomainException.class, () -> TicketLifecycle.timeoutCandidate(before,1,T.plus(Duration.ofHours(48)).minusNanos(1),POLICY));
        Decision d = TicketLifecycle.timeoutCandidate(before,1,T.plus(Duration.ofHours(48)),POLICY);
        assertEquals(CloseMode.TIMEOUT_AUTO,d.after().closeMode());
        assertEquals(State.CLOSED,d.after().state());
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->TicketLifecycle.timeoutCandidate(before,1,T.plusSeconds(999999),new Policy(null,null,null,null))).code);
    }
    @Test void unresolvedCountsAsReopenAndPlansOriginalClockResumption() {
        Decision d=customer(at(State.PENDING_CUSTOMER),command(Action.NOT_RESOLVED),POLICY);
        assertEquals(State.REOPENED,d.after().state());assertEquals(1,d.after().reopenCount());
        assertFalse(d.managerNotificationRequired());assertEquals(ClockEffect.RESUME_ORIGINAL_RECOVERY,d.clockEffect());
        assertThrows(DomainException.class,()->customer(at(State.PENDING_CUSTOMER),new Command(Action.NOT_RESOLVED,1,T,"",HASH),POLICY));
    }
    @Test void reopenIncludesSeventyTwoHourBoundaryAndPreservesSla() {
        Snapshot before = new Snapshot("tenant-a",State.CLOSED,7,1,T,T,T,HASH,CloseMode.TIMEOUT_AUTO,T.plusSeconds(60),T.plusSeconds(120));
        Decision d=customer(before,new Command(Action.REOPEN,7,T.plus(Duration.ofHours(72)),"仍未恢复",null),POLICY);
        assertEquals(State.IN_PROGRESS,d.after().state());assertEquals(2,d.after().reopenCount());
        assertTrue(d.managerNotificationRequired());assertNull(d.after().closedAt());assertNull(d.after().closeMode());
        assertEquals(before.responseDueAt(),d.after().responseDueAt());assertEquals(before.recoveryDueAt(),d.after().recoveryDueAt());
        assertThrows(DomainException.class,()->customer(before,new Command(Action.REOPEN,7,T.plus(Duration.ofHours(72)).plusNanos(1),"仍未恢复",null),POLICY));
    }
    @Test void missingConfigurationDoesNotInventReopenPolicy() {
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->customer(at(State.CLOSED),command(Action.REOPEN),new Policy(null,null,null,2))).code);
        assertEquals("CONFIG_PENDING",assertThrows(DomainException.class,()->customer(at(State.PENDING_CUSTOMER),command(Action.NOT_RESOLVED),new Policy(null,null,null,null))).code);
    }
    @Test void staleVersionTimeRollbackAndCounterOverflowAreRejected() {
        assertEquals(409,assertThrows(DomainException.class,()->engineer(at(State.NEW),new Command(Action.ACCEPT,2,T,null,null),null)).status);
        assertThrows(DomainException.class,()->engineer(at(State.NEW),new Command(Action.ACCEPT,1,T.minusNanos(1),null,null),null));
        Snapshot overflow=new Snapshot("tenant-a",State.CLOSED,1,Integer.MAX_VALUE,T,T,T,HASH,CloseMode.CUSTOMER_CONFIRM,null,null);
        assertThrows(DomainException.class,()->customer(overflow,command(Action.REOPEN),POLICY));
        Snapshot versionOverflow=new Snapshot("tenant-a",State.NEW,Long.MAX_VALUE,0,T,null,null,null,null,null,null);
        assertThrows(DomainException.class,()->engineer(versionOverflow,new Command(Action.ACCEPT,Long.MAX_VALUE,T,null,null),null));
    }
    @Test void malformedSnapshotsAndNullCommandsFailPredictably() {
        assertThrows(DomainException.class,()->new Snapshot("tenant-a",State.CLOSED,1,0,T,T,T.minusSeconds(1),HASH,CloseMode.CUSTOMER_CONFIRM,null,null));
        assertThrows(DomainException.class,()->new Snapshot("tenant-a",State.PENDING_CUSTOMER,1,0,T,null,null,null,null,null,null));
        assertThrows(DomainException.class,()->engineer(at(State.NEW),null,null));
        assertThrows(DomainException.class,()->customer(at(State.CLOSED),null,null));
    }
}
