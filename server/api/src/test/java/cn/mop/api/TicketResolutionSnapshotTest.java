package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.TicketResolutionSnapshot;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TicketResolutionSnapshotTest {
    private static final UUID TENANT=UUID.fromString("74000000-0000-4000-8000-000000000001");
    private static final UUID TICKET=UUID.fromString("74000000-0000-4000-8000-000000000002");
    private static final UUID ENGINEER=UUID.fromString("74000000-0000-4000-8000-000000000003");
    private static final Instant TIME=Instant.parse("2026-10-20T00:00:00Z");
    private static TicketResolutionSnapshot sample() {
        return new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,"synthetic summary","synthetic solution with more than twenty characters");
    }
    @Test void deterministicCanonicalEncodingMatchesIndependentGolden() {
        var s=sample();
        assertEquals("4073fd9765e39485e74b51a584302fb7658d3ea8f0ad64c8dee4c547b296df7f",s.sha256());
        ByteBuffer bytes=ByteBuffer.wrap(s.canonicalBytes());
        int size=bytes.getInt();byte[] version=new byte[size];bytes.get(version);
        assertEquals(TicketResolutionSnapshot.FORMAT,new String(version,StandardCharsets.UTF_8));
        assertArrayEquals(s.canonicalBytes(),sample().canonicalBytes());
    }
    @Test void everyDecisionRelevantFieldChangesHash() {
        var s=sample();
        var changed=new TicketResolutionSnapshot[]{
            new TicketResolutionSnapshot(TICKET,TICKET,7,ENGINEER,TIME,s.ticketSummary(),s.solution()),
            new TicketResolutionSnapshot(TENANT,TENANT,7,ENGINEER,TIME,s.ticketSummary(),s.solution()),
            new TicketResolutionSnapshot(TENANT,TICKET,8,ENGINEER,TIME,s.ticketSummary(),s.solution()),
            new TicketResolutionSnapshot(TENANT,TICKET,7,TENANT,TIME,s.ticketSummary(),s.solution()),
            new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME.plusMillis(1),s.ticketSummary(),s.solution()),
            new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,s.ticketSummary()+"!",s.solution()),
            new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,s.ticketSummary(),s.solution()+"!")
        };
        for(var other:changed)assertNotEquals(s.sha256(),other.sha256());
    }
    @Test void returnsIndependentBytesAndRejectsMismatchedEvidence() {
        var s=sample();byte[] bytes=s.canonicalBytes();bytes[0]=99;
        assertArrayEquals(sample().canonicalBytes(),s.canonicalBytes());
        assertDoesNotThrow(()->s.requireExpectedHash(s.sha256()));
        for(String hash:new String[]{null,"","a".repeat(64),s.sha256().toUpperCase()})
            assertThrows(DomainException.class,()->s.requireExpectedHash(hash));
    }
    @Test void preservesExactWhitespaceAndUnicodeWithoutNormalization() {
        var a=new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,"café","😀".repeat(20)+"\n");
        var b=new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,"cafe\u0301","😀".repeat(20)+"\r\n");
        assertNotEquals(a.sha256(),b.sha256());assertEquals("😀".repeat(20)+"\n",a.solution());
    }
    @Test void rejectsInvalidTextIdentityVersionAndTimestamp() {
        var s=sample();
        assertThrows(DomainException.class,()->new TicketResolutionSnapshot(null,TICKET,7,ENGINEER,TIME,s.ticketSummary(),s.solution()));
        assertThrows(DomainException.class,()->new TicketResolutionSnapshot(TENANT,TICKET,0,ENGINEER,TIME,s.ticketSummary(),s.solution()));
        assertThrows(DomainException.class,()->new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME.plusNanos(1),s.ticketSummary(),s.solution()));
        assertThrows(DomainException.class,()->new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,Instant.MAX,s.ticketSummary(),s.solution()));
        assertThrows(DomainException.class,()->new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,"\ud800",s.solution()));
        assertThrows(DomainException.class,()->new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,"summary","😀".repeat(19)));
    }
    @Test void enforcesExactUtf8DocumentBudget() {
        var base=sample();
        int overhead=base.canonicalBytes().length-base.solution().getBytes(StandardCharsets.UTF_8).length;
        String fits="x".repeat(TicketResolutionSnapshot.MAX_DOCUMENT_BYTES-overhead);
        assertEquals(TicketResolutionSnapshot.MAX_DOCUMENT_BYTES,
            new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,base.ticketSummary(),fits).canonicalBytes().length);
        assertEquals(413,assertThrows(DomainException.class,()->
            new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,base.ticketSummary(),fits+"x")).status);
        assertThrows(DomainException.class,()->new TicketResolutionSnapshot(TENANT,TICKET,7,ENGINEER,TIME,"summary","字".repeat(30_000)));
    }
}
