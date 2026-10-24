package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.operations.EventOperationalMetrics;
import cn.mop.domain.operations.EventOperationalMetricsReader.Sample;
import cn.mop.domain.operations.EventOperationalMetricsReader.Severity;
import cn.mop.domain.sla.MonitoringRecoveryClockEvidence.SourceTime;
import cn.mop.domain.sla.MonitoringSlaLatency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Complete monitoring cohorts, not ticket timestamps or caller-supplied recovery flags. */
class MonitoringSlaLatencyTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ASSET = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    private MonitoringSlaLatency.Event event(int id, Instant ack, String handling, String monitor, Instant recovery) {
        var sample = new Sample(
            new UUID(0, id),
            ASSET,
            2,
            "SYNTHETIC",
            Severity.HIGH,
            monitor,
            handling,
            "NONE",
            START,
            ack,
            END
        );
        return new MonitoringSlaLatency.Event(
            sample,
            recovery == null ? null : new SourceTime(recovery, new UUID(1, id))
        );
    }

    private MonitoringSlaLatency.Result read(List<MonitoringSlaLatency.Event> events) {
        return MonitoringSlaLatency.calculate(TENANT, START, END, END, events);
    }

    @Test
    void sharedEventAcknowledgmentAndVerifiedT2UseTheOriginalProblemTime() {
        var result = read(
            List.of(event(1, START.plusSeconds(3), "PENDING_REVIEW", "RECOVERED", START.plusSeconds(25)))
        );
        assertEquals(3L, result.mtta().meanSec());
        assertEquals(25L, result.mttr().meanSec());
        assertEquals(EventOperationalMetrics.VERSION, result.mtta().sourceVersion());
        assertEquals(MonitoringSlaLatency.RECOVERY_VERSION, result.mttr().sourceVersion());
        assertEquals(0, result.mttr().unknownCount());
    }

    @Test
    void unknownRecoveryIsNotCountedAsEitherRecoveredOrStillProblem() {
        var result = read(
            List.of(
                event(1, START, "ACKNOWLEDGED", "RECOVERED", START.plusSeconds(25)),
                event(2, START, "ACKNOWLEDGED", "RECOVERED", null),
                event(3, null, "NEW", "PROBLEM", null)
            )
        );
        assertEquals(3, result.incidentCount());
        assertEquals(1, result.mttr().sampleCount());
        assertEquals(1, result.mttr().unrecoveredCount());
        assertEquals(1, result.mttr().unknownCount());
        assertNull(result.mttr().meanSec());
        assertNull(result.mttr().p50Sec());
        assertNull(result.mttr().p95Sec());
    }

    @Test
    void anInvalidAcknowledgmentCannotBeDroppedToMakeAValidAverage() {
        var result = read(
            List.of(
                event(1, START.plusSeconds(2), "ACKNOWLEDGED", "PROBLEM", null),
                event(2, START.minusMillis(1), "ACKNOWLEDGED", "PROBLEM", null)
            )
        );
        assertEquals(1, result.mtta().sampleCount());
        assertEquals(1, result.mtta().unknownCount());
        assertNull(result.mtta().meanSec());
    }

    @Test
    void missingHumanAcknowledgmentIsUnknownAfterAHandlingTransition() {
        var result = read(List.of(event(1, null, "PENDING_REVIEW", "RECOVERED", START.plusSeconds(10))));
        assertEquals(1, result.mtta().unknownCount());
        assertNull(result.mtta().meanSec());
        assertEquals(10L, result.mttr().meanSec());
    }

    @Test
    void nanosecondsAreNotTruncatedBeforeAveragingAndPercentilesRoundConsistently() {
        var result = read(
            List.of(
                event(1, START, "ACKNOWLEDGED", "RECOVERED", START.plusNanos(499_999_999)),
                event(2, START, "ACKNOWLEDGED", "RECOVERED", START.plusNanos(500_000_001))
            )
        );
        assertEquals(1L, result.mttr().meanSec());
        assertEquals(1L, result.mttr().p50Sec());
        assertEquals(1L, result.mttr().p95Sec());
    }

    @Test
    void zeroIsValidWhileAnEmptyCohortStaysNull() {
        var zero = read(List.of(event(1, START, "ACKNOWLEDGED", "RECOVERED", START)));
        assertEquals(0L, zero.mtta().meanSec());
        assertEquals(0L, zero.mttr().meanSec());
        var empty = read(List.of());
        assertEquals(0, empty.mttr().sampleCount());
        assertEquals(0, empty.mttr().unknownCount());
        assertNull(empty.mttr().meanSec());
    }

    @Test
    void historicalCohortUsesLaterFactsKnownAtTheCurrentObservation() {
        var events = List.of(event(1, END.plusSeconds(2), "ACKNOWLEDGED", "RECOVERED", END.plusSeconds(20)));
        var result = MonitoringSlaLatency.calculate(TENANT, START, END, END.plusSeconds(60), events);
        assertEquals(3602L, result.mtta().meanSec());
        assertEquals(3620L, result.mttr().meanSec());
        assertEquals(0, result.mttr().unknownCount());
    }

    @Test
    void unconfirmedFutureOrContradictoryProofsRemainUnknown() {
        for (var value : List.of(
            event(1, START, "ACKNOWLEDGED", "RECOVERED", END.plusNanos(1)),
            event(2, START, "ACKNOWLEDGED", "PROBLEM", START.plusSeconds(2)),
            event(3, START, "ACKNOWLEDGED", "RECOVERED", START.minusNanos(1))
        )) {
            var result = read(List.of(value));
            assertEquals(1, result.mttr().unknownCount());
            assertEquals(0, result.mttr().unrecoveredCount());
            assertNull(result.mttr().meanSec());
        }
    }

    @Test
    void duplicateIncompleteAndOversizedCohortsAreRejected() {
        var value = event(1, START, "ACKNOWLEDGED", "RECOVERED", START);
        assertThrows(IllegalArgumentException.class, () -> read(List.of(value, value)));
        assertThrows(IllegalArgumentException.class, () ->
            MonitoringSlaLatency.calculate(TENANT, START.plusSeconds(1), END, END, List.of(value))
        );
        assertThrows(IllegalArgumentException.class, () ->
            read(
                IntStream.range(0, 10001)
                    .mapToObj(i -> event(i, START, "ACKNOWLEDGED", "PROBLEM", null))
                    .toList()
            )
        );
    }
}
