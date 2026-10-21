package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic history only. Neither persisted database state nor authenticated provider evidence. */
class DeliveryReceiptSnapshotCodecTest {
    final DeliveryReceiptSnapshotCodec codec = new DeliveryReceiptSnapshotCodec();
    static UUID id(int n) { return UUID.fromString("7f000000-0000-4000-8000-" + String.format("%012d", n)); }
    final Instant at = Instant.parse("2026-10-21T00:00:00.123456789Z");
    final Deduplication key = new Deduplication(id(3), id(4), Channel.WEBHOOK, id(5), "OPENED");
    final DeliveryReceiptSnapshotCodec.Scope scope = new DeliveryReceiptSnapshotCodec.Scope(id(1), id(2), key);
    DeliveryReceiptState queued() { return DeliveryReceiptState.queued(id(1), id(2), key, at); }
    Receipt receipt(int request, int receipt, ReceiptStatus status, int nanos) { return new Receipt(id(1), id(2), id(request), id(receipt), status, at.plusNanos(nanos)); }
    DeliveryReceiptState vector() {
        var s = queued().start(0, id(6), at.plusNanos(1));
        s = s.record(1, receipt(6, 8, ReceiptStatus.ACCEPTED, 2), at.plusNanos(3));
        s = s.record(2, receipt(6, 9, ReceiptStatus.FAILED, 4), at.plusNanos(5));
        s = s.deadLetter(3, at.plusNanos(6)).start(4, id(7), at.plusNanos(7));
        return s.record(5, receipt(6, 10, ReceiptStatus.DELIVERED, 2), at.plusNanos(8));
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    DeliveryReceiptState decode(byte[] bytes) throws Exception { return codec.decode(bytes, hash(bytes), scope); }
    DeliveryReceiptState roundTrip(DeliveryReceiptState s) { var d = codec.encode(s); return codec.decode(d.bytes(), d.sha256(), scope); }
    void same(DeliveryReceiptState a, DeliveryReceiptState b) {
        assertEquals(a.tenantId(), b.tenantId()); assertEquals(a.deliveryId(), b.deliveryId()); assertEquals(a.deduplication(), b.deduplication());
        assertEquals(a.queuedAt(), b.queuedAt()); assertEquals(a.observedAt(), b.observedAt()); assertEquals(a.version(), b.version());
        assertEquals(a.transitions(), b.transitions()); assertEquals(a.attempts(), b.attempts()); assertEquals(a.receipts(), b.receipts());
        assertEquals(a.status(), b.status()); assertEquals(a.firstDeliveredAt(), b.firstDeliveredAt()); assertEquals(a.logicalDeliveredCount(), b.logicalDeliveredCount());
        assertFalse(b.providerReceiptVerified()); assertFalse(b.automaticSendEnabled()); assertNull(b.effectiveRetryBudget()); assertNull(b.effectiveSmsQuota());
    }
    @Test void everyStatusRoundTripsWithoutChangingReceiptMeaning() {
        var unknown=queued().start(0,id(6),at);var accepted=unknown.record(1,receipt(6,8,ReceiptStatus.ACCEPTED,0),at);
        var failed=accepted.record(2,receipt(6,9,ReceiptStatus.FAILED,0),at);var dead=failed.deadLetter(3,at);
        for(var s:List.of(queued(),unknown,accepted,failed,dead,vector()))same(s,roundTrip(s));
    }
    @Test void orderedTransitionsPreserveDeadLetterAndDistinctObservationTimes() {
        var s=vector();assertEquals(6,s.transitions().size());assertEquals(s.version(),s.transitions().size());
        var record=(ReceiptRecorded)s.transitions().get(1);assertEquals(at.plusNanos(2),record.receipt().occurredAt());assertEquals(at.plusNanos(3),record.observedAt());
        assertEquals(new DeadLettered(at.plusNanos(6)),s.transitions().get(3));same(s,roundTrip(s));
    }
    @Test void exactReceiptReplayDoesNotCreateAnotherTransitionOrAdvanceObservation() {
        var s=roundTrip(vector());var same=s.record(0,receipt(6,8,ReceiptStatus.ACCEPTED,2),at.plusSeconds(100));
        assertSame(s,same);assertEquals(6,same.transitions().size());assertEquals(at.plusNanos(8),same.observedAt());
        assertThrows(UnsupportedOperationException.class,()->same.transitions().clear());
    }
    @Test void unknownRestoresAsUnknownNotRetryableQueue() {
        var s=roundTrip(queued().start(0,id(6),at));assertEquals(Status.UNKNOWN,s.status());assertEquals(0,s.logicalDeliveredCount());
        assertThrows(DomainException.class,()->s.start(s.version(),id(7),at.plusSeconds(300)));assertNull(s.effectiveRetryBudget());
    }
    @Test void restoredDeadLetterRequiresNewAttemptAndLateOldDeliveryStopsReplay() {
        var s=queued().start(0,id(6),at).record(1,receipt(6,8,ReceiptStatus.FAILED,0),at).deadLetter(2,at);
        var next=roundTrip(s).start(3,id(7),at.plusNanos(1));assertEquals(Status.UNKNOWN,next.status());
        var delivered=roundTrip(next).record(4,receipt(6,9,ReceiptStatus.DELIVERED,0),at.plusNanos(2));
        var restored=roundTrip(delivered);assertEquals(1,restored.logicalDeliveredCount());assertEquals(Status.DELIVERED,restored.status());
        assertThrows(DomainException.class,()->restored.start(restored.version(),id(11),at.plusSeconds(1)));
    }
    @Test void allChannelsAndAllExpectedScopeFieldsAreBound() {
        for(var channel:Channel.values()){
            var k=new Deduplication(id(3),id(4),channel,id(5),"OPENED");var s=DeliveryReceiptState.queued(id(1),id(2),k,at);var d=codec.encode(s);
            same(s,codec.decode(d.bytes(),d.sha256(),new DeliveryReceiptSnapshotCodec.Scope(id(1),id(2),k)));
        }
        var d=codec.encode(vector());
        var keys=List.of(new Deduplication(id(99),id(4),Channel.WEBHOOK,id(5),"OPENED"),new Deduplication(id(3),id(99),Channel.WEBHOOK,id(5),"OPENED"),new Deduplication(id(3),id(4),Channel.EMAIL,id(5),"OPENED"),new Deduplication(id(3),id(4),Channel.WEBHOOK,id(99),"OPENED"),new Deduplication(id(3),id(4),Channel.WEBHOOK,id(5),"CLOSED"));
        for(var k:keys)assertThrows(DomainException.class,()->codec.decode(d.bytes(),d.sha256(),new DeliveryReceiptSnapshotCodec.Scope(id(1),id(2),k)));
        for(var other:List.of(new DeliveryReceiptSnapshotCodec.Scope(id(99),id(2),key),new DeliveryReceiptSnapshotCodec.Scope(id(1),id(99),key)))assertThrows(DomainException.class,()->codec.decode(d.bytes(),d.sha256(),other));
    }
    @Test void checksumAndDocumentCopiesAreEnforced() throws Exception {
        var d=codec.encode(vector());var bytes=d.bytes();bytes[bytes.length-1]^=1;
        assertThrows(DomainException.class,()->codec.decode(bytes,d.sha256(),scope));assertThrows(DomainException.class,()->codec.decode(d.bytes(),d.sha256().toUpperCase(Locale.ROOT),scope));
        assertThrows(DomainException.class,()->new DeliveryReceiptSnapshotCodec.Document(bytes,d.sha256()));
        var original=d.bytes();var copy=new DeliveryReceiptSnapshotCodec.Document(original,hash(original));original[0]^=1;assertArrayEquals(d.bytes(),copy.bytes());
        var returned=copy.bytes();returned[0]^=1;assertArrayEquals(d.bytes(),copy.bytes());
    }
    @Test void everyTruncationAndTrailingByteRejectEvenWithMatchingChecksum() throws Exception {
        byte[] bytes=codec.encode(vector()).bytes();for(int i=0;i<bytes.length;i++){var shortBytes=Arrays.copyOf(bytes,i);assertThrows(DomainException.class,()->decode(shortBytes));}
        assertThrows(DomainException.class,()->decode(Arrays.copyOf(bytes,bytes.length+1)));
    }
    interface Writer { void write(DataOutputStream out) throws Exception; }
    static void uuid(DataOutputStream out,UUID value)throws IOException{out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    static void instant(DataOutputStream out,Instant value)throws IOException{out.writeLong(value.getEpochSecond());out.writeInt(value.getNano());}
    byte[] manual(String format,Instant queued,long version,int count,Writer events)throws Exception{
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
            out.writeUTF(format);uuid(out,id(1));uuid(out,id(2));uuid(out,id(3));uuid(out,id(4));out.writeUTF("WEBHOOK");uuid(out,id(5));out.writeUTF("OPENED");instant(out,queued);out.writeLong(version);out.writeInt(count);events.write(out);out.flush();return bytes.toByteArray();
        }
    }
    void start(DataOutputStream out,int request,Instant t)throws IOException{out.writeByte(1);uuid(out,id(request));instant(out,t);}
    void recorded(DataOutputStream out,int request,int receipt,String status,Instant occurred,Instant observed)throws IOException{out.writeByte(3);uuid(out,id(request));uuid(out,id(receipt));out.writeUTF(status);instant(out,occurred);instant(out,observed);}
    @Test void unknownFormatTagsVersionsAndCountsRejectBeforeAllocatingHistory() throws Exception {
        assertThrows(DomainException.class,()->decode(manual("MOP_DELIVERY_RECEIPT_SNAPSHOT_V2",at,0,0,o->{})));
        for(int n:new int[]{-1,12001,Integer.MAX_VALUE})assertThrows(DomainException.class,()->decode(manual(DeliveryReceiptSnapshotCodec.FORMAT,at,n,n,o->{})));
        assertThrows(DomainException.class,()->decode(manual(DeliveryReceiptSnapshotCodec.FORMAT,at,1,0,o->{})));
        assertThrows(DomainException.class,()->decode(manual(DeliveryReceiptSnapshotCodec.FORMAT,at,1,1,o->o.writeByte(99))));
    }
    @Test void forgedStateTransitionsCannotBeTrustedJustBecauseTheirChecksumMatches() throws Exception {
        var invalidEvents=List.<Writer>of(o->{start(o,6,at);start(o,7,at);},o->{o.writeByte(2);instant(o,at);start(o,6,at);},o->{start(o,6,at);recorded(o,7,8,"DELIVERED",at,at);},o->{start(o,6,at);recorded(o,6,8,"DELIVERED",at.minusNanos(1),at);},o->{start(o,6,at);recorded(o,6,8,"ACCEPTED",at.plusNanos(1),at);},o->{start(o,6,at.plusNanos(1));recorded(o,6,8,"ACCEPTED",at.plusNanos(1),at);},o->{start(o,6,at);recorded(o,6,8,"SENT",at,at);});
        for(var events:invalidEvents)assertThrows(DomainException.class,()->decode(manual(DeliveryReceiptSnapshotCodec.FORMAT,at,2,2,events)));
    }
    @Test void duplicateEncodedReceiptCannotInflateVersionOrCreateAnotherDelivery() {
        assertThrows(DomainException.class,()->decode(manual(DeliveryReceiptSnapshotCodec.FORMAT,at,3,3,o->{start(o,6,at);recorded(o,6,8,"DELIVERED",at,at);recorded(o,6,8,"DELIVERED",at,at.plusNanos(1));})));
        assertThrows(DomainException.class,()->decode(manual(DeliveryReceiptSnapshotCodec.FORMAT,at,3,3,o->{start(o,6,at);recorded(o,6,8,"ACCEPTED",at,at);recorded(o,6,8,"DELIVERED",at,at);})));
    }
    @Test void nanosecondNormalizationAndOutOfRangeDatesAreRejected() throws Exception {
        var d=codec.encode(queued());for(int n:new int[]{-1,1_000_000_000}){byte[] b=d.bytes();int index=b.length-16;java.nio.ByteBuffer.wrap(b,index,4).putInt(n);assertThrows(DomainException.class,()->decode(b));}
        for(var t:List.of(Instant.parse("0000-12-31T23:59:59Z"),Instant.parse("+10000-01-01T00:00:00Z")))assertThrows(DomainException.class,()->decode(manual(DeliveryReceiptSnapshotCodec.FORMAT,t,0,0,o->{})));
        for(var t:List.of(Instant.parse("0001-01-01T00:00:00Z"),Instant.parse("9999-12-31T23:59:59.999999999Z"))){var q=DeliveryReceiptState.queued(id(1),id(2),key,t);same(q,roundTrip(q));}
    }
    @Test void noncanonicalModifiedUtfIsNotAnAlternativeValidEncoding() throws Exception {
        byte[] original=codec.encode(queued()).bytes();byte[] marker=new byte[]{0,6,'O','P','E','N','E','D'};int position=-1;
        for(int i=0;i<=original.length-marker.length;i++)if(Arrays.equals(Arrays.copyOfRange(original,i,i+marker.length),marker)){position=i;break;}
        assertTrue(position>=0);var bytes=new ByteArrayOutputStream();bytes.write(original,0,position);bytes.write(new byte[]{0,7,(byte)0xc1,(byte)0x8f,'P','E','N','E','D'});bytes.write(original,position+marker.length,original.length-position-marker.length);
        assertThrows(DomainException.class,()->decode(bytes.toByteArray()));
    }
    @Test void nullOversizedAndMissingScopeFailWithSafeStorageError() {
        for(byte[] bytes:new byte[][]{null,new byte[0],new byte[DeliveryReceiptSnapshotCodec.MAX_BYTES+1]})assertThrows(DomainException.class,()->codec.decode(bytes,"0".repeat(64),scope));
        assertThrows(DomainException.class,()->codec.encode(null));var d=codec.encode(vector());assertThrows(DomainException.class,()->codec.decode(d.bytes(),d.sha256(),null));
        var e=assertThrows(DomainException.class,()->codec.decode("PRIVATE_UNTRUSTED_RECEIPT".getBytes(),"0".repeat(64),scope));assertEquals(503,e.status);assertEquals("DELIVERY_SNAPSHOT_INVALID",e.code);assertFalse(e.getMessage().contains("PRIVATE_UNTRUSTED_RECEIPT"));assertNull(e.getCause());
    }
    @Test void fullTechnicalHistoryFitsAndRestoresWithoutTruncation() {
        var s=queued();int nextReceipt=20000;
        for(int i=0;i<DeliveryReceiptState.MAX_ATTEMPTS;i++){
            UUID request=new UUID(1,i+1);s=s.start(s.version(),request,at);
            int receipts=i==DeliveryReceiptState.MAX_ATTEMPTS-1?DeliveryReceiptState.MAX_RECEIPTS-(DeliveryReceiptState.MAX_ATTEMPTS-1):1;
            for(int j=0;j<receipts;j++)s=s.record(s.version(),new Receipt(id(1),id(2),request,new UUID(2,nextReceipt++),ReceiptStatus.FAILED,at),at);
            s=s.deadLetter(s.version(),at);
        }
        assertEquals(12000,s.transitions().size());var d=codec.encode(s);assertTrue(d.bytes().length<DeliveryReceiptSnapshotCodec.MAX_BYTES);same(s,codec.decode(d.bytes(),d.sha256(),scope));
    }
    @Test void independentPythonVectorMatchesEveryByteAndDigest() throws Exception {
        Path path=Path.of("../../fixtures/delivery-receipt-snapshot-vector-20261021.json");if(!Files.isRegularFile(path))path=Path.of("fixtures/delivery-receipt-snapshot-vector-20261021.json");
        var fixture=new ObjectMapper().readTree(Files.readString(path));var d=codec.encode(vector());assertEquals(426,d.bytes().length);assertEquals(fixture.get("sha256").asText(),d.sha256());assertArrayEquals(HexFormat.of().parseHex(fixture.get("snapshot_hex").asText()),d.bytes());same(vector(),codec.decode(d.bytes(),d.sha256(),scope));
    }
}
