package cn.mop.domain.dashboard;
import cn.mop.domain.dashboard.NotificationAcceptance.*;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NotificationAcceptanceTest {
 static final Instant NOW=Instant.parse("2026-10-24T00:00:00Z");static final Access ACCESS=new Access(UUID.randomUUID(),UUID.randomUUID(),1);
 static Entry row(String channel,String state){var at=NOW.minusSeconds(60);return new Entry(UUID.randomUUID(),channel,state,at,state.equals("QUEUED")?null:at.plusSeconds(1),Set.of("QUEUED","PROCESSING").contains(state)?null:at.plusSeconds(2));}
 @Test void countsAcceptanceSeparatelyAcrossActualSubmissionChannels(){var s=NotificationAcceptance.evaluate(ACCESS,NOW,List.of(row("EMAIL","ACCEPTED"),row("WECHAT_WORK","UNKNOWN"),row("EMAIL","FAILED")));assertEquals(3,s.counts().total());assertEquals(1,s.counts().accepted());assertEquals(1,s.counts().unknown());assertEquals(2,s.byChannel().get("EMAIL").total());}
 @Test void allLocalStatesAreCountedWithoutDeliveryClaim(){var states=List.of("QUEUED","PROCESSING","ACCEPTED","FAILED","UNKNOWN","CANCELLED");var s=NotificationAcceptance.evaluate(ACCESS,NOW,states.stream().map(x->row("EMAIL",x)).toList());assertEquals(new Counts(6,1,1,1,1,1,1),s.counts());}
 @Test void emptyCohortIsNotFailure(){var s=NotificationAcceptance.evaluate(ACCESS,NOW,List.of());assertEquals(0,s.counts().total());assertEquals(0,s.byChannel().get("WECHAT_WORK").accepted());assertEquals(NOW.minusSeconds(86400),s.windowStart());}
 @Test void duplicateSameChannelFailsButDistinctChannelIdentityMayCoincide(){var r=row("EMAIL","ACCEPTED");assertThrows(RuntimeException.class,()->NotificationAcceptance.evaluate(ACCESS,NOW,List.of(r,r)));assertEquals(2,NotificationAcceptance.evaluate(ACCESS,NOW,List.of(r,new Entry(r.id(),"WECHAT_WORK",r.status(),r.createdAt(),r.startedAt(),r.finishedAt()))).counts().total());}
 @Test void rejectsOutsideCohortAndFutureCompletion(){assertThrows(RuntimeException.class,()->NotificationAcceptance.evaluate(ACCESS,NOW,List.of(new Entry(UUID.randomUUID(),"EMAIL","QUEUED",NOW,null,null))));assertThrows(RuntimeException.class,()->NotificationAcceptance.evaluate(ACCESS,NOW,List.of(new Entry(UUID.randomUUID(),"EMAIL","QUEUED",NOW.minusSeconds(86401),null,null))));assertThrows(RuntimeException.class,()->NotificationAcceptance.evaluate(ACCESS,NOW,List.of(new Entry(UUID.randomUUID(),"EMAIL","ACCEPTED",NOW.minusSeconds(2),NOW.minusSeconds(1),NOW.plusSeconds(1)))));}
 @Test void exactLeftBoundaryIsIncluded(){assertEquals(1,NotificationAcceptance.evaluate(ACCESS,NOW,List.of(new Entry(UUID.randomUUID(),"EMAIL","QUEUED",NOW.minusSeconds(86400),null,null))).counts().total());}
 @Test void deliveredDeclarationsAndUnfinishedTerminalRowsAreRejected(){assertThrows(RuntimeException.class,()->row("EMAIL","DELIVERED"));assertThrows(RuntimeException.class,()->new Entry(UUID.randomUUID(),"EMAIL","ACCEPTED",NOW,null,null));}
}
