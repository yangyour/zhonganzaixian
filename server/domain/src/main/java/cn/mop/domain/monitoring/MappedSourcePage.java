package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.CaptureReceipt;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.FetchedEvent;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit bounded source window; a returned page is not an atomic upstream snapshot or a global checkpoint. */
public final class MappedSourcePage {

    private static final BigInteger MAX_ID = new BigInteger("18446744073709551615");
    public static final int MAX_PAGE_SIZE = 10;
    public static final long MAX_CLOCK = 253402300799L;

    private MappedSourcePage() {}

    public record Window(String firstEventId, String lastEventId, long fromClock, long throughClock, int pageSize) {
        public Window {
            if (
                !numeric(firstEventId) ||
                !numeric(lastEventId) ||
                new BigInteger(firstEventId).compareTo(new BigInteger(lastEventId)) > 0 ||
                fromClock < 0 ||
                throughClock < fromClock ||
                throughClock > MAX_CLOCK ||
                pageSize < 1 ||
                pageSize > MAX_PAGE_SIZE
            ) {
                throw new DomainException(
                    400,
                    "INVALID_SOURCE_PAGE_WINDOW",
                    "需要明确有界的来源ID、秒时间窗口及1至10条页大小"
                );
            }
        }
    }

    public record Fetched(Window window, List<FetchedEvent> events, String nextEventId) {
        public Fetched {
            if (window == null || events == null || events.size() > window.pageSize()) {
                throw invalid();
            }
            events = List.copyOf(events);
            BigInteger previous = null;
            long bytes = 0;
            for (var event : events) {
                if (event == null || !numeric(event.sourceEventId())) {
                    throw invalid();
                }
                BigInteger id = new BigInteger(event.sourceEventId());
                if (
                    id.compareTo(new BigInteger(window.firstEventId())) < 0 ||
                    id.compareTo(new BigInteger(window.lastEventId())) > 0 ||
                    (previous != null && id.compareTo(previous) <= 0) ||
                    event.occurredAt().getEpochSecond() < window.fromClock() ||
                    event.occurredAt().getEpochSecond() > window.throughClock()
                ) {
                    throw invalid();
                }
                previous = id;
                bytes += event.payload().length;
            }
            if (
                bytes > 65536 ||
                (nextEventId != null &&
                    (!numeric(nextEventId) ||
                        events.size() != window.pageSize() ||
                        previous == null ||
                        !previous.add(BigInteger.ONE).toString().equals(nextEventId) ||
                        new BigInteger(nextEventId).compareTo(new BigInteger(window.lastEventId())) > 0))
            ) {
                throw invalid();
            }
        }
    }

    public record Item(
        CaptureReceipt capture,
        String eventId,
        Long eventVersion,
        String ticketId,
        String pendingReason
    ) {
        public Item {
            if (
                capture == null ||
                (eventId == null) != (eventVersion == null) ||
                (eventVersion != null && eventVersion < 1) ||
                (eventId != null && pendingReason != null) ||
                (eventId != null && !uuid(eventId)) ||
                (ticketId != null && !uuid(ticketId)) ||
                (eventId == null && (ticketId != null || pendingReason == null)) ||
                (eventId != null &&
                    (capture.phase() != RawMonitoringEvent.Phase.PROBLEM ||
                        capture.outcome().startsWith("CONFLICT"))) ||
                (pendingReason != null &&
                    !Set.of(
                        "SOURCE_KEY_CONFLICT",
                        "RECOVERY_OBSERVATION_REQUIRED",
                        "SOURCE_SEVERITY_UNCLASSIFIED",
                        "SOURCE_CLOCK_AHEAD",
                        "EVENT_SIGNAL_OUT_OF_ORDER"
                    ).contains(pendingReason)) ||
                capture.outcome().startsWith("CONFLICT") != "SOURCE_KEY_CONFLICT".equals(pendingReason) ||
                (!capture.outcome().startsWith("CONFLICT") &&
                    eventId == null &&
                    "RECOVERY_OBSERVATION_REQUIRED".equals(pendingReason) !=
                        (capture.phase() == RawMonitoringEvent.Phase.RECOVERED))
            ) {
                throw invalid();
            }
        }

        public boolean businessProcessed() {
            return eventId != null;
        }
    }

    public record Receipt(
        UUID tenantId,
        UUID mappingId,
        long mappingVersion,
        Window window,
        List<Item> items,
        String nextEventId
    ) {
        public Receipt {
            if (
                tenantId == null ||
                mappingId == null ||
                mappingVersion < 1 ||
                window == null ||
                items == null ||
                items.size() > window.pageSize()
            ) {
                throw invalid();
            }
            items = List.copyOf(items);
            BigInteger previous = null;
            for (var item : items) {
                var capture = item.capture();
                if (
                    !mappingId.equals(capture.mappingId()) ||
                    mappingVersion != capture.mappingVersion() ||
                    !numeric(capture.sourceEventId())
                ) {
                    throw invalid();
                }
                var id = new BigInteger(capture.sourceEventId());
                if (
                    id.compareTo(new BigInteger(window.firstEventId())) < 0 ||
                    id.compareTo(new BigInteger(window.lastEventId())) > 0 ||
                    (previous != null && id.compareTo(previous) <= 0)
                ) {
                    throw invalid();
                }
                previous = id;
            }
            if (
                nextEventId != null &&
                (!numeric(nextEventId) ||
                    items.size() != window.pageSize() ||
                    previous == null ||
                    !previous.add(BigInteger.ONE).toString().equals(nextEventId) ||
                    new BigInteger(nextEventId).compareTo(new BigInteger(window.lastEventId())) > 0)
            ) {
                throw invalid();
            }
        }

        public boolean completeWindowClaimed() {
            return false;
        }
    }

    public static boolean numeric(String value) {
        return value != null && value.matches("[1-9][0-9]{0,19}") && new BigInteger(value).compareTo(MAX_ID) <= 0;
    }

    private static boolean uuid(String value) {
        return value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid bounded mapped-source page; source contents redacted");
    }
}
