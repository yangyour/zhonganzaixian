package cn.mop.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.TicketSample;
import cn.mop.domain.sla.SlaComplianceScenario;
import cn.mop.domain.sla.SlaComplianceScenario.Exclusion;
import cn.mop.domain.sla.SlaComplianceScenario.Priority;
import cn.mop.domain.sla.SlaComplianceScenario.Reference;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Internal proof precision must not weaken the public explicit-scenario timestamp contract. */
class SourcePrecisionMonthlySlaTest {

    private static final Instant START = Instant.parse("2026-10-24T00:00:00Z");
    private static final Instant DUE = START.plusSeconds(25);

    private TicketSample sample(String id, Instant recovery) {
        return new TicketSample(
            id,
            Priority.P2,
            Tier.STANDARD,
            Exclusion.NONE,
            null,
            START,
            START.plusSeconds(5),
            START.plusSeconds(3),
            DUE,
            recovery
        );
    }

    @Test
    void exactDeadlineAndOneNanosecondOnEitherSideRemainDistinct() {
        var result = SlaAvailabilityAndCompensation.evaluateMonthly(
            Tier.STANDARD,
            null,
            List.of(sample("before", DUE.minusNanos(1)), sample("equal", DUE), sample("after", DUE.plusNanos(1)))
        );
        assertEquals(2, result.metCount());
        assertEquals(1, result.notMetCount());
        assertEquals(0, result.unresolvedCount());
        assertEquals(DUE.plusNanos(1), result.items().get(2).recoveryActualAt());
        assertEquals("NOT_MET", result.items().get(2).recoveryCheck());
        assertNull(result.compensationAmountFen());
    }

    @Test
    void publicScenarioEvidenceStillRejectsSubmillisecondCallerTimes() {
        assertEquals(
            400,
            assertThrows(DomainException.class, () ->
                new SlaComplianceScenario.Evidence(
                    "explicit",
                    Priority.P2,
                    Exclusion.NONE,
                    null,
                    new Reference("snapshot", 1),
                    START.plusSeconds(5),
                    DUE,
                    START.plusSeconds(3),
                    DUE.plusNanos(1)
                )
            ).status
        );
    }

    @Test
    void missingRecoveryStaysUnresolvedAndDuplicatePopulationIsRejected() {
        var result = SlaAvailabilityAndCompensation.evaluateMonthly(
            Tier.STANDARD,
            null,
            List.of(sample("unknown", null))
        );
        assertEquals(1, result.unresolvedCount());
        assertEquals("UNKNOWN", result.items().get(0).recoveryCheck());
        assertNull(result.items().get(0).recoveryActualAt());
        assertThrows(DomainException.class, () ->
            SlaAvailabilityAndCompensation.evaluateMonthly(
                Tier.STANDARD,
                null,
                List.of(sample("same", DUE), sample("same", DUE))
            )
        );
    }
}
