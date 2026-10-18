package cn.mop.domain.sla;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.TicketLifecycle.State;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.time.IntervalSets.Interval;
import java.time.Instant;
import java.util.*;

/** Pure server-side evaluation of a ticket's frozen SLA clock against its workflow state and current tenant tier. */
public final class TicketSlaClockEvaluation {
    public static final String METRIC_VERSION = "SLA_CLOCK_V1";

    public record Thresholds(Instant p50At, Instant p80At, Instant p100At) {}

    public record ClockStatus(String name, Instant dueAt, Instant completedAt, boolean paused, Boolean breached,
            String status, String missingReason, Thresholds thresholds, List<Interval> workingWindows) {}

    public record Evaluation(String priority, Tier frozenServiceTier, Tier currentTenantServiceTier,
            boolean tierChangedSinceOpen, State workflowState, Instant startedAt, Instant acceptedAt,
            Instant resolvedAt, Instant closedAt, Instant confirmDueAt, int reopenCount,
            String reopenPolicyStatus, ClockStatus responseClock, ClockStatus recoveryClock,
            Map<String, String> slaMissing, List<TicketSlaSnapshot.Reference> slaReferences,
            String metricVersion) {}

    private TicketSlaClockEvaluation() {}

    public static Evaluation evaluate(TicketLifecycle.Snapshot workflow, Tier currentTenantTier, Instant observedAt) {
        if (workflow == null || observedAt == null)
            throw new DomainException(400, "VALIDATION_ERROR", "工单SLA评估输入不能为空");
        TicketSlaSnapshot.time(observedAt);
        TicketSlaSnapshot sla = workflow.sla();
        Tier frozen = sla.tier();
        boolean tierChanged = currentTenantTier != null && currentTenantTier != frozen;

        ClockStatus response = evaluateResponse(workflow, sla, observedAt);
        ClockStatus recovery = evaluateRecovery(workflow, sla, observedAt);
        String reopenPolicy = workflow.reopenCount() > 0 ? "PENDING_Q27" : "NOT_REOPENED";

        return new Evaluation(
            sla.priority(),
            frozen,
            currentTenantTier,
            tierChanged,
            workflow.state(),
            sla.startedAt(),
            workflow.acceptedAt(),
            workflow.resolvedAt(),
            workflow.closedAt(),
            workflow.confirmDueAt(),
            workflow.reopenCount(),
            reopenPolicy,
            response,
            recovery,
            sla.missing(),
            sla.references(),
            METRIC_VERSION
        );
    }

    private static ClockStatus evaluateResponse(TicketLifecycle.Snapshot workflow, TicketSlaSnapshot sla, Instant observedAt) {
        Instant due = sla.responseDueAt();
        Instant completed = workflow.acceptedAt();
        Thresholds thresholds = new Thresholds(
            sla.threshold("response", 50),
            sla.threshold("response", 80),
            sla.threshold("response", 100)
        );
        String missing = sla.missing().get("response");
        if (workflow.state() == State.CANCELLED && completed == null) {
            return new ClockStatus("RESPONSE", due, null, false, null, "CANCELLED", missing, thresholds, sla.responseWindows());
        }
        if (due == null) {
            return new ClockStatus("RESPONSE", null, completed, false, null, "PENDING_CONFIG",
                missing != null ? missing : "CONFIGURATION_UNAVAILABLE", thresholds, sla.responseWindows());
        }
        if (completed != null) {
            boolean breached = completed.isAfter(due);
            return new ClockStatus("RESPONSE", due, completed, false, breached, breached ? "BREACHED" : "MET", missing, thresholds, sla.responseWindows());
        }
        boolean breached = observedAt.isAfter(due);
        return new ClockStatus("RESPONSE", due, null, false, breached, breached ? "BREACHED" : "RUNNING", missing, thresholds, sla.responseWindows());
    }

    private static ClockStatus evaluateRecovery(TicketLifecycle.Snapshot workflow, TicketSlaSnapshot sla, Instant observedAt) {
        Instant due = sla.recoveryDueAt();
        Instant completed = workflow.resolvedAt();
        Thresholds thresholds = new Thresholds(
            sla.threshold("recovery", 50),
            sla.threshold("recovery", 80),
            sla.threshold("recovery", 100)
        );
        String missing = sla.missing().get("recovery");
        if (workflow.state() == State.CANCELLED) {
            return new ClockStatus("RECOVERY", due, completed, false, null, "CANCELLED", missing, thresholds, sla.recoveryWindows());
        }
        if (due == null) {
            return new ClockStatus("RECOVERY", null, completed, workflow.state() == State.RESOLVED, null, "PENDING_CONFIG",
                missing != null ? missing : "CONFIGURATION_UNAVAILABLE", thresholds, sla.recoveryWindows());
        }
        if (workflow.state() == State.RESOLVED) {
            boolean breached = completed != null && completed.isAfter(due);
            return new ClockStatus("RECOVERY", due, completed, true, breached,
                breached ? "BREACHED" : "PAUSED_FOR_CUSTOMER_CONFIRM", missing, thresholds, sla.recoveryWindows());
        }
        if (workflow.state() == State.CLOSED) {
            Instant check = completed != null ? completed : workflow.closedAt();
            boolean breached = check != null && check.isAfter(due);
            return new ClockStatus("RECOVERY", due, completed, false, breached, breached ? "BREACHED" : "MET", missing, thresholds, sla.recoveryWindows());
        }
        boolean breached = observedAt.isAfter(due);
        return new ClockStatus("RECOVERY", due, null, false, breached, breached ? "BREACHED" : "RUNNING", missing, thresholds, sla.recoveryWindows());
    }
}
