package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.ChannelSubmissionResult;
import cn.mop.domain.notification.ChannelSubmissionResult.*;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChannelSubmissionResultTest {
    private final UUID tenant=UUID.randomUUID(), delivery=UUID.randomUUID(), request=UUID.randomUUID();
    private final Instant at=Instant.parse("2026-10-19T06:00:00Z");
    private DeliveryReceiptState started(Channel channel) {
        return DeliveryReceiptState.queued(tenant,delivery,new Deduplication(UUID.randomUUID(),UUID.randomUUID(),channel,UUID.randomUUID(),"OPENED"),at)
                .start(0,request,at);
    }
    private ChannelSubmissionResult result(Outcome outcome,Reason reason) {return new ChannelSubmissionResult(tenant,delivery,request,outcome,reason,at.plusSeconds(1));}
    @Test void acceptedIsNotDeliveredAndExactReplayIsIdempotent() {
        var before=started(Channel.WECHAT_WORK);var r=result(Outcome.ACCEPTED,Reason.PROVIDER_ACCEPTED);var id=UUID.randomUUID();
        var after=r.apply(before,1,id);assertEquals(Status.ACCEPTED,after.status());assertEquals(0,after.logicalDeliveredCount());assertNull(after.firstDeliveredAt());
        assertSame(after,r.apply(after,1,id));assertEquals(2,after.version());
        assertThrows(DomainException.class,()->after.start(2,UUID.randomUUID(),at.plusSeconds(2)));
    }
    @Test void rejectionRequiresExplicitDeadLetterBeforeNewAttempt() {
        var after=result(Outcome.REJECTED,Reason.PROVIDER_REJECTED).apply(started(Channel.WECHAT_WORK),1,UUID.randomUUID());
        assertEquals(Status.FAILED,after.status());assertThrows(DomainException.class,()->after.start(2,UUID.randomUUID(),at.plusSeconds(2)));
        var retry=after.deadLetter(2,at.plusSeconds(2)).start(3,UUID.randomUUID(),at.plusSeconds(3));
        assertEquals(Status.UNKNOWN,retry.status());assertEquals(2,retry.attempts().size());assertEquals(0,retry.logicalDeliveredCount());
    }
    @Test void unknownAndNotSubmittedNeverManufactureFailureOrEnableReplay() {
        var before=started(Channel.WECHAT_WORK);
        assertSame(before,result(Outcome.UNKNOWN,Reason.TRANSPORT_UNCERTAIN).apply(before,1,UUID.randomUUID()));
        assertSame(before,result(Outcome.NOT_SUBMITTED,Reason.TOKEN_UNAVAILABLE).apply(before,1,UUID.randomUUID()));
        assertEquals(Status.UNKNOWN,before.status());assertThrows(DomainException.class,()->before.deadLetter(1,at.plusSeconds(2)));
    }
    @Test void scopeAndAttemptAreRequired() {
        var wrongTenant=new ChannelSubmissionResult(UUID.randomUUID(),delivery,request,Outcome.ACCEPTED,Reason.PROVIDER_ACCEPTED,at.plusSeconds(1));
        assertEquals(404,assertThrows(DomainException.class,()->wrongTenant.apply(started(Channel.WECHAT_WORK),1,UUID.randomUUID())).status);
        assertThrows(DomainException.class,()->result(Outcome.ACCEPTED,Reason.PROVIDER_ACCEPTED).apply(started(Channel.SMS),1,UUID.randomUUID()));
        var wrongRequest=new ChannelSubmissionResult(tenant,delivery,UUID.randomUUID(),Outcome.UNKNOWN,Reason.RESPONSE_UNCERTAIN,at.plusSeconds(1));
        assertThrows(DomainException.class,()->wrongRequest.apply(started(Channel.WECHAT_WORK),1,UUID.randomUUID()));
    }
    @Test void versionAndTimeAreCheckedEvenForUnknownObservations() {
        var state=started(Channel.WECHAT_WORK);assertThrows(DomainException.class,()->result(Outcome.UNKNOWN,Reason.RESPONSE_UNCERTAIN).apply(state,0,UUID.randomUUID()));
        var early=new ChannelSubmissionResult(tenant,delivery,request,Outcome.UNKNOWN,Reason.TRANSPORT_UNCERTAIN,at.minusSeconds(1));
        assertThrows(IllegalArgumentException.class,()->early.apply(state,1,UUID.randomUUID()));
    }
    @Test void mismatchedOutcomeAndReasonRejected() {
        assertThrows(IllegalArgumentException.class,()->result(Outcome.ACCEPTED,Reason.TRANSPORT_UNCERTAIN));
        assertThrows(IllegalArgumentException.class,()->result(Outcome.UNKNOWN,Reason.PROVIDER_ACCEPTED));
    }
    @Test void lateSubmissionAcceptanceCannotUndoDeliveredEvidence() {
        var state=started(Channel.WECHAT_WORK).record(1,new Receipt(tenant,delivery,request,UUID.randomUUID(),ReceiptStatus.DELIVERED,at),at);
        var after=result(Outcome.ACCEPTED,Reason.PROVIDER_ACCEPTED).apply(state,2,UUID.randomUUID());assertEquals(Status.DELIVERED,after.status());assertEquals(1,after.logicalDeliveredCount());
    }
}
