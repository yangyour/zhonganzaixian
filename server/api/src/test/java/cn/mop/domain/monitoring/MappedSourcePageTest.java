package cn.mop.domain.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MappedSourcePageTest {

    final MappedSourcePage.Window window = new MappedSourcePage.Window("1", "99", 0, 100, 2);

    FetchedEvent event(String id) {
        return new FetchedEvent(id, RawMonitoringEvent.Phase.PROBLEM, Instant.ofEpochSecond(50), "23", new byte[] {
            1,
        });
    }

    @Test
    void explicitWindowRejectsNoncanonicalIdsTimeAndTechnicalLimits() {
        for (String id : List.of("0", "01", "-1", "18446744073709551616")) {
            assertThrows(DomainException.class, () -> new MappedSourcePage.Window(id, "99", 0, 1, 2));
        }
        assertThrows(DomainException.class, () -> new MappedSourcePage.Window("9", "1", 0, 1, 2));
        assertThrows(DomainException.class, () -> new MappedSourcePage.Window("1", "9", 2, 1, 2));
        assertThrows(DomainException.class, () -> new MappedSourcePage.Window("1", "9", 0, 1, 11));
    }

    @Test
    void fetchedPageRejectsDuplicatesRangeAndForgedContinuation() {
        for (var events : List.of(
            List.of(event("2"), event("2")),
            List.of(event("3"), event("2")),
            List.of(event("100"))
        )) {
            assertThrows(IllegalArgumentException.class, () -> new MappedSourcePage.Fetched(window, events, null));
        }
        assertThrows(IllegalArgumentException.class, () ->
            new MappedSourcePage.Fetched(window, List.of(event("2")), "3")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new MappedSourcePage.Fetched(window, List.of(event("2"), event("3")), "5")
        );
        var page = new MappedSourcePage.Fetched(window, List.of(event("2"), event("3")), "4");
        assertEquals("4", page.nextEventId());
        assertThrows(UnsupportedOperationException.class, () -> page.events().clear());
    }

    @Test
    void receiptRejectsScopeOrderingAndContradictoryBusinessResult() {
        UUID mapping = UUID.randomUUID();
        var capture = new CaptureReceipt(
            mapping,
            2,
            "2",
            RawMonitoringEvent.Phase.PROBLEM,
            "RAW_STORED",
            UUID.randomUUID(),
            Instant.now()
        );
        var item = new MappedSourcePage.Item(capture, UUID.randomUUID().toString(), 1L, null, null);
        assertThrows(IllegalArgumentException.class, () ->
            new MappedSourcePage.Receipt(UUID.randomUUID(), UUID.randomUUID(), 2, window, List.of(item), null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new MappedSourcePage.Receipt(UUID.randomUUID(), mapping, 2, window, List.of(item, item), null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new MappedSourcePage.Item(capture, null, null, null, "SOURCE_KEY_CONFLICT")
        );
        assertFalse(
            new MappedSourcePage.Receipt(
                UUID.randomUUID(),
                mapping,
                2,
                window,
                List.of(item),
                null
            ).completeWindowClaimed()
        );
    }
}
