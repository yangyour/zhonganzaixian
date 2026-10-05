package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.CustomerConfirmationWindow;
import cn.mop.domain.ticket.CustomerConfirmationWindow.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class CustomerConfirmationWindowTest {
    private static final Instant START = Instant.parse("2026-10-31T23:30:00.123Z");
    @ParameterizedTest @EnumSource(Tier.class)
    void computesOnlyExplicitContractHours(Tier tier) {
        var result = CustomerConfirmationWindow.evaluate(new Input(tier, START, START));
        var due = START.plus(Duration.ofHours(tier == Tier.ADVANCED ? 24 : 48));
        assertEquals(due, result.scenarioConfirmationDueAt());
        assertEquals(due.minus(Duration.ofHours(12)), result.scenarioReminderAt());
        assertEquals(Phase.WAITING, result.scenarioPhase());
        assertEquals(List.of(), result.missing());
    }
    @ParameterizedTest @EnumSource(Tier.class)
    void comparesExactReminderAndDueBoundaries(Tier tier) {
        var schedule = CustomerConfirmationWindow.evaluate(new Input(tier, START, START));
        var reminder = schedule.scenarioReminderAt(); var due = schedule.scenarioConfirmationDueAt();
        assertPhase(tier, START.minusMillis(1), Phase.BEFORE_RESOLUTION);
        assertPhase(tier, reminder.minusMillis(1), Phase.WAITING);
        assertPhase(tier, reminder, Phase.REMINDER_WINDOW);
        assertPhase(tier, due.minusMillis(1), Phase.REMINDER_WINDOW);
        assertPhase(tier, due, Phase.DUE); assertPhase(tier, due.plusMillis(1), Phase.DUE);
    }
    @Test void unknownTierNeverDefaultsToBasic() {
        var result = CustomerConfirmationWindow.evaluate(new Input(null, START, START));
        assertNull(result.scenarioConfirmationDueAt()); assertNull(result.scenarioReminderAt()); assertNull(result.scenarioPhase());
        assertEquals(List.of(Missing.TIER), result.missing());
    }
    @Test void missingResolutionIsNotReplacedByCurrentTime() {
        var result = CustomerConfirmationWindow.evaluate(new Input(Tier.BASIC, null, START));
        assertNull(result.scenarioConfirmationDueAt()); assertEquals(List.of(Missing.RESOLVED_AT), result.missing());
    }
    @Test void missingClockKeepsExplicitScheduleButNoDueConclusion() {
        var result = CustomerConfirmationWindow.evaluate(new Input(Tier.ADVANCED, START, null));
        assertEquals(START.plus(Duration.ofHours(24)), result.scenarioConfirmationDueAt());
        assertNull(result.scenarioPhase()); assertEquals(List.of(Missing.OBSERVED_AT), result.missing());
    }
    @Test void allUnknownInputsStayUnknownAndMissingListIsImmutable() {
        var result = CustomerConfirmationWindow.evaluate(new Input(null, null, null));
        assertEquals(List.of(Missing.TIER, Missing.RESOLVED_AT, Missing.OBSERVED_AT), result.missing());
        assertThrows(UnsupportedOperationException.class, () -> result.missing().clear());
        assertNull(result.scenarioConfirmationDueAt()); assertNull(result.scenarioPhase());
    }
    @Test void scenarioNeverAuthorizesNotificationsOrClosingEvenWhenDue() {
        var result = CustomerConfirmationWindow.evaluate(new Input(Tier.ADVANCED, START, START.plus(Duration.ofDays(7))));
        assertEquals(Phase.DUE, result.scenarioPhase()); assertFalse(result.effective());
        assertNull(result.effectiveConfirmationDueAt()); assertNull(result.shouldNotify()); assertNull(result.shouldAutoClose());
        assertEquals("CONFIG_PENDING", result.workflowStatus());
        assertEquals("EXPLICIT_CUSTOMER_CONFIRMATION_WINDOW", result.scope());
        assertEquals("contract-m5-15-16-window-v1", result.ruleVersion());
    }
    @Test void laterTierScenarioCannotMutateEarlierSnapshot() {
        var original = new Input(Tier.BASIC, START, START);
        var first = CustomerConfirmationWindow.evaluate(original);
        CustomerConfirmationWindow.evaluate(new Input(Tier.ADVANCED, START, START));
        assertEquals(original, first.input()); assertEquals(START.plus(Duration.ofHours(48)), first.scenarioConfirmationDueAt());
    }
    @Test void rejectsInvalidTimePrecisionAndCalendarRange() {
        for (var invalid : List.of(START.plusNanos(1), Instant.parse("0000-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z"))) {
            assertThrows(DomainException.class, () -> new Input(Tier.BASIC, invalid, START));
            assertThrows(DomainException.class, () -> new Input(Tier.BASIC, START, invalid));
        }
    }
    @Test void rejectsOverflowingComputedDeadlineWithoutClamping() {
        assertThrows(DomainException.class, () -> CustomerConfirmationWindow.evaluate(new Input(Tier.BASIC, Instant.parse("9999-12-31T23:59:59.999Z"), START)));
        assertThrows(DomainException.class, () -> CustomerConfirmationWindow.evaluate(null));
    }
    @Test void preservesLeapDayAndSubsecondPrecisionAcrossMonthBoundary() {
        var start = Instant.parse("2028-02-28T23:59:59.999Z");
        var result = CustomerConfirmationWindow.evaluate(new Input(Tier.BASIC, start, start));
        assertEquals(Instant.parse("2028-03-01T23:59:59.999Z"), result.scenarioConfirmationDueAt());
    }
    private static void assertPhase(Tier tier, Instant observed, Phase expected) {
        assertEquals(expected, CustomerConfirmationWindow.evaluate(new Input(tier, START, observed)).scenarioPhase());
    }
}
