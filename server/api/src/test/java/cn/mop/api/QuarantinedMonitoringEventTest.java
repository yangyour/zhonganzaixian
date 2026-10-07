package cn.mop.api;
import cn.mop.domain.monitoring.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class QuarantinedMonitoringEventTest {
    static final UUID POOL=UUID.fromString("11111111-1111-4111-8111-111111111111"),TENANT=UUID.fromString("22222222-2222-4222-8222-222222222222"),ASSET=UUID.fromString("33333333-3333-4333-8333-333333333333");
    QuarantinedMonitoringEvent event(QuarantinedMonitoringEvent.Claims claims,QuarantinedMonitoringEvent.Reason reason,Instant source,Instant receipt,byte[] bytes){return new QuarantinedMonitoringEvent(new RawMonitoringEvent.Key(POOL,"DO_NOT_ECHO_SOURCE",RawMonitoringEvent.Phase.PROBLEM),claims,reason,source,receipt,bytes);}
    QuarantinedMonitoringEvent event(){return event(new QuarantinedMonitoringEvent.Claims(null,null),QuarantinedMonitoringEvent.Reason.UNKNOWN_MAPPING,Instant.ofEpochSecond(-1,999999999),Instant.EPOCH,new byte[]{0,-1,32});}
    @Test void missingAndPartialClaimsCanBeHeldWithoutAuthorizingThem(){for(var claims:new QuarantinedMonitoringEvent.Claims[]{new QuarantinedMonitoringEvent.Claims(null,null),new QuarantinedMonitoringEvent.Claims(TENANT,null),new QuarantinedMonitoringEvent.Claims(null,ASSET),new QuarantinedMonitoringEvent.Claims(TENANT,ASSET)}){var e=event(claims,QuarantinedMonitoringEvent.Reason.UNKNOWN_MAPPING,Instant.MIN,Instant.MAX,new byte[]{1});assertEquals(claims,e.claims());assertFalse(e.dispatchAllowed());assertFalse(e.sourceAuthorized());}}
    @Test void receiptTimeDoesNotChangeCandidate(){var e=event();assertEquals(e.candidateFingerprint(),event(e.claims(),e.reason(),e.occurredAt(),Instant.MAX,e.payload()).candidateFingerprint());}
    @Test void claimsReasonBytesAndSourceTimeAreAllCandidateFacts(){var e=event();for(var changed:new QuarantinedMonitoringEvent[]{event(new QuarantinedMonitoringEvent.Claims(TENANT,null),e.reason(),e.occurredAt(),e.receivedAt(),e.payload()),event(e.claims(),QuarantinedMonitoringEvent.Reason.SOURCE_KEY_CONFLICT,e.occurredAt(),e.receivedAt(),e.payload()),event(e.claims(),e.reason(),e.occurredAt().plusNanos(1),e.receivedAt(),e.payload()),event(e.claims(),e.reason(),e.occurredAt(),e.receivedAt(),new byte[]{0,-1,32,32})})assertNotEquals(e.candidateFingerprint(),changed.candidateFingerprint());}
    @Test void payloadIsDefensivelyCopied(){byte[] p={1};var e=event(new QuarantinedMonitoringEvent.Claims(null,null),QuarantinedMonitoringEvent.Reason.UNKNOWN_MAPPING,Instant.EPOCH,Instant.EPOCH,p);p[0]=2;e.payload()[0]=3;assertArrayEquals(new byte[]{1},e.payload());}
    @Test void sourceKeyFieldsAreNotInterchangeable(){var e=event();for(var key:new RawMonitoringEvent.Key[]{new RawMonitoringEvent.Key(TENANT,e.key().sourceEventId(),e.key().phase()),new RawMonitoringEvent.Key(POOL,"other",e.key().phase()),new RawMonitoringEvent.Key(POOL,e.key().sourceEventId(),RawMonitoringEvent.Phase.RECOVERED)})assertNotEquals(e.candidateFingerprint(),new QuarantinedMonitoringEvent(key,e.claims(),e.reason(),e.occurredAt(),e.receivedAt(),e.payload()).candidateFingerprint());}
    @Test void nullClaimsDifferFromAnyUuid(){var e=event();assertNotEquals(e.candidateFingerprint(),event(new QuarantinedMonitoringEvent.Claims(new UUID(0,0),null),e.reason(),e.occurredAt(),e.receivedAt(),e.payload()).candidateFingerprint());}
    @Test void invalidInputsFailWithoutPayloadDetails(){var e=event();assertThrows(IllegalArgumentException.class,()->new QuarantinedMonitoringEvent(null,e.claims(),e.reason(),e.occurredAt(),e.receivedAt(),e.payload()));assertThrows(IllegalArgumentException.class,()->event(null,e.reason(),e.occurredAt(),e.receivedAt(),e.payload()));assertThrows(IllegalArgumentException.class,()->event(e.claims(),null,e.occurredAt(),e.receivedAt(),e.payload()));for(byte[] p:new byte[][]{null,new byte[0],new byte[RawMonitoringEvent.MAX_PAYLOAD_BYTES+1]})assertThrows(IllegalArgumentException.class,()->event(e.claims(),e.reason(),e.occurredAt(),e.receivedAt(),p));}
    @Test void logsNeverExposeClaimsOrSourceContent(){var e=event(new QuarantinedMonitoringEvent.Claims(TENANT,ASSET),QuarantinedMonitoringEvent.Reason.CROSS_TENANT_CLAIM,Instant.EPOCH,Instant.EPOCH,"DO_NOT_ECHO_BODY".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertFalse(e.toString().contains("DO_NOT_ECHO"));assertFalse(e.claims().toString().contains(TENANT.toString()));assertFalse(e.claims().toString().contains(ASSET.toString()));}
    @Test void independentNullableUtf8GoldenVectorLocksEncoding(){
        // Independent Python struct/hashlib vector, with null=-1, UTF-8 lengths and pre-epoch nanoseconds.
        var e=new QuarantinedMonitoringEvent(new RawMonitoringEvent.Key(POOL,"合成隔离-😀",RawMonitoringEvent.Phase.PROBLEM),new QuarantinedMonitoringEvent.Claims(null,ASSET),QuarantinedMonitoringEvent.Reason.UNKNOWN_MAPPING,Instant.ofEpochSecond(-1,999999999),Instant.EPOCH,"{\"温度\":25} \n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("129163423ff3049f073e433e8f913355e69a2283371d66ac0c15a96948b8a47e",e.payloadSha256());
        assertEquals("891a1274d24f4807df903102c5fd94afb1b2db415b118576885804e8c8cc14a6",e.candidateFingerprint());
    }

}
