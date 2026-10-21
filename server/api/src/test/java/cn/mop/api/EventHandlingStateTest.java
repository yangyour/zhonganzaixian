package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.EventHandlingState;
import cn.mop.domain.monitoring.EventHandlingState.*;
import cn.mop.domain.monitoring.RawMonitoringEvent.Phase;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EventHandlingStateTest {
    final UUID tenant=UUID.randomUUID(),asset=UUID.randomUUID(),event=UUID.randomUUID(),actor=UUID.randomUUID();
    final Instant at=Instant.parse("2026-10-21T00:00:00.123456789Z");
    Signal signal(Phase phase,int seconds){return new Signal(UUID.randomUUID(),tenant,asset,phase,at.plusSeconds(seconds));}
    ReviewPolicy policy(){return new ReviewPolicy("release:"+UUID.randomUUID()+":1",ReviewMode.REQUIRE_RECOVERED_AND_MANUAL_ROOT_CAUSE);}
    EventHandlingState open(){return EventHandlingState.open(event,signal(Phase.PROBLEM,0),at);}
    EventHandlingState recovered(){return open().observe(1,signal(Phase.RECOVERED,1),at.plusSeconds(1));}
    void code(String expected,Runnable action){assertEquals(expected,assertThrows(DomainException.class,action::run).code);}
    @Test void humanWorkflowKeepsImmutableHistory(){var s=open();var a=s.acknowledge(1,actor,at.plusSeconds(1));var working=a.start(2,actor,at.plusSeconds(2));var review=working.observe(3,signal(Phase.RECOVERED,3),at.plusSeconds(4));var resolved=review.resolve(4,actor,policy(),true,"Confirmed test root cause",at.plusSeconds(5));var closed=resolved.close(5,actor,at.plusSeconds(6));assertEquals(Handling.NEW,s.handling());assertEquals(Handling.CLOSED,closed.handling());assertEquals(6,closed.version());assertEquals(Phase.RECOVERED,closed.monitorState());assertEquals(at.plusSeconds(1),closed.firstAckAt());assertEquals(at.plusSeconds(3),closed.firstRecoveredAt());assertEquals(6,closed.history().size());assertEquals(actor,closed.history().get(4).actorId());assertFalse(closed.durablyAccepted());assertFalse(closed.notificationDispatched());}
    @Test void recoveryFromEveryOpenHandlingStateNeedsReview(){var n=open();var a=n.acknowledge(1,actor,at);var p=a.start(2,actor,at);for(var s:new EventHandlingState[]{n,a,p})assertEquals(Handling.PENDING_REVIEW,s.observe(s.version(),signal(Phase.RECOVERED,1),at.plusSeconds(1)).handling());}
    @Test void recoveryCannotAutomaticallyCloseOrResolve(){var s=recovered();assertEquals(Handling.PENDING_REVIEW,s.handling());code("STATE_NOT_ALLOWED",()->s.close(s.version(),actor,at.plusSeconds(2)));assertNull(s.firstAckAt());}
    @Test void missingPolicyNeverInvented(){var s=recovered();code("CONFIG_PENDING",()->s.resolve(2,actor,null,true,"cause",at.plusSeconds(2)));code("CONFIG_PENDING",()->s.resolve(2,actor,new ReviewPolicy(null,ReviewMode.REQUIRE_RECOVERED_AND_MANUAL_ROOT_CAUSE),true,"cause",at.plusSeconds(2)));}
    @Test void malformedPolicyReferenceNotTrusted(){var s=recovered();code("CONFIG_PENDING",()->s.resolve(2,actor,new ReviewPolicy("secret-value",ReviewMode.REQUIRE_RECOVERED_AND_MANUAL_ROOT_CAUSE),true,"cause",at.plusSeconds(2)));}
    @Test void explicitHumanConfirmationRequired(){var s=recovered();code("STATE_NOT_ALLOWED",()->s.resolve(2,actor,policy(),false,"cause",at.plusSeconds(2)));}
    @Test void causeRequiredAndBounded(){var s=recovered();for(String cause:new String[]{null,""," "," leading","line\nbreak","x".repeat(501)})code("VALIDATION_ERROR",()->s.resolve(2,actor,policy(),true,cause,at.plusSeconds(2)));}
    @Test void activeProblemCannotBeResolved(){var s=open().acknowledge(1,actor,at).start(2,actor,at);code("STATE_NOT_ALLOWED",()->s.resolve(3,actor,policy(),true,"cause",at));}
    @Test void duplicateSignalIsIdempotentEvenAfterLaterManualChange(){var signal=signal(Phase.RECOVERED,1);var s=open().observe(1,signal,at.plusSeconds(1));var resolved=s.resolve(2,actor,policy(),true,"cause",at.plusSeconds(2));assertSame(resolved,resolved.observe(1,signal,at.plusSeconds(3)));}
    @Test void conflictingRawIdentityDoesNotOverwrite(){var signal=signal(Phase.RECOVERED,1);var s=open().observe(1,signal,at.plusSeconds(1));code("SOURCE_KEY_CONFLICT",()->s.observe(2,new Signal(signal.rawId(),tenant,asset,Phase.PROBLEM,signal.occurredAt()),at.plusSeconds(2)));}
    @Test void crossTenantAndAssetAreHidden(){var s=open();for(var signal:new Signal[]{new Signal(UUID.randomUUID(),UUID.randomUUID(),asset,Phase.RECOVERED,at),new Signal(UUID.randomUUID(),tenant,UUID.randomUUID(),Phase.RECOVERED,at)})code("NOT_FOUND",()->s.observe(1,signal,at));}
    @Test void staleVersionAndBackwardsObservationRejected(){var s=open().acknowledge(1,actor,at.plusSeconds(1));code("VERSION_CONFLICT",()->s.start(1,actor,at.plusSeconds(2)));code("VALIDATION_ERROR",()->s.start(2,actor,at));}
    @Test void futureAndOutOfOrderSourceDoNotAdvance(){var s=recovered();code("VALIDATION_ERROR",()->s.observe(2,signal(Phase.RECOVERED,3),at.plusSeconds(2)));code("VALIDATION_ERROR",()->s.observe(2,signal(Phase.RECOVERED,0),at.plusSeconds(2)));}
    @Test void simultaneousOppositePhasesNeedOrderingEvidence(){var s=open();code("CONFIG_PENDING",()->s.observe(1,signal(Phase.RECOVERED,0),at.plusSeconds(1)));}
    @Test void recurringProblemRequiresSeparateDisposition(){var s=recovered();code("CONFIG_PENDING",()->s.observe(2,signal(Phase.PROBLEM,2),at.plusSeconds(2)));}
    @Test void firstRecoveryNotOverwrittenByLaterObservation(){var s=recovered().observe(2,signal(Phase.RECOVERED,2),at.plusSeconds(2));assertEquals(at.plusSeconds(1),s.firstRecoveredAt());assertEquals(3,s.signals().size());}
    @Test void invalidTransitionsAndMissingActorRejected(){var s=open();code("STATE_NOT_ALLOWED",()->s.start(1,actor,at));code("VALIDATION_ERROR",()->s.acknowledge(1,null,at));var a=s.acknowledge(1,actor,at);code("STATE_NOT_ALLOWED",()->a.acknowledge(2,actor,at));}
    @Test void closedIsTerminalButExactReplayAllowed(){var signal=signal(Phase.RECOVERED,1);var s=open().observe(1,signal,at.plusSeconds(1)).resolve(2,actor,policy(),true,"sensitive root cause",at.plusSeconds(2)).close(3,actor,at.plusSeconds(3));code("STATE_NOT_ALLOWED",()->s.observe(4,signal(Phase.RECOVERED,4),at.plusSeconds(4)));assertSame(s,s.observe(1,signal,at.plusSeconds(4)));assertFalse(s.toString().contains("sensitive"));assertFalse(s.history().toString().contains("sensitive"));}
    @Test void snapshotsCannotBeMutatedAndNanosecondsPreserved(){var s=open();assertThrows(UnsupportedOperationException.class,()->s.history().clear());assertThrows(UnsupportedOperationException.class,()->s.signals().clear());assertEquals(at,s.problemAt());assertEquals(123456789,s.problemAt().getNano());}
    @Test void openingRequiresProblemNotFutureRecovery(){code("VALIDATION_ERROR",()->EventHandlingState.open(event,signal(Phase.RECOVERED,0),at));code("VALIDATION_ERROR",()->EventHandlingState.open(event,signal(Phase.PROBLEM,1),at));}
}
