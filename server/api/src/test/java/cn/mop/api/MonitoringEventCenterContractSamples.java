package cn.mop.api;

import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/** Synthetic, deterministic repository projections, never evidence of database or collector integration. */
final class MonitoringEventCenterContractSamples {
    static final String TENANT = id(1), ACTOR = id(2), ADMIN = id(3), VIEWER = id(4), ASSET = id(5);
    static final String EVENT = id(100), WINDOW = id(200), QUARANTINE = id(300), ATTEMPT = id(402);
    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private MonitoringEventCenterContractSamples() {}

    static String id(int suffix) { return "55000000-0000-4000-8000-" + String.format(Locale.ROOT, "%012d", suffix); }

    static EventSummary event(int index) {
        var handling = HandlingState.values()[index];
        var severity = new Severity[]{Severity.DISASTER, Severity.HIGH, Severity.AVERAGE, Severity.WARNING, Severity.INFO, Severity.HIGH}[index];
        var suppression = new SuppressionState[]{SuppressionState.NONE, SuppressionState.FLAP_SILENCED,
            SuppressionState.DEPENDENCY_SUPPRESSED, SuppressionState.MAINTENANCE_SILENCED,
            SuppressionState.INFO_DEFAULT_SILENT, SuppressionState.NONE}[index];
        var notification = switch (suppression) {
            case NONE -> NotificationEmptyState.NORMAL_DISPATCH_ELIGIBLE;
            case FLAP_SILENCED -> NotificationEmptyState.FLAP_SILENCED_NO_NOTIFY;
            case DEPENDENCY_SUPPRESSED -> NotificationEmptyState.DEPENDENCY_SUPPRESSED_NO_NOTIFY;
            case MAINTENANCE_SILENCED -> NotificationEmptyState.MAINTENANCE_WINDOW_SUPPRESSED;
            case INFO_DEFAULT_SILENT -> NotificationEmptyState.INFO_LEVEL_NO_NOTIFY;
        };
        return new EventSummary(id(100 + index), TENANT, ASSET, index + 1L, "SYNTHETIC_RULE_" + index,
            "合成监控规则 " + index, severity, severity.rank(), "FP_V1|" + TENANT + "|" + ASSET + "|SYNTHETIC_RULE_" + index,
            index + 1, index == 1 ? 3 : 0, index == 1 ? NOW.plusSeconds(300) : null,
            index < 3 ? MonitorState.PROBLEM : MonitorState.RECOVERED, handling, suppression,
            suppression == SuppressionState.NONE ? null : "合成抑制说明 " + suppression.name(), notification, notification.label(),
            suppression == SuppressionState.DEPENDENCY_SUPPRESSED, "CONFIG_PENDING_Q29", NOW.minusSeconds(600), NOW,
            index >= 3 ? NOW : null, index >= 1 ? NOW.minusSeconds(300) : null,
            index >= 4 ? NOW : null, index == 5 ? NOW : null, index >= 4 ? "合成测试人工确认根因" : null,
            index == 0 || index == 5 ? id(500 + index) : null, ACTOR, NOW);
    }

    static NoiseFunnelView funnel(String window) { return new NoiseFunnelView(TENANT, window, 23, 6, 3, 2, 4, 8, NOW); }

    static MaintenanceWindow window(int index) {
        boolean confirmed = index == 2;
        return new MaintenanceWindow(id(200 + index), TENANT, index + 1L, "合成维护窗口 " + index, ASSET,
            index == 1 ? NOW.plusSeconds(3600) : NOW.minusSeconds(3600),
            index == 2 ? NOW.minusSeconds(60) : NOW.plusSeconds(7200),
            index == 1 ? "PAUSE_COLLECTION" : "SUPPRESS_NOTIFY", new String[]{"ACTIVE", "SCHEDULED", "ENDED"}[index],
            index != 1, confirmed, confirmed ? ADMIN : null, confirmed ? NOW.minusSeconds(1800) : null,
            "合成测试维护原因", ACTOR, confirmed ? ADMIN : ACTOR, NOW);
    }

    static QuarantineItem quarantine(int index) {
        String decision = index == 0 ? null : index == 1 ? "BIND_ASSET" : "DISCARD";
        return new QuarantineItem(id(300 + index), id(350), "synthetic-quarantine-" + index, index == 2 ? "RECOVERED" : "PROBLEM",
            index == 0 ? null : TENANT, index == 0 ? null : ASSET, "UNKNOWN_MAPPING", NOW.minusSeconds(60).plusNanos(123456789), NOW.plusNanos(987654321),
            "HELD_UNVERIFIED", decision, index == 1 ? ASSET : null, index == 1 ? EVENT : null,
            index == 0 ? null : "合成测试人工核验原因", index == 0 ? null : ACTOR, index == 0 ? null : NOW);
    }

    static AutoTicketAttempt attempt(int index) {
        var status = AutoTicketStatus.values()[index];
        return new AutoTicketAttempt(id(400 + index), TENANT, id(100 + index), index + 1L,
            status == AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED ? 5 : status == AutoTicketStatus.MANUAL_CREATED ? 6 : 1, 5, "30,120,600,600,600", status,
            status == AutoTicketStatus.SUPPRESSED_SKIPPED ? null : "P1", "CONFIG_PENDING_Q14",
            status == AutoTicketStatus.SUCCEEDED || status == AutoTicketStatus.MANUAL_CREATED ? id(500 + index) : null,
            status == AutoTicketStatus.RETRY_SCHEDULED ? NOW.plusSeconds(30) : null,
            status == AutoTicketStatus.RETRY_SCHEDULED || status == AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED ? "合成下游失败摘要" : null,
            ACTOR, NOW);
    }

    static EventCenterWorkspace workspace(String window) {
        return new EventCenterWorkspace(TENANT, IntStream.range(0, HandlingState.values().length).mapToObj(MonitoringEventCenterContractSamples::event).toList(),
            funnel(window), IntStream.range(0, 3).mapToObj(MonitoringEventCenterContractSamples::window).toList(),
            IntStream.range(0, 3).mapToObj(MonitoringEventCenterContractSamples::quarantine).toList(),
            IntStream.range(0, AutoTicketStatus.values().length).mapToObj(MonitoringEventCenterContractSamples::attempt).toList(), NOW);
    }

    static EventDetailView detail(boolean includeAttempt) {
        var members = List.of(new EventMember(id(600), TENANT, EVENT, null, 1, "synthetic-source-problem", MonitorState.PROBLEM,
            NOW.minusSeconds(60), false, NOW), new EventMember(id(601), TENANT, EVENT, id(650), 2,
            "synthetic-source-recovery", MonitorState.RECOVERED, NOW, true, NOW));
        var noise = Arrays.stream(NoiseLayer.values()).map(layer -> new NoiseRecord(id(700 + layer.ordinal()), TENANT, EVENT,
            layer, "合成降噪轨迹 " + layer.name(), NOW)).toList();
        return new EventDetailView(event(0), members, noise, includeAttempt ? Optional.of(attempt(0)) : Optional.empty(), NOW);
    }
}
