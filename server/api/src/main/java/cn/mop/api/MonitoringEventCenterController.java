package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/**
 * REST controller for V37 D15 Monitoring Event Center, Noise Funnel,
 * Maintenance Windows, Quarantine Manual Decisions, and Auto-Ticket Attempts
 * (M1-2, M4-7, M4-8, M4-9, M4-10, M4-11, M4-14, M5-4).
 */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public final class MonitoringEventCenterController {
    private static final long MAX_VERSION = 9_007_199_254_740_991L;
    private final PlatformRepository platform;
    private final MonitoringEventCenterRepository repository;

    public MonitoringEventCenterController(PlatformRepository platform, MonitoringEventCenterRepository repository) {
        this.platform = platform;
        this.repository = repository;
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/events")
    Object workspace(HttpServletRequest r) {
        var subject = subject(r, "tickets:read", true);
        Access a = subject.access();
        var q = query(r, Set.of("window", "page_size"));
        String window = q.getOrDefault("window", "24h");
        int limit = size(q.get("page_size"), 25, 50);
        EventCenterWorkspace w = repository.workspace(a, window, limit);
        if (w == null || !a.tenantId().equals(w.tenantId())) throw unavailable();
        var result = map(
            "tenant_id", w.tenantId(),
            "events", w.events().stream().map(x -> eventDto(x, a.tenantId())).toList(),
            "noise_funnel", funnelDto(w.noiseFunnel(), a.tenantId()),
            "maintenance_windows", w.maintenanceWindows().stream().map(x -> windowDto(x, a.tenantId())).toList(),
            "quarantine_items", w.quarantineItems().stream().map(MonitoringEventCenterController::quarantineDto).toList(),
            "autoticket_attempts", w.autoTicketAttempts().stream().map(x -> attemptDto(x, a.tenantId())).toList(),
            "observed_at", w.observedAt().toString(),
            "source", "postgresql"
        );
        DashboardReadAuthorization.recheck(r, platform, subject.membership());
        return success(result, r);
    }

    @GetMapping("/events/noise-funnel")
    Object noiseFunnel(HttpServletRequest r) {
        var subject = subject(r, "tickets:read", true);
        Access a = subject.access();
        var q = query(r, Set.of("window"));
        String window = q.getOrDefault("window", "24h");
        NoiseFunnelView v = repository.noiseFunnel(a, window);
        if (v == null || !a.tenantId().equals(v.tenantId())) throw unavailable();
        var result = funnelDto(v, a.tenantId());
        DashboardReadAuthorization.recheck(r, platform, subject.membership());
        return success(result, r);
    }

    @GetMapping("/events/{id}")
    Object eventDetail(@PathVariable("id") String rawId, HttpServletRequest r) {
        var subject = subject(r, "tickets:read", true);
        Access a = subject.access();
        if (r.getQueryString() != null) throw invalid();
        String id = parseUuid(rawId);
        EventDetailView v = repository.eventDetail(a, id);
        if (v == null || v.event() == null || !a.tenantId().equals(v.event().tenantId())) throw unavailable();
        var result = map(
            "event", eventDto(v.event(), a.tenantId()),
            "members", v.members().stream().map(x -> memberDto(x, a.tenantId())).toList(),
            "noise_records", v.noiseRecords().stream().map(x -> noiseDto(x, a.tenantId())).toList(),
            "autoticket_attempt", v.autoTicketAttempt().map(x -> attemptDto(x, a.tenantId())).orElse(null),
            "observed_at", v.observedAt().toString(),
            "source", "postgresql"
        );
        DashboardReadAuthorization.recheck(r, platform, subject.membership());
        return success(result, r);
    }

    @PostMapping(value = "/events/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object ingest(HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("asset_id", "rule_code", "rule_name", "severity", "source_phase", "source_event_id", "occurred_at", "simulate_autoticket_failure"));
        String assetId = uuid(b.get("asset_id"));
        String ruleCode = text(b.get("rule_code"), 64);
        String ruleName = text(b.get("rule_name"), 128);
        Severity severity = parseEnum(b.get("severity"), Severity.class);
        MonitorState phase = parseEnum(b.get("source_phase"), MonitorState.class);
        String sourceEventId = text(b.get("source_event_id"), 256);
        Instant occurredAt = b.get("occurred_at").isNull() ? null : parseInstant(b.get("occurred_at"));
        if (!b.get("simulate_autoticket_failure").isBoolean()) throw invalid();
        boolean simulateFail = b.get("simulate_autoticket_failure").booleanValue();
        IngestEventCommand cmd = new IngestEventCommand(assetId, ruleCode, ruleName, severity, phase, sourceEventId, occurredAt, simulateFail);
        String key = idempotencyKey(r);
        String digest = digest("EVENT_INGEST_V1\n" + assetId + "\n" + ruleCode + "\n" + ruleName + "\n" + severity.name() + "\n" + phase.name() + "\n" + sourceEventId + "\n" + occurredAt + "\n" + simulateFail);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> eventDto(repository.ingestEvent(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @PostMapping(value = "/events/{id}/transitions", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object transition(@PathVariable("id") String rawId, HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        String eventId = parseUuid(rawId);
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("expected_version", "action", "root_cause_note"));
        long expected = version(b.get("expected_version"));
        String action = text(b.get("action"), 32);
        String rootCause = b.get("root_cause_note").isNull() ? null : text(b.get("root_cause_note"), 500);
        if ("RESOLVE".equals(action) && (rootCause == null || rootCause.strip().codePointCount(0,rootCause.strip().length()) < MonitoringEventPipeline.MIN_REVIEW_CODE_POINTS))
            throw new DomainException(400,"VALIDATION_ERROR","人工复核必须填写至少20个字符的根因与处置说明");
        TransitionEventCommand cmd = new TransitionEventCommand(eventId, expected, action, rootCause);
        String key = idempotencyKey(r);
        String digest = digest("EVENT_TRANSITION_V1\n" + eventId + "\n" + expected + "\n" + action + "\n" + rootCause);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> eventDto(repository.transitionEvent(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @PostMapping(value = "/maintenance-windows", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object createMaintenanceWindow(HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("name", "asset_id", "starts_at", "ends_at", "silence_mode", "sla_exclusion_requested", "reason"));
        String name = text(b.get("name"), 128);
        String assetId = uuid(b.get("asset_id"));
        Instant startsAt = parseInstant(b.get("starts_at"));
        Instant endsAt = parseInstant(b.get("ends_at"));
        String silenceMode = text(b.get("silence_mode"), 24);
        if (!b.get("sla_exclusion_requested").isBoolean()) throw invalid();
        boolean slaReq = b.get("sla_exclusion_requested").booleanValue();
        String reason = text(b.get("reason"), 500);
        CreateMaintenanceWindowCommand cmd = new CreateMaintenanceWindowCommand(name, assetId, startsAt, endsAt, silenceMode, slaReq, reason);
        String key = idempotencyKey(r);
        String digest = digest("MAINT_WIN_CREATE_V1\n" + name + "\n" + assetId + "\n" + startsAt + "\n" + endsAt + "\n" + silenceMode + "\n" + slaReq + "\n" + reason);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> windowDto(repository.createMaintenanceWindow(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @PostMapping(value = "/maintenance-windows/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object updateMaintenanceWindow(@PathVariable("id") String rawId, HttpServletRequest r) {
        String windowId = parseUuid(rawId);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("expected_version", "action", "new_ends_at"));
        long expected = version(b.get("expected_version"));
        String action = text(b.get("action"), 32).toUpperCase(Locale.ROOT);
        Instant newEndsAt = b.get("new_ends_at").isNull() ? null : parseInstant(b.get("new_ends_at"));
        Access a = "CONFIRM_SLA_EXCLUSION".equals(action)
            ? access(r, "configurations:write", true)
            : access(r, "ticket_notes:write_internal", true);
        UpdateMaintenanceWindowCommand cmd = new UpdateMaintenanceWindowCommand(windowId, expected, action, newEndsAt);
        String key = idempotencyKey(r);
        String digest = digest("MAINT_WIN_ACTION_V1\n" + windowId + "\n" + expected + "\n" + action + "\n" + newEndsAt);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> {
                if ("CONFIRM_SLA_EXCLUSION".equals(action)) repository.authorizeAdminWrite(a);
                else repository.authorizeWrite(a);
            },
            () -> windowDto(repository.updateMaintenanceWindow(a, cmd), a.tenantId()));
        return success(result, r);
    }

    @PostMapping(value = "/monitoring/quarantine/{id}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object decideQuarantine(@PathVariable("id") String rawId, HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        String quarantineId = parseUuid(rawId);
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("decision", "bound_asset_id", "reason"));
        String decision = text(b.get("decision"), 16);
        String boundAssetId = b.get("bound_asset_id").isNull() ? null : uuid(b.get("bound_asset_id"));
        String reason = text(b.get("reason"), 500);
        QuarantineDecisionCommand cmd = new QuarantineDecisionCommand(quarantineId, decision, boundAssetId, reason);
        String key = idempotencyKey(r);
        String digest = digest("QUARANTINE_DECIDE_V1\n" + quarantineId + "\n" + decision + "\n" + boundAssetId + "\n" + reason);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> quarantineDto(repository.resolveQuarantine(a, cmd)));
        return success(result, r);
    }

    @PostMapping(value = "/autoticket-attempts/{id}/manual-create", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object manualCreateAutoTicket(@PathVariable("id") String rawId, HttpServletRequest r) {
        Access a = access(r, "ticket_notes:write_internal", true);
        if (r.getQueryString() != null) throw invalid();
        String attemptId = parseUuid(rawId);
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("expected_version", "reason"));
        long expected = version(b.get("expected_version"));
        String reason = text(b.get("reason"), 500);
        ManualAutoTicketCommand cmd = new ManualAutoTicketCommand(attemptId, expected, reason);
        String key = idempotencyKey(r);
        String digest = digest("AUTOTICKET_MANUAL_V1\n" + attemptId + "\n" + expected + "\n" + reason);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest, a.permissionVersion(),
            () -> repository.authorizeWrite(a),
            () -> attemptDto(repository.manualCreateAutoTicket(a, cmd), a.tenantId()));
        return success(result, r);
    }

    private record Subject(Access access, Membership membership) {}

    private Access access(HttpServletRequest r, String capability, boolean internalOnly) {
        return subject(r, capability, internalOnly).access();
    }

    private Subject subject(HttpServletRequest r, String capability, boolean internalOnly) {
        if (r.getUserPrincipal() == null || r.getSession(false) == null) throw expired();
        Object t = r.getSession(false).getAttribute("mop.tenant_id");
        if (!(t instanceof String tenant) || tenant.isBlank()) throw expired();
        String actor = r.getUserPrincipal().getName();
        var m = platform.membership(actor, tenant).orElseThrow(MonitoringEventCenterController::expired);
        if (!m.active() || m.permissionVersion() < 1 || m.permissionVersion() > MAX_VERSION
                || !actor.equals(m.userId()) || !tenant.equals(m.tenantId())) throw expired();
        if (!m.capabilities().contains(capability) || (internalOnly && !Capabilities.internal(m.role())))
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份无权操作监控事件中心");
        return new Subject(new Access(tenant, actor, m.permissionVersion(), trace(r)), m);
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员上下文已失效");
    }

    private static Map<String, Object> eventDto(EventSummary e, String expectedTenant) {
        if (e == null || !expectedTenant.equals(e.tenantId())) throw unavailable();
        return map(
            "id", e.id(),
            "tenant_id", e.tenantId(),
            "asset_id", e.assetId(),
            "version", e.version(),
            "rule_code", e.ruleCode(),
            "rule_name", e.ruleName(),
            "severity", e.severity().name(),
            "severity_rank", e.severityRank(),
            "fingerprint", e.fingerprint(),
            "occurrence_count", e.occurrenceCount(),
            "flap_flip_count", e.flapFlipCount(),
            "flap_silenced_until", e.flapSilencedUntil() == null ? null : e.flapSilencedUntil().toString(),
            "monitor_state", e.monitorState().name(),
            "handling_state", e.handlingState().name(),
            "suppression_state", e.suppressionState().name(),
            "suppression_reason", e.suppressionReason(),
            "notification_empty_state", e.notificationEmptyState().name(),
            "notification_empty_state_label", e.notificationEmptyStateLabel(),
            "sla_unknown_marked", e.slaUnknownMarked(),
            "review_policy_status", e.reviewPolicyStatus(),
            "problem_at", e.problemAt().toString(),
            "last_occurred_at", e.lastOccurredAt().toString(),
            "recovered_at", e.recoveredAt() == null ? null : e.recoveredAt().toString(),
            "first_ack_at", e.firstAckAt() == null ? null : e.firstAckAt().toString(),
            "resolved_at", e.resolvedAt() == null ? null : e.resolvedAt().toString(),
            "closed_at", e.closedAt() == null ? null : e.closedAt().toString(),
            "root_cause_note", e.rootCauseNote(),
            "ticket_id", e.ticketId(),
            "updated_by", e.updatedBy(),
            "updated_at", e.updatedAt().toString()
        );
    }

    private static Map<String, Object> memberDto(EventMember m, String expectedTenant) {
        if (m == null || !expectedTenant.equals(m.tenantId())) throw unavailable();
        return map(
            "id", m.id(),
            "tenant_id", m.tenantId(),
            "event_id", m.eventId(),
            "raw_event_id", m.rawEventId(),
            "sequence_no", m.sequenceNo(),
            "source_event_id", m.sourceEventId(),
            "source_phase", m.sourcePhase().name(),
            "occurred_at", m.occurredAt().toString(),
            "merged_within_window", m.mergedWithinWindow(),
            "recorded_at", m.recordedAt().toString()
        );
    }

    private static Map<String, Object> noiseDto(NoiseRecord n, String expectedTenant) {
        if (n == null || !expectedTenant.equals(n.tenantId())) throw unavailable();
        return map(
            "id", n.id(),
            "tenant_id", n.tenantId(),
            "event_id", n.eventId(),
            "layer", n.layer().name(),
            "detail", n.detail(),
            "recorded_at", n.recordedAt().toString()
        );
    }

    private static Map<String, Object> funnelDto(NoiseFunnelView f, String expectedTenant) {
        if (f == null || !expectedTenant.equals(f.tenantId())) throw unavailable();
        return map(
            "tenant_id", f.tenantId(),
            "window", f.window(),
            "raw_intake_count", f.rawIntakeCount(),
            "merged_deduplicated_count", f.mergedDeduplicatedCount(),
            "flap_silenced_count", f.flapSilencedCount(),
            "dependency_suppressed_count", f.dependencySuppressedCount(),
            "maintenance_silenced_count", f.maintenanceSilencedCount(),
            "effective_alert_count", f.effectiveAlertCount(),
            "observed_at", f.observedAt().toString()
        );
    }

    private static Map<String, Object> windowDto(MaintenanceWindow w, String expectedTenant) {
        if (w == null || !expectedTenant.equals(w.tenantId())) throw unavailable();
        return map(
            "id", w.id(),
            "tenant_id", w.tenantId(),
            "version", w.version(),
            "name", w.name(),
            "asset_id", w.assetId(),
            "starts_at", w.startsAt().toString(),
            "ends_at", w.endsAt().toString(),
            "silence_mode", w.silenceMode(),
            "status", w.status(),
            "sla_exclusion_requested", w.slaExclusionRequested(),
            "sla_exclusion_confirmed", w.slaExclusionConfirmed(),
            "sla_exclusion_confirmed_by", w.slaExclusionConfirmedBy(),
            "sla_exclusion_confirmed_at", w.slaExclusionConfirmedAt() == null ? null : w.slaExclusionConfirmedAt().toString(),
            "reason", w.reason(),
            "created_by", w.createdBy(),
            "updated_by", w.updatedBy(),
            "updated_at", w.updatedAt().toString()
        );
    }

    private static Map<String, Object> quarantineDto(QuarantineItem q) {
        if (q == null) throw unavailable();
        return map(
            "quarantine_id", q.quarantineId(),
            "pool_id", q.poolId(),
            "source_event_id", q.sourceEventId(),
            "source_phase", q.sourcePhase(),
            "claimed_tenant_id", q.claimedTenantId(),
            "claimed_asset_id", q.claimedAssetId(),
            "quarantine_reason", q.quarantineReason(),
            "occurred_at", q.occurredAt().toString(),
            "received_at", q.receivedAt().toString(),
            "quarantine_status", q.quarantineStatus(),
            "decision", q.decision(),
            "bound_asset_id", q.boundAssetId(),
            "result_event_id", q.resultEventId(),
            "decision_reason", q.decisionReason(),
            "decided_by", q.decidedBy(),
            "decided_at", q.decidedAt() == null ? null : q.decidedAt().toString()
        );
    }

    private static Map<String, Object> attemptDto(AutoTicketAttempt a, String expectedTenant) {
        if (a == null || !expectedTenant.equals(a.tenantId())) throw unavailable();
        return map(
            "id", a.id(),
            "tenant_id", a.tenantId(),
            "event_id", a.eventId(),
            "version", a.version(),
            "attempt_count", a.attemptCount(),
            "max_retries", a.maxRetries(),
            "backoff_schedule_sec", a.backoffScheduleSec(),
            "status", a.status().name(),
            "mapped_priority", a.mappedPriority(),
            "mapping_policy_status", a.mappingPolicyStatus(),
            "ticket_id", a.ticketId(),
            "next_retry_at", a.nextRetryAt() == null ? null : a.nextRetryAt().toString(),
            "last_error", a.lastError(),
            "updated_by", a.updatedBy(),
            "updated_at", a.updatedAt().toString()
        );
    }

    private static Map<String, String> query(HttpServletRequest r, Set<String> allowed) {
        String raw = r.getQueryString();
        if (raw == null) return Map.of();
        if (raw.isEmpty()) throw invalid();
        var out = new LinkedHashMap<String, String>();
        for (String p : raw.split("&", -1)) {
            int eq = p.indexOf('=');
            if (eq <= 0 || eq != p.lastIndexOf('=')) throw invalid();
            String k = decode(p.substring(0, eq)), v = decode(p.substring(eq + 1));
            if (!allowed.contains(k) || v.isEmpty() || out.put(k, v) != null) throw invalid();
        }
        return out;
    }

    private static String decode(String v) {
        if (v.indexOf('+') >= 0) throw invalid();
        var b = new java.io.ByteArrayOutputStream(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '%') {
                if (i + 2 >= v.length()) throw invalid();
                int hi = Character.digit(v.charAt(i + 1), 16), lo = Character.digit(v.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw invalid();
                b.write((hi << 4) | lo);
                i += 2;
            } else if (c <= 0x7f) b.write(c);
            else throw invalid();
        }
        return b.toString(StandardCharsets.UTF_8);
    }

    private static void fields(JsonNode n, Set<String> expected) {
        var names = new HashSet<String>();
        n.fieldNames().forEachRemaining(names::add);
        if (!names.equals(expected)) throw invalid();
    }

    private static long version(JsonNode n) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong()) throw invalid();
        long v = n.longValue();
        if (v < 1 || v > MAX_VERSION) throw invalid();
        return v;
    }

    private static String uuid(JsonNode n) {
        if (n == null || !n.isTextual()) throw invalid();
        return parseUuid(n.textValue());
    }

    private static String parseUuid(String s) {
        if (s == null || !s.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) throw invalid();
        return s;
    }

    private static Instant parseInstant(JsonNode n) {
        if (n == null || !n.isTextual()) throw invalid();
        try { return Instant.parse(n.textValue()); } catch (Exception e) { throw invalid(); }
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode n, Class<E> cls) {
        if (n == null || !n.isTextual()) throw invalid();
        try { return Enum.valueOf(cls, n.textValue()); } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static String text(JsonNode n, int max) {
        if (n == null || !n.isTextual()) throw invalid();
        String s = n.textValue();
        if (s == null || s.isBlank() || s.codePointCount(0, s.length()) > max) throw invalid();
        return s.trim();
    }

    private static int size(String raw, int def, int max) {
        if (raw == null) return def;
        if (!raw.matches("^[1-9][0-9]{0,2}$")) throw invalid();
        int v = Integer.parseInt(raw);
        if (v < 1 || v > max) throw invalid();
        return v;
    }

    private static String idempotencyKey(HttpServletRequest r) {
        var vals = Collections.list(r.getHeaders("Idempotency-Key"));
        if (vals.size() != 1) throw invalid();
        String k = vals.get(0);
        if (k == null || k.length() < 8 || k.length() > 128 || !k.matches("^[A-Za-z0-9._:-]+$")) throw invalid();
        return k;
    }

    private static String route(HttpServletRequest r) {
        String c = r.getContextPath(), u = r.getRequestURI();
        return c != null && !c.isEmpty() && u.startsWith(c) ? u.substring(c.length()) : u;
    }

    private static String digest(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> map(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "监控事件中心或维护窗口请求参数无效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "监控事件中心持久化不可用，未返回替代结果"); }
}
