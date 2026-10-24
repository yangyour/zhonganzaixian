package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryJournalReader.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The attention endpoint retains current authority and complete-population boundaries of the existing journal. */
class DeliveryAttentionControllerTest {

    private final DeliveryJournalControllerTest fixture = new DeliveryJournalControllerTest();

    @Test
    void wrongFilterUnknownKeysDuplicatesAndHalfCursorNeverReachReader() {
        for (var key : List.of("status", "tenant_id", "before", "page_size")) {
            var request = fixture.request();
            request.addParameter(
                key,
                key.equals("status") ? "DELIVERED" : key.equals("before") ? fixture.at.toString() : "bad"
            );
            assertEquals(
                400,
                assertThrows(DomainException.class, () -> fixture.controller(true).attention(request)).status
            );
        }
        var duplicate = fixture.request();
        duplicate.addParameter("status", "FAILED", "UNKNOWN");
        assertEquals(
            400,
            assertThrows(DomainException.class, () -> fixture.controller(true).attention(duplicate)).status
        );
        verifyNoInteractions(fixture.reader);
    }

    @Test
    void wholePopulationMetadataAndNoSendFlagsAreReturnedAfterAuthorityRecheck() {
        var request = fixture.request();
        request.addParameter("status", "FAILED");
        when(
            fixture.reader.attention(any(), isNull(), eq(AttentionStatus.FAILED), isNull(), eq(10))
        ).thenReturn(
            new AttentionPage(
                new Page(fixture.scope(), fixture.at, 10, 1, List.of(fixture.entry("FAILED")), null),
                AttentionStatus.FAILED,
                15
            )
        );
        assertNotNull(fixture.controller(true).attention(request));
        verify(fixture.platform, times(2)).membership(fixture.actor, fixture.tenant);
    }

    @Test
    void mismatchedCurrentStatusCannotBePresentedAsAFailedQueue() {
        var request = fixture.request();
        request.addParameter("status", "FAILED");
        when(fixture.reader.attention(any(), any(), any(), any(), anyInt())).thenReturn(
            new AttentionPage(
                new Page(fixture.scope(), fixture.at, 10, 1, List.of(fixture.entry("DELIVERED")), null),
                AttentionStatus.FAILED,
                15
            )
        );
        assertEquals(
            503,
            assertThrows(DomainException.class, () -> fixture.controller(true).attention(request)).status
        );
    }
}
