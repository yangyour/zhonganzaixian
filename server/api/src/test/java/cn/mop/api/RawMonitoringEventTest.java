package cn.mop.api;

import cn.mop.domain.monitoring.RawMonitoringEvent;
import cn.mop.domain.monitoring.RawMonitoringEvent.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RawMonitoringEventTest {
    static final UUID POOL=UUID.fromString("59000000-0000-4000-8000-000000000001"),TENANT=UUID.fromString("59000000-0000-4000-8000-000000000002"),ASSET=UUID.fromString("59000000-0000-4000-8000-000000000003");
    static final Instant SOURCE=Instant.parse("2026-10-06T00:00:00.123456789Z"), RECEIVED=SOURCE.plusSeconds(30);
    static RawMonitoringEvent event(String id, Phase phase, Instant source, Instant received, String payload) { return new RawMonitoringEvent(new Key(POOL,id,phase),new Binding(TENANT,ASSET),source,received,payload.getBytes(StandardCharsets.UTF_8)); }
    @Test void preservesSourceAndReceiptSeparatelyWithoutTruncation() {var e=event("合成源-1",Phase.PROBLEM,SOURCE,RECEIVED,"{} ");assertEquals(SOURCE,e.occurredAt());assertEquals(RECEIVED,e.receivedAt());assertEquals(123456789,e.occurredAt().getNano());assertEquals("{} ",new String(e.payload(),StandardCharsets.UTF_8));}
    @Test void sourceClockAheadIsNotRewrittenAsReceiveTime() {var e=event("1",Phase.PROBLEM,RECEIVED,SOURCE,"{}");assertEquals(RECEIVED,e.occurredAt());assertEquals(SOURCE,e.receivedAt());}
    @Test void redeliveryWithNewReceiptTimeRetainsSameFingerprint() {var e=event("1",Phase.PROBLEM,SOURCE,RECEIVED,"{}");var retry=event("1",Phase.PROBLEM,SOURCE,RECEIVED.plusSeconds(90),"{}");assertEquals(Comparison.SAME_CONTENT,e.compare(retry));assertEquals(e.contentFingerprint(),retry.contentFingerprint());}
    @Test void payloadChangeIsConflictNotOverwrite() {var e=event("1",Phase.PROBLEM,SOURCE,RECEIVED,"{}");assertEquals(Comparison.CONFLICT_REQUIRES_REVIEW,e.compare(event("1",Phase.PROBLEM,SOURCE,RECEIVED,"{ }")));assertEquals("{}",new String(e.payload(),StandardCharsets.UTF_8));}
    @Test void sourceTimeChangeIsConflict() {var e=event("1",Phase.PROBLEM,SOURCE,RECEIVED,"{}");assertEquals(Comparison.CONFLICT_REQUIRES_REVIEW,e.compare(event("1",Phase.PROBLEM,SOURCE.plusNanos(1),RECEIVED,"{}")));}
    @Test void mappingChangeIsConflictEvenWhenPayloadMatches() {var e=event("1",Phase.PROBLEM,SOURCE,RECEIVED,"{}");var other=new RawMonitoringEvent(e.key(),new Binding(ASSET,TENANT),SOURCE,RECEIVED,e.payload());assertEquals(Comparison.CONFLICT_REQUIRES_REVIEW,e.compare(other));}
    @Test void eachPhaseAndPoolAndSourceIdHaveSeparateKeys() {var e=event("1",Phase.PROBLEM,SOURCE,RECEIVED,"{}");assertEquals(Comparison.DIFFERENT_KEY,e.compare(event("1",Phase.RECOVERED,SOURCE,RECEIVED,"{}")));assertEquals(Comparison.DIFFERENT_KEY,e.compare(event("2",Phase.PROBLEM,SOURCE,RECEIVED,"{}")));assertEquals(Comparison.DIFFERENT_KEY,e.compare(new RawMonitoringEvent(new Key(ASSET,"1",Phase.PROBLEM),e.binding(),SOURCE,RECEIVED,e.payload())));}
    @Test void immutablePayloadInputAndOutput() {byte[] raw={1,2,3};var e=new RawMonitoringEvent(new Key(POOL,"1",Phase.PROBLEM),new Binding(TENANT,ASSET),SOURCE,RECEIVED,raw);String hash=e.payloadSha256();raw[0]=9;e.payload()[0]=8;assertArrayEquals(new byte[]{1,2,3},e.payload());assertEquals(hash,e.payloadSha256());}
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" "," leading","trailing ","a\nb","a\u0000b","\ud800"})
    void rejectsInvalidSourceIds(String id) {assertThrows(IllegalArgumentException.class,()->new Key(POOL,id,Phase.PROBLEM));}
    @Test void rejectsOversizedOrEmptyPayloadAndMissingScope() {assertThrows(IllegalArgumentException.class,()->new Key(POOL,"x".repeat(257),Phase.PROBLEM));assertThrows(IllegalArgumentException.class,()->new Binding(null,ASSET));assertThrows(IllegalArgumentException.class,()->new RawMonitoringEvent(new Key(POOL,"1",Phase.PROBLEM),new Binding(TENANT,ASSET),SOURCE,RECEIVED,new byte[0]));assertThrows(IllegalArgumentException.class,()->new RawMonitoringEvent(new Key(POOL,"1",Phase.PROBLEM),new Binding(TENANT,ASSET),SOURCE,RECEIVED,new byte[RawMonitoringEvent.MAX_PAYLOAD_BYTES+1]));}
    @Test void logsCannotExposePayloadOrSourceIdentifier() {var e=event("DO_NOT_ECHO_ID",Phase.PROBLEM,SOURCE,RECEIVED,"DO_NOT_ECHO_BODY");assertFalse(e.toString().contains("DO_NOT_ECHO"));assertTrue(e.toString().contains("not durably accepted"));}
    @Test void sameSourceTimeAndBytesAreDeterministicAndNamespaced() {var e=event("1|PROBLEM",Phase.PROBLEM,SOURCE,RECEIVED,"x");assertEquals(e.contentFingerprint(),event("1|PROBLEM",Phase.PROBLEM,SOURCE,RECEIVED,"x").contentFingerprint());assertNotEquals(e.contentFingerprint(),event("1",Phase.PROBLEM,SOURCE,RECEIVED,"PROBLEM|x").contentFingerprint());}
}
