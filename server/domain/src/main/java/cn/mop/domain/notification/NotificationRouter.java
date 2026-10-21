package cn.mop.domain.notification;

import java.time.*;
import java.util.*;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import cn.mop.domain.notification.DeliveryReceiptState.Deduplication;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft.LevelPreference;

/** Pure routing decisions over explicitly supplied published facts, never a dispatch authorization. */
public final class NotificationRouter {
    private NotificationRouter() {}
    public enum Priority { P1, P2, P3, P4 }
    /** Supported algorithm only; Q14 must explicitly publish this choice before it is usable. */
    public enum Precedence { MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS }
    public enum Outcome { PLANNED, CONFIG_PENDING, FILTERED_LEVEL, FILTERED_CHANNEL, QUIET, DUPLICATE, RESULT_UNKNOWN }
    public record Event(UUID tenantId, UUID poolId, UUID eventId, Priority priority, String phase, Instant occurredAt) {
        public Event {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(poolId); Objects.requireNonNull(eventId);
            if (phase == null || !phase.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
            Objects.requireNonNull(occurredAt);
        }
    }
    /** No factory accepts V26 drafts as effective preferences. Publication and binding must be verified by a repository. */
    public record Preference(UUID tenantId, UUID receiverId, String publication, LevelPreference level,
                             Set<Channel> channels, QuietHours.Policy quiet) {
        public Preference {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(receiverId);
            channels = channels == null ? null : Set.copyOf(channels);
        }
    }
    public record Matrix(UUID tenantId, String publication, Priority priority, Set<Channel> channels,
                         Duration aggregateWait, Duration dedupeWindow, Precedence precedence) {
        public Matrix {
            Objects.requireNonNull(tenantId);
            channels = channels == null ? null : Set.copyOf(channels);
            if (aggregateWait != null && (aggregateWait.isNegative() || aggregateWait.compareTo(Duration.ofDays(1)) > 0)) throw invalid();
            // Contract fixed window; never accept a caller-selected shorter bypass.
            if (dedupeWindow != null && !dedupeWindow.equals(Duration.ofMinutes(5))) throw invalid();
        }
    }
    public record Binding(UUID tenantId, UUID receiverId, Channel channel, String reference, Instant validUntil) {
        public Binding { Objects.requireNonNull(tenantId); Objects.requireNonNull(receiverId); Objects.requireNonNull(channel); }
    }
    /** Exact-key history only. Unknown outcome is not a failed send and cannot be retried by routing. */
    public record Prior(UUID tenantId, Deduplication key, Instant initiatedAt, boolean unresolved) {
        public Prior { Objects.requireNonNull(tenantId); Objects.requireNonNull(key); Objects.requireNonNull(initiatedAt); }
    }
    public record Decision(Channel channel, Outcome outcome, Deduplication key, Instant notBefore,
                           String matrixPublication, String preferencePublication, String bindingReference) {
        public boolean dispatched() { return false; }
        public boolean delivered() { return false; }
    }
    public record Plan(Outcome outcome, List<Decision> decisions) {
        public Plan { decisions = List.copyOf(decisions); }
        public boolean dispatchAuthorized() { return false; }
    }
    /** Null history means history unavailable; an explicit empty list means a verified empty query. */
    public static Plan plan(Event event, UUID receiver, Matrix matrix, Preference preference,
                            List<Binding> bindings, List<Prior> history, Instant at) {
        Objects.requireNonNull(event); Objects.requireNonNull(receiver); Objects.requireNonNull(at);
        if (at.isBefore(event.occurredAt())) throw invalid();
        if (matrix != null && !event.tenantId().equals(matrix.tenantId()) || preference != null &&
                (!event.tenantId().equals(preference.tenantId()) || !receiver.equals(preference.receiverId()))) throw invalid();
        if (bindings != null) {
            if (bindings.size() > Channel.values().length) throw invalid();
            var seen = EnumSet.noneOf(Channel.class);
            for (var b : bindings) if (b == null || !event.tenantId().equals(b.tenantId()) || !receiver.equals(b.receiverId()) || !seen.add(b.channel())) throw invalid();
        }
        if (history != null) {
            if (history.size() > 10000) throw invalid();
            for (var p : history) if (p == null || !event.tenantId().equals(p.tenantId()) ||
                    !event.poolId().equals(p.key().resourcePoolId()) || !event.eventId().equals(p.key().eventId()) ||
                    !receiver.equals(p.key().receiverId()) || !event.phase().equals(p.key().stage()) || p.initiatedAt().isAfter(at)) throw invalid();
        }
        if (event.priority() == null || matrix == null || !present(matrix.publication()) || matrix.priority() != event.priority() ||
                matrix.channels() == null || matrix.precedence() == null || matrix.aggregateWait() == null || matrix.dedupeWindow() == null ||
                preference == null || !present(preference.publication()) || preference.level() == null || preference.channels() == null ||
                bindings == null || history == null) return new Plan(Outcome.CONFIG_PENDING, List.of());
        // An explicitly empty matrix is a published record-only policy, not an inferred default.
        if (matrix.channels().isEmpty()) return new Plan(Outcome.FILTERED_CHANNEL, List.of());
        var decisions = new ArrayList<Decision>();
        for (var channel : Channel.values()) {
            if (!matrix.channels().contains(channel)) continue;
            var key = new Deduplication(event.poolId(), event.eventId(), channel, receiver, event.phase());
            var binding = bindings.stream().filter(b -> b.channel() == channel).findFirst().orElse(null);
            Outcome outcome;
            if (!allows(preference.level(), event.priority())) outcome = Outcome.FILTERED_LEVEL;
            else if (!preference.channels().contains(channel)) outcome = Outcome.FILTERED_CHANNEL;
            else {
                var quiet = QuietHours.evaluate(event.priority() == Priority.P1, preference.quiet(), at);
                if (quiet.status() == QuietHours.Status.UNRESOLVED) outcome = Outcome.CONFIG_PENDING;
                else if (quiet.status() == QuietHours.Status.MUTED) outcome = Outcome.QUIET;
                else if (binding == null || !present(binding.reference()) || binding.validUntil() == null || !at.isBefore(binding.validUntil())) outcome = Outcome.CONFIG_PENDING;
                else if (history.stream().anyMatch(p -> p.key().equals(key) && p.unresolved())) outcome = Outcome.RESULT_UNKNOWN;
                else if (history.stream().anyMatch(p -> p.key().equals(key) && Duration.between(p.initiatedAt(), at).compareTo(matrix.dedupeWindow()) < 0)) outcome = Outcome.DUPLICATE;
                else outcome = Outcome.PLANNED;
            }
            Instant notBefore = null;
            if (outcome == Outcome.PLANNED) {
                Instant due = event.occurredAt().plus(matrix.aggregateWait());
                notBefore = due.isAfter(at) ? due : at;
                if (!notBefore.isBefore(binding.validUntil())) { outcome = Outcome.CONFIG_PENDING; notBefore = null; }
            }
            decisions.add(new Decision(channel, outcome, key, notBefore, matrix.publication(), preference.publication(), binding == null ? null : binding.reference()));
        }
        Outcome overall = decisions.stream().anyMatch(d -> d.outcome() == Outcome.CONFIG_PENDING) ? Outcome.CONFIG_PENDING :
                decisions.stream().anyMatch(d -> d.outcome() == Outcome.RESULT_UNKNOWN) ? Outcome.RESULT_UNKNOWN :
                decisions.stream().anyMatch(d -> d.outcome() == Outcome.PLANNED) ? Outcome.PLANNED : decisions.get(0).outcome();
        return new Plan(overall, decisions);
    }
    private static boolean allows(LevelPreference level, Priority priority) {
        return level == LevelPreference.ALL || priority == Priority.P1 || level == LevelPreference.P1_P2 && priority == Priority.P2;
    }
    private static boolean present(String value) { return value != null && !value.isBlank() && value.length() <= 256; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Explicit scoped routing evidence required"); }
}
