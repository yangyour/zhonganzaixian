package cn.mop.domain.ticket;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.TicketCustomerActionRules;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Pure candidate D07 transition model. Does not authorize a request, persist a ticket,
 * start/stop an SLA clock, send notifications or establish customer approval.
 * A future repository must load current membership, relationships, immutable solution
 * snapshot and effective configuration in its authorized tenant transaction, enforce
 * CSRF/idempotency/CAS and atomically persist the transition and dependent intents.
 * The candidate matrix still requires the M5-3 design approval before production use.
 */
public final class TicketLifecycle {
    private TicketLifecycle() {}

    public enum State { NEW, IN_PROGRESS, WAITING_VENDOR, PENDING_CUSTOMER, RESOLVED, CLOSED, REOPENED }
    public enum Action { ACCEPT, TRANSFER, WAIT_FOR_VENDOR, RESUME, SUBMIT_RESOLUTION, CONFIRM, NOT_RESOLVED, AUTO_CLOSE, REOPEN }
    public enum ClockEffect { UNCHANGED, PAUSE_RECOVERY, RESUME_ORIGINAL_RECOVERY }
    public enum CloseMode { CUSTOMER_CONFIRM, TIMEOUT_AUTO }

    /** Explicit configuration inputs only; null is never replaced with a business default. */
    public record Policy(Integer solutionMinChars, Duration confirmationTimeout,
                         Duration reopenWindow, Integer managerThreshold) {
        public Policy {
            if (solutionMinChars != null && solutionMinChars < 20
                    || confirmationTimeout != null && (confirmationTimeout.isZero() || confirmationTimeout.isNegative())
                    || reopenWindow != null && (reopenWindow.isZero() || reopenWindow.isNegative())
                    || managerThreshold != null && managerThreshold < 1) throw invalid();
        }
    }

    public record Snapshot(String tenantId, State state, long version, int reopenCount,
                           Instant lastTransitionAt, Instant resolvedAt, Instant closedAt,
                           String solutionSnapshotHash, CloseMode closeMode,
                           Instant responseDueAt, Instant recoveryDueAt) {
        public Snapshot {
            if (tenantId == null || tenantId.isBlank() || state == null || version < 1 || reopenCount < 0
                    || lastTransitionAt == null) throw invalid();
            if (solutionSnapshotHash != null && !hash(solutionSnapshotHash)) throw invalid();
            if (resolvedAt != null && resolvedAt.isAfter(lastTransitionAt)
                    || closedAt != null && closedAt.isAfter(lastTransitionAt)) throw invalid();
            if (closedAt != null && (resolvedAt == null || closedAt.isBefore(resolvedAt))) throw invalid();
            if ((state == State.PENDING_CUSTOMER || state == State.RESOLVED || state == State.CLOSED)
                    && (resolvedAt == null || solutionSnapshotHash == null)) throw invalid();
            if (state == State.CLOSED ? closedAt == null || closeMode == null : closedAt != null || closeMode != null) throw invalid();
        }
    }

    public record Command(Action action, long expectedVersion, Instant occurredAt,
                          String reason, String solutionSnapshotHash) {
        public Command {
            if (action == null || expectedVersion < 1 || occurredAt == null) throw invalid();
        }
    }

    /** Effects are plans, not evidence of execution or delivery. SLA deadlines are never recomputed. */
    public record Decision(Snapshot before, Snapshot after, Action action, String reason,
                           ClockEffect clockEffect, boolean managerNotificationRequired) {
        public boolean persisted() { return false; }
        public boolean productionApproved() { return false; }
        public String scope() { return "CANDIDATE_TICKET_LIFECYCLE"; }
    }

    public static boolean allows(State state, Action action) {
        Objects.requireNonNull(state); Objects.requireNonNull(action);
        return switch (action) {
            case ACCEPT -> state == State.NEW;
            case TRANSFER -> Set.of(State.NEW, State.IN_PROGRESS, State.WAITING_VENDOR, State.REOPENED).contains(state);
            case WAIT_FOR_VENDOR -> state == State.IN_PROGRESS || state == State.REOPENED;
            case RESUME -> state == State.WAITING_VENDOR;
            case SUBMIT_RESOLUTION -> state == State.IN_PROGRESS || state == State.REOPENED;
            case CONFIRM, NOT_RESOLVED, AUTO_CLOSE -> state == State.PENDING_CUSTOMER;
            case REOPEN -> state == State.CLOSED;
        };
    }

    public static Decision customer(Snapshot before, Command command, Policy policy,
                                    Membership actor, TicketCustomerActionRules.Relationships relationships) {
        TicketCustomerActionRules.requireCustomer(actor, relationships);
        if (before == null || !before.tenantId().equals(actor.tenantId())) throw notFound();
        if (command == null) throw invalid();
        if (!Set.of(Action.CONFIRM, Action.NOT_RESOLVED, Action.REOPEN).contains(command.action())) throw forbidden();
        return decide(before, command, policy);
    }

    public static Decision engineer(Snapshot before, Command command, Policy policy, Membership actor) {
        if (actor == null || !actor.active() || actor.permissionVersion() < 1
                || actor.userId() == null || actor.userId().isBlank())
            throw new DomainException(401, "SESSION_EXPIRED", "当前成员身份已失效");
        if (before == null || !before.tenantId().equals(actor.tenantId())) throw notFound();
        if (command == null) throw invalid();
        if (!Set.of("msp", "padmin").contains(actor.role() == null ? "" : actor.role())
                || !Set.of(Action.ACCEPT, Action.TRANSFER, Action.WAIT_FOR_VENDOR, Action.RESUME, Action.SUBMIT_RESOLUTION).contains(command.action()))
            throw forbidden();
        return decide(before, command, policy);
    }

