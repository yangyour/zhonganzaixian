package cn.mop.domain.notification;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** Pure local-calendar quiet-window evaluation, not permission or a dispatch decision. */
public final class QuietHours {
    private QuietHours() {}

    public enum Status { MUTED, NOT_MUTED, UNRESOLVED }
    public enum Reason { INSIDE_WINDOW, OUTSIDE_WINDOW, P1_BYPASS, DISABLED,
        PRIORITY_UNKNOWN, POLICY_UNKNOWN, INSTANT_UNKNOWN }

    /** Equal boundaries are ambiguous, never silently interpreted as all-day or disabled. */
    public record Policy(String version, Boolean enabled, ZoneId zone, LocalTime start, LocalTime end) {
        public Policy {
            if (start != null && start.equals(end))
                throw new IllegalArgumentException("Quiet-window boundaries must differ");
        }
        private boolean complete() {
            return version != null && !version.isBlank() && enabled != null
                    && (!enabled || zone != null && start != null && end != null);
        }
    }

    public record Result(Status status, Reason reason, String policyVersion, LocalTime localTime) {}

    /**
     * isP1 must come from the resolved, authorized priority mapping, not an untrusted request.
     * P1 bypasses this filter even if quiet policy is unknown; all other dispatch gates still apply.
     * Windows use [start,end) on the supplied zone's local clock, including both DST overlaps.
     */
    public static Result evaluate(Boolean isP1, Policy policy, Instant at) {
        String version = policy == null ? null : policy.version();
        if (Boolean.TRUE.equals(isP1)) return new Result(Status.NOT_MUTED, Reason.P1_BYPASS, version, null);
        if (isP1 == null) return new Result(Status.UNRESOLVED, Reason.PRIORITY_UNKNOWN, version, null);
        if (policy == null || !policy.complete()) return new Result(Status.UNRESOLVED, Reason.POLICY_UNKNOWN, version, null);
        if (!policy.enabled()) return new Result(Status.NOT_MUTED, Reason.DISABLED, version, null);
        if (at == null) return new Result(Status.UNRESOLVED, Reason.INSTANT_UNKNOWN, version, null);
        LocalTime local = at.atZone(policy.zone()).toLocalTime();
        boolean muted = policy.start().isBefore(policy.end())
                ? !local.isBefore(policy.start()) && local.isBefore(policy.end())
                : !local.isBefore(policy.start()) || local.isBefore(policy.end());
        return new Result(muted ? Status.MUTED : Status.NOT_MUTED,
                muted ? Reason.INSIDE_WINDOW : Reason.OUTSIDE_WINDOW, version, local);
    }
}
