package cn.mop.domain.ticket;

import cn.mop.domain.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Contract M5-15/16 arithmetic only, never authority to notify, close or reset an SLA. */
public final class CustomerConfirmationWindow {
    private CustomerConfirmationWindow() {}
    public enum Tier { BASIC, STANDARD, ADVANCED }
    public enum Phase { BEFORE_RESOLUTION, WAITING, REMINDER_WINDOW, DUE }
    public enum Missing { TIER, RESOLVED_AT, OBSERVED_AT }
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999Z");
    public static final Duration REMINDER_LEAD = Duration.ofHours(12);

    /** Explicit scenario inputs: a tier is not proof of an approved effective service assignment. */
    public record Input(Tier tier, Instant resolvedAt, Instant observedAt) {
        public Input { validateTime(resolvedAt); validateTime(observedAt); }
    }
    public static final class Result {
        private final Input input;
        private final Instant reminderAt, confirmationDueAt;
        private final Phase phase;
        private final List<Missing> missing;
        private Result(Input input, Instant reminderAt, Instant confirmationDueAt, Phase phase, List<Missing> missing) {
            this.input = input; this.reminderAt = reminderAt; this.confirmationDueAt = confirmationDueAt;
            this.phase = phase; this.missing = List.copyOf(missing);
        }
        public Input input() { return input; }
        public Instant scenarioReminderAt() { return reminderAt; }
        public Instant scenarioConfirmationDueAt() { return confirmationDueAt; }
        public Phase scenarioPhase() { return phase; }
        public List<Missing> missing() { return missing; }
        public String scope() { return "EXPLICIT_CUSTOMER_CONFIRMATION_WINDOW"; }
        public String ruleVersion() { return "contract-m5-15-16-window-v1"; }
        public boolean effective() { return false; }
        public Instant effectiveConfirmationDueAt() { return null; }
        public Boolean shouldNotify() { return null; }
        public Boolean shouldAutoClose() { return null; }
        public String workflowStatus() { return "CONFIG_PENDING"; }
    }
    public static Result evaluate(Input input) {
        if (input == null) throw invalid();
        var missing = new java.util.ArrayList<Missing>();
        if (input.tier() == null) missing.add(Missing.TIER);
        if (input.resolvedAt() == null) missing.add(Missing.RESOLVED_AT);
        if (input.observedAt() == null) missing.add(Missing.OBSERVED_AT);
        if (input.tier() == null || input.resolvedAt() == null) return new Result(input, null, null, null, missing);
        Instant due;
        try { due = input.resolvedAt().plus(Duration.ofHours(input.tier() == Tier.ADVANCED ? 24 : 48)); }
        catch (java.time.DateTimeException | ArithmeticException exception) { throw invalid(); }
        validateTime(due);
        Instant reminder = due.minus(REMINDER_LEAD);
        Instant now = input.observedAt();
        Phase phase = now == null ? null : now.isBefore(input.resolvedAt()) ? Phase.BEFORE_RESOLUTION
            : now.isBefore(reminder) ? Phase.WAITING : now.isBefore(due) ? Phase.REMINDER_WINDOW : Phase.DUE;
        return new Result(input, reminder, due, phase, missing);
    }
    private static void validateTime(Instant time) {
        if (time != null && (time.isBefore(MIN) || time.isAfter(MAX) || time.getNano() % 1_000_000 != 0)) throw invalid();
    }
    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "确认窗口对照需明确输入及有效UTC毫秒时间，不能推断实际关单或通知");
    }
}