    /** Only a future fenced Worker adapter may call this after verifying its lease and tenant. */
    public static Decision timeoutCandidate(Snapshot before, long expectedVersion, Instant now, Policy policy) {
        if (before == null) throw invalid();
        return decide(before, new Command(Action.AUTO_CLOSE, expectedVersion, now, null, before.solutionSnapshotHash()), policy);
    }

    private static Decision decide(Snapshot before, Command cmd, Policy policy) {
        if (before == null || cmd == null) throw invalid();
        if (before.version() != cmd.expectedVersion())
            throw new DomainException(409, "VERSION_CONFLICT", "工单版本已变化，请重新读取");
        if (cmd.occurredAt().isBefore(before.lastTransitionAt()) || !allows(before.state(), cmd.action()))
            throw stateError();
        State target = before.state();
        int count = before.reopenCount();
        Instant resolved = before.resolvedAt(), closed = before.closedAt();
        String snapshotHash = before.solutionSnapshotHash();
        CloseMode mode = before.closeMode();
        ClockEffect clock = ClockEffect.UNCHANGED;
        boolean notify = false;
        switch (cmd.action()) {
            case ACCEPT -> target = State.IN_PROGRESS;
            case TRANSFER -> TicketCustomerActionRules.requireUnresolvedReason(cmd.reason());
            case WAIT_FOR_VENDOR -> {
                TicketCustomerActionRules.requireUnresolvedReason(cmd.reason());
                target = State.WAITING_VENDOR;
            }
            case RESUME -> target = State.IN_PROGRESS;
            case SUBMIT_RESOLUTION -> {
                if (policy == null || policy.solutionMinChars() == null) throw pending();
                TicketCustomerActionRules.requireSolution(cmd.reason());
                if (textLength(cmd.reason()) < policy.solutionMinChars()
                        || !hash(cmd.solutionSnapshotHash())) throw invalid();
                target = State.PENDING_CUSTOMER; resolved = cmd.occurredAt(); snapshotHash = cmd.solutionSnapshotHash();
                clock = ClockEffect.PAUSE_RECOVERY;
            }
            case CONFIRM, AUTO_CLOSE -> {
                requireSnapshot(before, cmd);
                if (cmd.action() == Action.AUTO_CLOSE) {
                    if (policy == null || policy.confirmationTimeout() == null) throw pending();
                    if (Duration.between(before.resolvedAt(), cmd.occurredAt()).compareTo(policy.confirmationTimeout()) < 0) throw stateError();
                }
                target = State.CLOSED; closed = cmd.occurredAt();
                mode = cmd.action() == Action.CONFIRM ? CloseMode.CUSTOMER_CONFIRM : CloseMode.TIMEOUT_AUTO;
            }
            case NOT_RESOLVED, REOPEN -> {
                if (policy == null || policy.managerThreshold() == null) throw pending();
                if (cmd.action() == Action.NOT_RESOLVED) requireSnapshot(before, cmd);
                else {
                    if (policy.reopenWindow() == null) throw pending();
                    if (Duration.between(before.closedAt(), cmd.occurredAt()).compareTo(policy.reopenWindow()) > 0) throw stateError();
                }
                TicketCustomerActionRules.requireReopenReason(cmd.reason());
                try { count = Math.incrementExact(count); } catch (ArithmeticException e) { throw stateError(); }
                target = cmd.action() == Action.NOT_RESOLVED ? State.REOPENED : State.IN_PROGRESS;
                closed = null; mode = null; clock = ClockEffect.RESUME_ORIGINAL_RECOVERY;
                notify = count >= policy.managerThreshold();
            }
        }
        long version;
        try { version = Math.incrementExact(before.version()); } catch (ArithmeticException e) { throw stateError(); }
        return new Decision(before, new Snapshot(before.tenantId(), target, version, count, cmd.occurredAt(),
                resolved, closed, snapshotHash, mode, before.responseDueAt(), before.recoveryDueAt()),
                cmd.action(), cmd.reason(), clock, notify);
    }

    private static void requireSnapshot(Snapshot before, Command cmd) {
        if (!hash(cmd.solutionSnapshotHash()) || !cmd.solutionSnapshotHash().equals(before.solutionSnapshotHash()))
            throw new DomainException(409, "SOLUTION_SNAPSHOT_CONFLICT", "解决快照已变化，请重新读取");
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static int textLength(String value) {
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.codePointCount(start, end);
    }
    private static boolean whitespace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xfeff;
    }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "工单状态或动作输入无效"); }
    private static DomainException pending() { return new DomainException(422, "CONFIG_PENDING", "此动作所需运行配置尚未明确"); }
    private static DomainException stateError() { return new DomainException(422, "STATE_NOT_ALLOWED", "当前状态或时间不允许该动作"); }
    private static DomainException forbidden() { return new DomainException(403, "ACTION_FORBIDDEN", "当前身份不能执行此动作"); }
    private static DomainException notFound() { return new DomainException(404, "OBJECT_NOT_FOUND", "对象不存在或无权限"); }
}
