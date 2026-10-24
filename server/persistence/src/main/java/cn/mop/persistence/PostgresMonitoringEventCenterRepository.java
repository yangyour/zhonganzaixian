package cn.mop.persistence;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import cn.mop.domain.monitoring.MonitoringEventPipeline;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL repository for V37 D15 Monitoring Event Center, Deduplication (xN),
 * Flap Silence, Dependency Suppression, Maintenance Windows, Quarantine Manual Decisions,
 * and Auto-Ticket Creation (M1-2, M4-7..M4-11, M4-14, M5-4).
 */
public final class PostgresMonitoringEventCenterRepository implements MonitoringEventCenterRepository {
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    /** Shares the event transaction for recurrence intents and frozen-deadline reminder tasks. */
    private final PostgresTicketWorkflowRepository ticketWorkflows;

    public PostgresMonitoringEventCenterRepository(DataSource source, PostgresPlatformRepository platform) {
        this.source = source;
        this.platform = platform;
        this.ticketWorkflows = new PostgresTicketWorkflowRepository(source, platform,
            new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection c) throws SQLException; }

    private <T> T read(Access a, String capability, boolean internalOnly, SqlWork<T> work) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                member(c, a, capability, internalOnly, false);
                T out = work.run(c);
                c.commit();
                return out;
            } catch (Throwable t) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw t;
            }
        } catch (DomainException e) {
            throw e;
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public void authorizeWrite(Access a) {
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public void authorizeAdminWrite(Access a) {
        Connection c = platform.idempotentCommandConnection();
        try {
            String role = member(c, a, "configurations:write", true, true);
            if (!"padmin".equals(role)) {
                throw new DomainException(403, "ACTION_FORBIDDEN", "仅服务经理/平台管理员可执行SLA排除确认");
            }
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public EventCenterWorkspace workspace(Access a, String window, int pageSize) {
        if (pageSize < 1 || pageSize > 50) throw invalid();
        String normWindow = normalizeWindow(window);
        return read(a, "tickets:read", true, c -> {
            Instant observed = now(c);
            List<EventSummary> events = loadEvents(c, a.tenantId(), pageSize);
            NoiseFunnelView funnel = computeNoiseFunnel(c, a.tenantId(), normWindow, observed);
            List<MaintenanceWindow> windows = loadMaintenanceWindows(c, a.tenantId(), pageSize);
            List<QuarantineItem> quarantine = loadQuarantineItems(c, a.tenantId(), pageSize);
            List<AutoTicketAttempt> attempts = loadAutoTicketAttempts(c, a.tenantId(), pageSize);
            return new EventCenterWorkspace(a.tenantId(), events, funnel, windows, quarantine, attempts, observed);
        });
    }

    @Override
    public EventDetailView eventDetail(Access a, String eventId) {
        if (eventId == null || eventId.isBlank()) throw invalid();
        return read(a, "tickets:read", true, c -> {
            Instant observed = now(c);
            EventSummary event = findEvent(c, a.tenantId(), eventId)
                .orElseThrow(() -> new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该监控事件"));
            List<EventMember> members = loadEventMembers(c, a.tenantId(), eventId);
            List<NoiseRecord> noise = loadNoiseRecords(c, a.tenantId(), eventId);
            Optional<AutoTicketAttempt> attempt = findAutoTicketAttemptByEvent(c, a.tenantId(), eventId);
            return new EventDetailView(event, members, noise, attempt, observed);
        });
    }

    @Override
    public NoiseFunnelView noiseFunnel(Access a, String window) {
        String normWindow = normalizeWindow(window);
        return read(a, "tickets:read", true, c -> {
            Instant observed = now(c);
            return computeNoiseFunnel(c, a.tenantId(), normWindow, observed);
        });
    }

    @Override
    public EventSummary ingestEvent(Access a, IngestEventCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            return ingestInternal(c, a, cmd, null);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    private EventSummary ingestInternal(Connection c, Access a, IngestEventCommand cmd, String rawEventId) throws SQLException {
        if (cmd.simulateAutoTicketFailure()) MonitoringSimulationGuard.requireIsolated(c, a.tenantId());
        String assetName = requireAssetName(c, a.tenantId(), cmd.assetId());
        String fp = MonitoringEventPipeline.fingerprint(a.tenantId(), cmd.assetId(), cmd.ruleCode());
        lock(c, "mop.monitoring_event|" + fp);
        Instant now = now(c);
        Instant occurred = cmd.occurredAt() != null ? cmd.occurredAt() : now;

        // Collector redelivery is independent of the HTTP idempotency key.
        try (var s = statement(c, "SELECT e.id::text FROM public.mop_monitoring_event e JOIN public.mop_monitoring_event_member m ON m.tenant_id=e.tenant_id AND m.event_id=e.id WHERE e.tenant_id=?::uuid AND e.fingerprint=? AND m.source_event_id=? AND m.source_phase=? ORDER BY m.recorded_at DESC LIMIT 1")) {
            s.setString(1, a.tenantId()); s.setString(2, fp); s.setString(3, cmd.sourceEventId().trim()); s.setString(4, cmd.sourcePhase().name());
            try (var r = s.executeQuery()) {
                if (r.next()) return findEvent(c, a.tenantId(), r.getString(1)).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
            }
        }
        Optional<EventSummary> latestOpen = findLatestOpenEventByFingerprint(c, a.tenantId(), fp);
        if (latestOpen.isPresent() && occurred.isBefore(latestOpen.get().lastOccurredAt()))
            throw new DomainException(422, "EVENT_SIGNAL_OUT_OF_ORDER", "较早监控信号不能覆盖较新的状态，请核对来源时间");
        if (latestOpen.isEmpty() && cmd.sourcePhase() == MonitorState.RECOVERED)
            throw new DomainException(422, "RECOVERY_WITHOUT_PROBLEM", "恢复信号缺少当前租户同设备同规则的故障事件");
        if (latestOpen.isPresent() && latestOpen.get().monitorState() == MonitorState.RECOVERED
            && cmd.sourcePhase() == MonitorState.RECOVERED) return latestOpen.get();
        boolean terminalTicket = false;
        if (latestOpen.isPresent() && latestOpen.get().ticketId() != null) {
            lock(c, "mop.ticket.workflow|" + a.tenantId() + "|" + latestOpen.get().ticketId());
            terminalTicket = ticketWorkflows.terminal(c, a.tenantId(), latestOpen.get().ticketId());
        }
        String upstreamProblemAsset = findActiveUpstreamProblemAsset(c, a.tenantId(), cmd.assetId());
        Optional<MaintenanceWindow> activeMw = findActiveMaintenanceWindow(c, a.tenantId(), cmd.assetId(), occurred);

        String eventId;
        int nextSeq;
        boolean merged;
        FlapDecision flapDecision;
        SuppressionDecision suppression;

        if (latestOpen.isPresent() && !(terminalTicket && cmd.sourcePhase() == MonitorState.PROBLEM)
            && (latestOpen.get().monitorState() == MonitorState.PROBLEM
            || cmd.sourcePhase() == MonitorState.RECOVERED
            || MonitoringEventPipeline.shouldMergeWithinWindow(latestOpen.get().lastOccurredAt(), occurred, latestOpen.get().handlingState()))) {
            EventSummary prev = latestOpen.get();
            eventId = prev.id();
            nextSeq = prev.occurrenceCount() + 1;
            merged = MonitoringEventPipeline.shouldMergeWithinWindow(prev.lastOccurredAt(), occurred, prev.handlingState());
            boolean phaseFlipped = prev.monitorState() != cmd.sourcePhase();
            flapDecision = MonitoringEventPipeline.evaluateFlap(
                prev.flapFlipCount(), phaseFlipped, prev.lastOccurredAt(), occurred, prev.flapSilencedUntil()
            );
            suppression = MonitoringEventPipeline.evaluateSuppression(
                cmd.severity(), flapDecision.flapTriggered(), upstreamProblemAsset, activeMw.map(MaintenanceWindow::name).orElse(null)
            );
            HandlingState nextHandling = MonitoringEventPipeline.nextHandlingStateOnSignal(prev.handlingState(), cmd.sourcePhase());
            Instant recoveredAt = cmd.sourcePhase() == MonitorState.RECOVERED
                ? (prev.monitorState() == MonitorState.RECOVERED ? prev.recoveredAt() : occurred) : null;
            long nextVersion = prev.version() + 1;

            try (var s = statement(c, "UPDATE public.mop_monitoring_event SET version=?,occurrence_count=?,flap_flip_count=?,flap_silenced_until=?,monitor_state=?,handling_state=?,suppression_state=?,suppression_reason=?,notification_empty_state=?,sla_unknown_marked=?,last_occurred_at=?,recovered_at=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
                s.setLong(1, nextVersion);
                s.setInt(2, nextSeq);
                s.setInt(3, flapDecision.flipCountInWindow());
                if (flapDecision.silencedUntil() == null) s.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 4, flapDecision.silencedUntil());
                s.setString(5, cmd.sourcePhase().name());
                s.setString(6, nextHandling.name());
                s.setString(7, suppression.suppressionState().name());
                if (suppression.suppressionReason() == null) s.setNull(8, Types.VARCHAR); else s.setString(8, suppression.suppressionReason());
                s.setString(9, suppression.notificationEmptyState().name());
                s.setBoolean(10, prev.slaUnknownMarked() || suppression.slaUnknownMarked());
                instant(s, 11, occurred);
                if (recoveredAt == null) s.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 12, recoveredAt);
                s.setString(13, a.actorId());
                instant(s, 14, now);
                s.setString(15, a.tenantId());
                s.setString(16, eventId);
                s.setLong(17, prev.version());
                if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "监控事件并发更新冲突");
            }
        } else {
            eventId = UUID.randomUUID().toString();
            nextSeq = 1;
            merged = false;
            flapDecision = new FlapDecision(0, false, null);
            suppression = MonitoringEventPipeline.evaluateSuppression(
                cmd.severity(), false, upstreamProblemAsset, activeMw.map(MaintenanceWindow::name).orElse(null)
            );
            HandlingState initialHandling = cmd.sourcePhase() == MonitorState.RECOVERED ? HandlingState.PENDING_REVIEW : HandlingState.NEW;
            Instant recoveredAt = cmd.sourcePhase() == MonitorState.RECOVERED ? occurred : null;

            try (var s = statement(c, "INSERT INTO public.mop_monitoring_event(id,tenant_id,asset_id,version,rule_code,rule_name,severity,severity_rank,fingerprint,occurrence_count,flap_flip_count,flap_silenced_until,monitor_state,handling_state,suppression_state,suppression_reason,notification_empty_state,sla_unknown_marked,review_policy_status,problem_at,last_occurred_at,recovered_at,updated_by,updated_at) VALUES(?::uuid,?::uuid,?::uuid,1,?,?,?,?,?,1,0,NULL,?,?,?,?,?,?,'CONFIG_PENDING_Q29',?,?,?,?::uuid,?)")) {
                s.setString(1, eventId);
                s.setString(2, a.tenantId());
                s.setString(3, cmd.assetId());
                s.setString(4, cmd.ruleCode().trim().toUpperCase(Locale.ROOT));
                s.setString(5, cmd.ruleName().trim());
                s.setString(6, cmd.severity().name());
                s.setInt(7, cmd.severity().rank());
                s.setString(8, fp);
                s.setString(9, cmd.sourcePhase().name());
                s.setString(10, initialHandling.name());
                s.setString(11, suppression.suppressionState().name());
                if (suppression.suppressionReason() == null) s.setNull(12, Types.VARCHAR); else s.setString(12, suppression.suppressionReason());
                s.setString(13, suppression.notificationEmptyState().name());
                s.setBoolean(14, suppression.slaUnknownMarked());
                instant(s, 15, occurred);
                instant(s, 16, occurred);
                if (recoveredAt == null) s.setNull(17, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 17, recoveredAt);
                s.setString(18, a.actorId());
                instant(s, 19, now);
                s.executeUpdate();
            }
        }

        try (var s = statement(c, "INSERT INTO public.mop_monitoring_event_member(id,tenant_id,event_id,raw_event_id,sequence_no,source_event_id,source_phase,occurred_at,merged_within_window,recorded_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?)")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, a.tenantId());
            s.setString(3, eventId);
            if (rawEventId == null) s.setNull(4, Types.OTHER); else s.setString(4, rawEventId);
            s.setInt(5, nextSeq);
            s.setString(6, cmd.sourceEventId().trim());
            s.setString(7, cmd.sourcePhase().name());
            instant(s, 8, occurred);
            s.setBoolean(9, merged);
            instant(s, 10, now);
            s.executeUpdate();
        }

        appendNoiseRecord(c, a.tenantId(), eventId, NoiseLayer.RAW_INTAKE, "原始监控事件到达: " + cmd.sourceEventId() + " (" + cmd.sourcePhase().name() + ")", now);
        if (merged) {
            appendNoiseRecord(c, a.tenantId(), eventId, NoiseLayer.MERGE, "5分钟窗口内同指纹归并(×" + nextSeq + ")", now);
        }
        if (suppression.primaryNoiseLayer() != NoiseLayer.EFFECTIVE_ALERT) {
            appendNoiseRecord(c, a.tenantId(), eventId, suppression.primaryNoiseLayer(),
                suppression.suppressionReason() != null ? suppression.suppressionReason() : suppression.primaryNoiseLayer().name(), now);
        } else if (nextSeq == 1 && cmd.sourcePhase() == MonitorState.PROBLEM) {
            appendNoiseRecord(c, a.tenantId(), eventId, NoiseLayer.EFFECTIVE_ALERT, "通过归并/防抖/依赖/维护窗口过滤，生成有效告警事件", now);
        }

        if (cmd.sourcePhase() == MonitorState.PROBLEM) {
            processAutoTicketForEvent(c, a, eventId, cmd.assetId(), assetName, cmd.ruleCode(), cmd.ruleName(),
                cmd.severity(), suppression.suppressionState(), cmd.simulateAutoTicketFailure(), now);
            if (latestOpen.isPresent() && latestOpen.get().id().equals(eventId)
                    && latestOpen.get().monitorState() == MonitorState.RECOVERED
                    && latestOpen.get().ticketId() != null) {
                ticketWorkflows.monitoringRecurrence(c, a.tenantId(), latestOpen.get().ticketId(),
                    eventId, nextSeq, suppression.suppressionState() == SuppressionState.NONE, now);
            }
        }

        platform.audit(a.actorId(), a.tenantId(), "monitoring.event.ingest", "monitoring_event", eventId, "SUCCEEDED",
            "EVENT_" + cmd.ruleCode() + "_OCCURRENCES_" + nextSeq + "_STATE_" + cmd.sourcePhase().name() + "_SUPP_" + suppression.suppressionState().name(), a.traceId());
        return findEvent(c, a.tenantId(), eventId).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
    }

    private void processAutoTicketForEvent(
        Connection c,
        Access a,
        String eventId,
        String assetId,
        String assetName,
        String ruleCode,
        String ruleName,
        Severity severity,
        SuppressionState suppressionState,
        boolean simulateFailure,
        Instant now
    ) throws SQLException {
        Optional<AutoTicketAttempt> existing = findAutoTicketAttemptByEvent(c, a.tenantId(), eventId);
        if (existing.isPresent() && (existing.get().status() == AutoTicketStatus.SUCCEEDED
            || existing.get().status() == AutoTicketStatus.MANUAL_CREATED
            || existing.get().status() == AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED)) {
            return;
        }
        // Further raw signals must not spend a scheduled retry budget before its durable deadline.
        if (existing.isPresent() && existing.get().status() == AutoTicketStatus.RETRY_SCHEDULED
            && existing.get().nextRetryAt() != null && existing.get().nextRetryAt().isAfter(now)) return;
        int prevAttempts = existing.map(AutoTicketAttempt::attemptCount).orElse(0);
        AutoTicketPlan plan = MonitoringEventPipeline.evaluateAutoTicketAttempt(
            severity, suppressionState, prevAttempts, simulateFailure, now
        );
        String ticketId = existing.map(AutoTicketAttempt::ticketId).orElse(null);
        if (plan.status() == AutoTicketStatus.SUCCEEDED && ticketId == null) {
            EventSummary event = findEvent(c, a.tenantId(), eventId)
                .orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
            ticketId = reuseOrCreateTicket(c, a, event, assetName,
                "[" + severity.name() + "] " + ruleName + " (" + ruleCode + ")",
                "监控事件中心自动建单: event_id=" + eventId + ", rule_code=" + ruleCode
                    + ", severity=" + severity.name(), now);
            linkTicketToEvent(c, a.tenantId(), eventId, ticketId, a.actorId(), now);
        }

        if (existing.isEmpty()) {
            try (var s = statement(c, "INSERT INTO public.mop_autoticket_attempt(id,tenant_id,event_id,version,attempt_count,max_retries,backoff_schedule_sec,status,mapped_priority,mapping_policy_status,ticket_id,next_retry_at,last_error,updated_by,updated_at) VALUES(?::uuid,?::uuid,?::uuid,1,?,5,'30,120,600,600,600',?,?,'CONFIG_PENDING_Q14',?::uuid,?,?,?::uuid,?)")) {
                s.setString(1, UUID.randomUUID().toString());
                s.setString(2, a.tenantId());
                s.setString(3, eventId);
                s.setInt(4, plan.attemptCount());
                s.setString(5, plan.status().name());
                if (plan.mappedPriority() == null) s.setNull(6, Types.VARCHAR); else s.setString(6, plan.mappedPriority());
                if (ticketId == null) s.setNull(7, Types.OTHER); else s.setString(7, ticketId);
                if (plan.nextRetryAt() == null) s.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 8, plan.nextRetryAt());
                if (plan.lastError() == null) s.setNull(9, Types.VARCHAR); else s.setString(9, plan.lastError());
                s.setString(10, a.actorId());
                instant(s, 11, now);
                s.executeUpdate();
            }
        } else {
            long nextVer = existing.get().version() + 1;
            try (var s = statement(c, "UPDATE public.mop_autoticket_attempt SET version=?,attempt_count=?,status=?,ticket_id=?::uuid,next_retry_at=?,last_error=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
                s.setLong(1, nextVer);
                s.setInt(2, plan.attemptCount());
                s.setString(3, plan.status().name());
                if (ticketId == null) s.setNull(4, Types.OTHER); else s.setString(4, ticketId);
                if (plan.nextRetryAt() == null) s.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 5, plan.nextRetryAt());
                if (plan.lastError() == null) s.setNull(6, Types.VARCHAR); else s.setString(6, plan.lastError());
                s.setString(7, a.actorId());
                instant(s, 8, now);
                s.setString(9, a.tenantId());
                s.setString(10, existing.get().id());
                s.setLong(11, existing.get().version());
                if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "自动建单尝试版本已变化，本次事务未提交");
            }
        }
    }

    @Override
    public EventSummary transitionEvent(Access a, TransitionEventCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            lock(c, "mop.monitoring_event_id|" + a.tenantId() + "|" + cmd.eventId());
            EventSummary current = findEvent(c, a.tenantId(), cmd.eventId())
                .orElseThrow(() -> new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该监控事件"));
            if (current.ticketId() != null)
                lock(c, "mop.ticket.workflow|" + a.tenantId() + "|" + current.ticketId());
            if (current.version() != cmd.expectedVersion())
                throw new DomainException(409, "VERSION_CONFLICT", "监控事件版本已变化，请刷新后重试");

            HandlingState nextState = MonitoringEventPipeline.transitionHandlingState(
                current.handlingState(), current.monitorState(), cmd.action(), cmd.rootCauseNote()
            );
            Instant now = now(c);
            Instant firstAck = current.firstAckAt() != null ? current.firstAckAt()
                : (nextState == HandlingState.ACKNOWLEDGED || nextState == HandlingState.IN_PROGRESS ? now : null);
            Instant resolvedAt = nextState == HandlingState.RESOLVED ? now : current.resolvedAt();
            Instant closedAt = nextState == HandlingState.CLOSED ? now : current.closedAt();
            String rootCause = cmd.rootCauseNote() != null && !cmd.rootCauseNote().isBlank()
                ? cmd.rootCauseNote().trim() : current.rootCauseNote();
            long nextVer = current.version() + 1;

            try (var s = statement(c, "UPDATE public.mop_monitoring_event SET version=?,handling_state=?,first_ack_at=?,resolved_at=?,closed_at=?,root_cause_note=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
                s.setLong(1, nextVer);
                s.setString(2, nextState.name());
                if (firstAck == null) s.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 3, firstAck);
                if (resolvedAt == null) s.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 4, resolvedAt);
                if (closedAt == null) s.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 5, closedAt);
                if (rootCause == null) s.setNull(6, Types.VARCHAR); else s.setString(6, rootCause);
                s.setString(7, a.actorId());
                instant(s, 8, now);
                s.setString(9, a.tenantId());
                s.setString(10, cmd.eventId());
                s.setLong(11, current.version());
                if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "监控事件版本已变化，请刷新后重试");
            }
            platform.audit(a.actorId(), a.tenantId(), "monitoring.event.transition", "monitoring_event", cmd.eventId(), "SUCCEEDED",
                "EVENT_TRANSITION_" + current.handlingState().name() + "_TO_" + nextState.name() + "_V" + nextVer, a.traceId());
            return findEvent(c, a.tenantId(), cmd.eventId()).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public MaintenanceWindow createMaintenanceWindow(Access a, CreateMaintenanceWindowCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            requireAssetName(c, a.tenantId(), cmd.assetId());
            Instant now = now(c);
            String id = UUID.randomUUID().toString();
            String status = !now.isBefore(cmd.startsAt()) && now.isBefore(cmd.endsAt()) ? "ACTIVE" : "SCHEDULED";
            try (var s = statement(c, "INSERT INTO public.mop_maintenance_window(id,tenant_id,version,name,asset_id,starts_at,ends_at,silence_mode,status,sla_exclusion_requested,sla_exclusion_confirmed,sla_exclusion_confirmed_by,sla_exclusion_confirmed_at,reason,created_by,updated_by,updated_at) VALUES(?::uuid,?::uuid,1,?,?::uuid,?,?,?,?,?,false,NULL,NULL,?,?::uuid,?::uuid,?)")) {
                s.setString(1, id);
                s.setString(2, a.tenantId());
                s.setString(3, cmd.name().trim());
                s.setString(4, cmd.assetId());
                instant(s, 5, cmd.startsAt());
                instant(s, 6, cmd.endsAt());
                s.setString(7, cmd.silenceMode());
                s.setString(8, status);
                s.setBoolean(9, cmd.slaExclusionRequested());
                s.setString(10, cmd.reason().trim());
                s.setString(11, a.actorId());
                s.setString(12, a.actorId());
                instant(s, 13, now);
                s.executeUpdate();
            }
            platform.audit(a.actorId(), a.tenantId(), "maintenance_window.create", "maintenance_window", id, "SUCCEEDED",
                "MAINTENANCE_WINDOW_" + cmd.silenceMode() + "_SLA_REQ_" + cmd.slaExclusionRequested(), a.traceId());
            return findMaintenanceWindow(c, a.tenantId(), id).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public MaintenanceWindow updateMaintenanceWindow(Access a, UpdateMaintenanceWindowCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            String action = cmd.action().trim().toUpperCase(Locale.ROOT);
            if ("CONFIRM_SLA_EXCLUSION".equals(action)) {
                String role = member(c, a, "configurations:write", true, true);
                if (!"padmin".equals(role)) {
                    throw new DomainException(403, "ACTION_FORBIDDEN", "仅服务经理/平台管理员可确认维护窗口SLA排除(M4-10)");
                }
            } else {
                member(c, a, "ticket_notes:write_internal", true, true);
            }
            lock(c, "mop.maintenance_window|" + a.tenantId() + "|" + cmd.windowId());
            MaintenanceWindow current = findMaintenanceWindow(c, a.tenantId(), cmd.windowId())
                .orElseThrow(() -> new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该维护窗口"));
            if (current.version() != cmd.expectedVersion())
                throw new DomainException(409, "VERSION_CONFLICT", "维护窗口版本已变化，请刷新后重试");

            Instant now = now(c);
            Instant nextEndsAt = current.endsAt();
            String nextStatus = current.status();
            boolean nextConfirmed = current.slaExclusionConfirmed();
            String nextConfirmedBy = current.slaExclusionConfirmedBy();
            Instant nextConfirmedAt = current.slaExclusionConfirmedAt();

            switch (action) {
                case "EXTEND" -> {
                    if (cmd.newEndsAt() == null || !cmd.newEndsAt().isAfter(current.endsAt()))
                        throw new DomainException(400, "VALIDATION_ERROR", "延长维护窗口的结束时间必须晚于原结束时间");
                    nextEndsAt = cmd.newEndsAt();
                }
                case "END" -> {
                    nextStatus = now.isAfter(current.startsAt()) ? "ENDED" : "CANCELLED";
                    if (now.isAfter(current.startsAt()) && now.isBefore(current.endsAt())) nextEndsAt=now;
                }
                case "CONFIRM_SLA_EXCLUSION" -> {
                    if (current.createdBy().equals(a.actorId()))
                        throw new DomainException(403,"SLA_EXCLUSION_SELF_APPROVAL","SLA排除审批人不得为申请人本人");
                    if (!current.slaExclusionRequested())
                        throw new DomainException(422, "SLA_EXCLUSION_NOT_REQUESTED", "该维护窗口未申请SLA排除，不可确认");
                    nextConfirmed = true;
                    nextConfirmedBy = a.actorId();
                    nextConfirmedAt = now;
                }
                default -> throw invalid();
            }

            long nextVer = current.version() + 1;
            try (var s = statement(c, "UPDATE public.mop_maintenance_window SET version=?,ends_at=?,status=?,sla_exclusion_confirmed=?,sla_exclusion_confirmed_by=?::uuid,sla_exclusion_confirmed_at=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
                s.setLong(1, nextVer);
                instant(s, 2, nextEndsAt);
                s.setString(3, nextStatus);
                s.setBoolean(4, nextConfirmed);
                if (nextConfirmedBy == null) s.setNull(5, Types.OTHER); else s.setString(5, nextConfirmedBy);
                if (nextConfirmedAt == null) s.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 6, nextConfirmedAt);
                s.setString(7, a.actorId());
                instant(s, 8, now);
                s.setString(9, a.tenantId());
                s.setString(10, cmd.windowId());
                s.setLong(11, current.version());
                if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "维护窗口版本已变化，请刷新后重试");
            }
            platform.audit(a.actorId(), a.tenantId(), "maintenance_window." + action.toLowerCase(Locale.ROOT), "maintenance_window", cmd.windowId(), "SUCCEEDED",
                "MAINTENANCE_WINDOW_" + action + "_V" + nextVer + "_SLA_CONFIRMED_" + nextConfirmed, a.traceId());
            return findMaintenanceWindow(c, a.tenantId(), cmd.windowId()).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public QuarantineItem resolveQuarantine(Access a, QuarantineDecisionCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            lock(c, "mop.quarantine_decision|" + cmd.quarantineId());
            QuarantineItem q = findQuarantineItem(c, a.tenantId(), cmd.quarantineId())
                .orElseThrow(() -> new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户隔离队列中不存在该候选记录"));
            if (q.decision() != null)
                throw new DomainException(409, "QUARANTINE_ALREADY_DECIDED", "该隔离事件已完成人工核验，不可重复裁决");

            Instant now = now(c);
            String resultEventId = null;
            if ("BIND_ASSET".equals(cmd.decision())) {
                MonitorState phase = "RECOVERED".equals(q.sourcePhase()) ? MonitorState.RECOVERED : MonitorState.PROBLEM;
                EventSummary promoted = ingestInternal(c, a, new IngestEventCommand(
                    cmd.boundAssetId(),
                    "QUARANTINE_PROMOTED",
                    "隔离核验放行监控事件",
                    Severity.HIGH,
                    phase,
                    q.sourceEventId(),
                    q.occurredAt(),
                    false
                ), null);
                resultEventId = promoted.id();
            }

            try (var s = statement(c, "INSERT INTO public.mop_monitoring_quarantine_decision(quarantine_id,tenant_id,decision,bound_asset_id,result_event_id,reason,decided_by,decided_at) VALUES(?::uuid,?::uuid,?,?::uuid,?::uuid,?,?::uuid,?)")) {
                s.setString(1, cmd.quarantineId());
                s.setString(2, a.tenantId());
                s.setString(3, cmd.decision());
                if (cmd.boundAssetId() == null) s.setNull(4, Types.OTHER); else s.setString(4, cmd.boundAssetId());
                if (resultEventId == null) s.setNull(5, Types.OTHER); else s.setString(5, resultEventId);
                s.setString(6, cmd.reason().trim());
                s.setString(7, a.actorId());
                instant(s, 8, now);
                s.executeUpdate();
            }
            platform.audit(a.actorId(), a.tenantId(), "monitoring.quarantine.decide", "monitoring_quarantine", cmd.quarantineId(), "SUCCEEDED",
                "QUARANTINE_" + cmd.decision() + (resultEventId != null ? "_EVENT_" + resultEventId : ""), a.traceId());
            return findQuarantineItem(c, a.tenantId(), cmd.quarantineId()).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    /** Due registration attempts for one explicitly configured tenant; this is not a global queue or provider sender. */
    public List<AutoTicketAttempt> dueRegistrationRetries(Access a, int limit) {
        if (limit < 1 || limit > 25) throw new DomainException(400, "VALIDATION_ERROR", "重试批次必须在1至25之间");
        return read(a, "ticket_notes:write_internal", true, c -> {
            var result = new ArrayList<AutoTicketAttempt>();
            try (var s = statement(c, ATTEMPT_SELECT + " WHERE tenant_id=?::uuid AND status='RETRY_SCHEDULED' AND next_retry_at<=statement_timestamp() ORDER BY next_retry_at,id LIMIT ?")) {
                s.setString(1, a.tenantId()); s.setInt(2, limit);
                try (var rows = s.executeQuery()) { while (rows.next()) result.add(mapAttempt(rows)); }
            }
            return List.copyOf(result);
        });
    }

    /** Must run in the existing authorized/idempotent transaction. Rechecks deadline and live event under its row lock. */
    public AutoTicketAttempt retryRegistration(Access a, String attemptId, long expectedVersion) {
        if (attemptId == null || expectedVersion < 1) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            AutoTicketAttempt initial = findAutoTicketAttemptById(c, a.tenantId(), attemptId).orElseThrow(() -> new DomainException(404,"RESOURCE_NOT_FOUND","当前租户下不存在该建单记录"));
            EventSummary initialEvent = findEvent(c, a.tenantId(), initial.eventId())
                .orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
            lock(c, "mop.monitoring_event|" + initialEvent.fingerprint());
            // Match ingestion: fingerprint first, then event row, then attempt CAS.
            try (var s = statement(c, "SELECT id FROM public.mop_monitoring_event WHERE tenant_id=?::uuid AND id=?::uuid FOR UPDATE")) {
                s.setString(1, a.tenantId()); s.setString(2, initial.eventId());
                try (var rows = s.executeQuery()) { if (!rows.next()) throw unavailable(); }
            }
            AutoTicketAttempt current = findAutoTicketAttemptById(c, a.tenantId(), attemptId).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
            if (current.version() != expectedVersion) throw new DomainException(409,"VERSION_CONFLICT","自动建单记录已变化，请重新读取");
            Instant now = now(c);
            if (current.status() != AutoTicketStatus.RETRY_SCHEDULED || current.nextRetryAt() == null || current.nextRetryAt().isAfter(now))
                throw new DomainException(409,"AUTOTICKET_NOT_DUE","该记录当前不满足到期重试条件");
            EventSummary event = findEvent(c, a.tenantId(), current.eventId()).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
            if (current.ticketId() != null || event.ticketId() != null) throw new DomainException(409,"AUTOTICKET_ALREADY_LINKED","事件已有工单关联，停止自动补建并等待核验");
            String stopStatus = null, stopReason = null;
            boolean currentlySuppressed = findActiveMaintenanceWindow(c,a.tenantId(),event.assetId(),now).isPresent()
                || findActiveUpstreamProblemAsset(c,a.tenantId(),event.assetId()) != null
                || event.flapSilencedUntil() != null && event.flapSilencedUntil().isAfter(now);
            if (currentlySuppressed || event.monitorState() != MonitorState.PROBLEM || event.handlingState() == HandlingState.RESOLVED || event.handlingState() == HandlingState.CLOSED || event.suppressionState() != SuppressionState.NONE) {
                stopStatus = "SUPPRESSED_SKIPPED"; stopReason = "到期复核时事件已恢复、已处理或处于抑制状态，不再自动登记工单";
            } else if (current.attemptCount() >= current.maxRetries()) {
                stopStatus = "QUARANTINED_MANUAL_REQUIRED"; stopReason = "建单尝试已达上限，等待人工复核";
            }
            if (stopStatus != null) {
                try (var s = statement(c, "UPDATE public.mop_autoticket_attempt SET version=version+1,status=?,next_retry_at=NULL,last_error=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
                    s.setString(1, stopStatus); s.setString(2, stopReason); s.setString(3,a.actorId()); instant(s,4,now);s.setString(5,a.tenantId());s.setString(6,attemptId);s.setLong(7,current.version());
                    if(s.executeUpdate()!=1)throw new DomainException(409,"VERSION_CONFLICT","到期建单记录已变化，本次事务未提交");
                }
            } else {
                processAutoTicketForEvent(c,a,event.id(),event.assetId(),requireAssetName(c,a.tenantId(),event.assetId()),event.ruleCode(),event.ruleName(),event.severity(),event.suppressionState(),false,now);
            }
            platform.audit(a.actorId(),a.tenantId(),"monitoring.autoticket.registration_retry","autoticket_attempt",attemptId,"SUCCEEDED",stopStatus==null?"REGISTRATION_ONLY_Q14_OUTBOX_REMAINS_BLOCKED":stopStatus,a.traceId());
            return findAutoTicketAttemptById(c,a.tenantId(),attemptId).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
        } catch (SQLException e) { throw unavailable(); }
    }

    @Override
    public AutoTicketAttempt manualCreateAutoTicket(Access a, ManualAutoTicketCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            lock(c, "mop.autoticket_attempt|" + a.tenantId() + "|" + cmd.attemptId());
            AutoTicketAttempt current = findAutoTicketAttemptById(c, a.tenantId(), cmd.attemptId())
                .orElseThrow(() -> new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该建单隔离记录"));
            EventSummary initialEvent = findEvent(c, a.tenantId(), current.eventId())
                .orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
            lock(c, "mop.monitoring_event|" + initialEvent.fingerprint());
            current = findAutoTicketAttemptById(c, a.tenantId(), cmd.attemptId())
                .orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
            if (current.version() != cmd.expectedVersion())
                throw new DomainException(409, "VERSION_CONFLICT", "建单尝试记录版本已变化，请刷新后重试");
            if (current.status() != AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED && current.status() != AutoTicketStatus.RETRY_SCHEDULED)
                throw new DomainException(422, "AUTOTICKET_NOT_ELIGIBLE_FOR_MANUAL", "仅重试失败或隔离状态的建单记录允许人工补建(M5-4)");

            EventSummary event = findEvent(c, a.tenantId(), current.eventId())
                .orElseThrow(() -> new DomainException(404, "RESOURCE_NOT_FOUND", "关联监控事件不存在"));
            if (current.ticketId() != null || event.ticketId() != null) {
                throw new DomainException(409, "AUTOTICKET_ALREADY_LINKED", "事件已有工单关联，请重新读取");
            }
            String assetName = requireAssetName(c, a.tenantId(), event.assetId());
            Instant now = now(c);
            String ticketId = reuseOrCreateTicket(c, a, event, assetName,
                "[人工补建-" + event.severity().name() + "] " + event.ruleName() + " (" + event.ruleCode() + ")",
                "建单隔离队列人工补建(M5-4): reason=" + cmd.reason().trim() + ", event_id=" + event.id(), now);
            linkTicketToEvent(c, a.tenantId(), event.id(), ticketId, a.actorId(), now);

            long nextVer = current.version() + 1;
            int nextCount = Math.min(6, current.attemptCount() + 1);
            try (var s = statement(c, "UPDATE public.mop_autoticket_attempt SET version=?,attempt_count=?,status='MANUAL_CREATED',ticket_id=?::uuid,next_retry_at=NULL,last_error=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
                s.setLong(1, nextVer);
                s.setInt(2, nextCount);
                s.setString(3, ticketId);
                s.setString(4, "人工补建成功: " + cmd.reason().trim());
                s.setString(5, a.actorId());
                instant(s, 6, now);
                s.setString(7, a.tenantId());
                s.setString(8, current.id());
                s.setLong(9, current.version());
                if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "建单尝试记录版本已变化，请刷新后重试");
            }
            platform.audit(a.actorId(), a.tenantId(), "monitoring.autoticket.manual_create", "autoticket_attempt", current.id(), "SUCCEEDED",
                "AUTOTICKET_MANUAL_CREATED_TICKET_" + ticketId, a.traceId());
            return findAutoTicketAttemptById(c, a.tenantId(), current.id()).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    /** The existing event/attempt/audit transaction owns both the reused link and a newly created registration. */
    private String reuseOrCreateTicket(Connection c, Access a, EventSummary event, String assetName,
                                       String title, String description, Instant now) throws SQLException {
        Optional<MonitoringTicketDedupe.Match> reused = MonitoringTicketDedupe.find(
            c, a.tenantId(), event.id(), event.fingerprint(), event.problemAt(), now
        );
        if (reused.isPresent()) {
            MonitoringTicketDedupe.Match match = reused.get();
            platform.audit(a.actorId(), a.tenantId(), "monitoring.autoticket.recurrence_link",
                "monitoring_event", event.id(), "SUCCEEDED",
                "PREVIOUS_EVENT_" + match.eventId() + "_TICKET_" + match.ticketId()
                    + "_WINDOW_SEC_" + match.policy().seconds() + "_POLICY_" + match.policy().reference(),
                a.traceId());
            return match.ticketId();
        }
        return insertAutoCreatedTicket(c, a.tenantId(), a.actorId(), event.assetId(), assetName,
            title, description, now);
    }

    private String insertAutoCreatedTicket(Connection c, String tenant, String actor, String assetId, String assetName, String title, String description, Instant now) throws SQLException {
        String ticketId = UUID.randomUUID().toString();
        String opId = UUID.randomUUID().toString();
        try (var s = statement(c, "INSERT INTO public.mop_ticket(id,tenant_id,receipt_no,title,description,source,category,impact_scope,urgency,asset_id,asset_name,reporter_id,contact_name,contact_phone,operation_id,created_at,updated_at) VALUES(?::uuid,?::uuid,?,?,?,'customer_report','设备异常','partial_area','urgent',?::uuid,?,?::uuid,'系统监控事件（未绑定客户联系人）','',?::uuid,?,?)")) {
            s.setString(1, ticketId);
            s.setString(2, tenant);
            s.setString(3, "EVT-" + ticketId.substring(0, 18));
            s.setString(4, title.length() > 200 ? title.substring(0, 200) : title);
            s.setString(5, description);
            s.setString(6, assetId);
            s.setString(7, assetName);
            s.setString(8, actor);
            s.setString(9, opId);
            instant(s, 10, now);
            instant(s, 11, now);
            s.executeUpdate();
        }
        try (var s = statement(c, "INSERT INTO public.mop_ticket_history(tenant_id,ticket_id,version,payload) VALUES(?::uuid,?::uuid,1,jsonb_build_object('action','MONITORING_REGISTER','actor_id',?::text,'occurred_at',?::text,'version',1))")) {
            s.setString(1, tenant);
            s.setString(2, ticketId);
            s.setString(3, actor);
            s.setString(4, now.toString());
            s.executeUpdate();
        }
        try (var s = statement(c, "INSERT INTO public.mop_operation(id,tenant_id,ticket_id,kind,result_ref,error,created_at,updated_at) VALUES(?::uuid,?::uuid,?::uuid,'CUSTOMER_REPORT_REGISTRATION',?,'{\"code\":\"CONFIG_PENDING\",\"message\":\"监控事件登记已保存，正式工单与通知规则待配置\",\"retryable\":false,\"question_ids\":[\"Q14\"]}'::jsonb,?,?)")) {
            s.setString(1, opId);
            s.setString(2, tenant);
            s.setString(3, ticketId);
            s.setString(4, "/api/v1/tickets/" + ticketId);
            instant(s, 5, now);
            instant(s, 6, now);
            s.executeUpdate();
        }
        try (var s = statement(c, "INSERT INTO public.mop_outbox(id,tenant_id,ticket_id,operation_id,topic,payload,blocked_question_ids,created_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,'ticket.customer_report.registered','{\"kind\":\"MONITORING_AUTOTICKET\"}'::jsonb,'[\"Q14\"]'::jsonb,?)")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, tenant);
            s.setString(3, ticketId);
            s.setString(4, opId);
            instant(s, 5, now);
            s.executeUpdate();
        }
        return ticketId;
    }

    private void linkTicketToEvent(Connection c, String tenant, String eventId, String ticketId, String actor, Instant now) throws SQLException {
        EventSummary ev = findEvent(c, tenant, eventId).orElseThrow(PostgresMonitoringEventCenterRepository::unavailable);
        try (var s = statement(c, "UPDATE public.mop_monitoring_event SET version=?,ticket_id=?::uuid,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
            s.setLong(1, ev.version() + 1);
            s.setString(2, ticketId);
            s.setString(3, actor);
            instant(s, 4, now);
            s.setString(5, tenant);
            s.setString(6, eventId);
            s.setLong(7, ev.version());
            if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "监控事件关联版本已变化，本次事务未提交");
        }
        if (ev.monitorState() == MonitorState.PROBLEM) {
            ticketWorkflows.monitoringRecurrence(c, tenant, ticketId, eventId, ev.occurrenceCount(),
                ev.suppressionState() == SuppressionState.NONE, now);
        }
    }

    private NoiseFunnelView computeNoiseFunnel(Connection c, String tenant, String window, Instant observed) throws SQLException {
        long lookbackSec = switch (window) {
            case "7d" -> 7L * 86400L;
            case "30d" -> 30L * 86400L;
            default -> 86400L;
        };
        Instant since = observed.minusSeconds(lookbackSec);
        long rawCount = 0;
        long mergeCount = 0;
        long flapCount = 0;
        long depCount = 0;
        long maintCount = 0;
        long effectiveCount = 0;
        try (var s = statement(c, "SELECT layer, count(*) FROM public.mop_event_noise_record WHERE tenant_id=?::uuid AND recorded_at>=? GROUP BY layer")) {
            s.setString(1, tenant);
            instant(s, 2, since);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    String layer = r.getString(1);
                    long cnt = r.getLong(2);
                    switch (layer) {
                        case "RAW_INTAKE" -> rawCount = cnt;
                        case "MERGE" -> mergeCount = cnt;
                        case "FLAP" -> flapCount = cnt;
                        case "DEPENDENCY" -> depCount = cnt;
                        case "MAINTENANCE" -> maintCount = cnt;
                        case "EFFECTIVE_ALERT" -> effectiveCount = cnt;
                    }
                }
            }
        }
        return new NoiseFunnelView(tenant, window, rawCount, mergeCount, flapCount, depCount, maintCount, effectiveCount, observed);
    }

    private void appendNoiseRecord(Connection c, String tenant, String eventId, NoiseLayer layer, String detail, Instant at) throws SQLException {
        try (var s = statement(c, "INSERT INTO public.mop_event_noise_record(id,tenant_id,event_id,layer,detail,recorded_at) VALUES(?::uuid,?::uuid,?::uuid,?,?,?)")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, tenant);
            s.setString(3, eventId);
            s.setString(4, layer.name());
            s.setString(5, detail.length() > 500 ? detail.substring(0, 500) : detail);
            instant(s, 6, at);
            s.executeUpdate();
        }
    }

    private String findActiveUpstreamProblemAsset(Connection c, String tenant, String childAssetId) throws SQLException {
        try (var s = statement(c, "SELECT e.parent_id::text FROM public.mop_asset_dependency_graph g "
            + "JOIN public.mop_asset_dependency_edge e ON e.tenant_id=g.tenant_id AND e.revision_version=g.version "
            + "JOIN public.mop_monitoring_event me ON me.tenant_id=e.tenant_id AND me.asset_id=e.parent_id "
            + "WHERE g.tenant_id=?::uuid AND e.child_id=?::uuid AND COALESCE(e.proposed_enabled,true) "
            + "AND me.monitor_state='PROBLEM' AND me.handling_state<>'CLOSED' ORDER BY me.last_occurred_at DESC LIMIT 1")) {
            s.setString(1, tenant);
            s.setString(2, childAssetId);
            try (var r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    private Optional<MaintenanceWindow> findActiveMaintenanceWindow(Connection c, String tenant, String assetId, Instant at) throws SQLException {
        try (var s = statement(c, "SELECT id::text,tenant_id::text,version,name,asset_id::text,starts_at,ends_at,silence_mode,status,sla_exclusion_requested,sla_exclusion_confirmed,sla_exclusion_confirmed_by::text,sla_exclusion_confirmed_at,reason,created_by::text,updated_by::text,updated_at FROM public.mop_maintenance_window WHERE tenant_id=?::uuid AND asset_id=?::uuid AND status IN ('SCHEDULED','ACTIVE') AND starts_at<=? AND ends_at>? ORDER BY starts_at DESC LIMIT 1")) {
            s.setString(1, tenant);
            s.setString(2, assetId);
            instant(s, 3, at);
            instant(s, 4, at);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(mapMaintenanceWindow(r)) : Optional.empty();
            }
        }
    }

    private Optional<EventSummary> findLatestOpenEventByFingerprint(Connection c, String tenant, String fingerprint) throws SQLException {
        try (var s = statement(c, EVENT_SELECT + " WHERE tenant_id=?::uuid AND fingerprint=? AND handling_state<>'CLOSED' ORDER BY last_occurred_at DESC, id DESC LIMIT 1")) {
            s.setString(1, tenant);
            s.setString(2, fingerprint);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(mapEvent(r)) : Optional.empty();
            }
        }
    }

    private Optional<EventSummary> findEvent(Connection c, String tenant, String eventId) throws SQLException {
        try (var s = statement(c, EVENT_SELECT + " WHERE tenant_id=?::uuid AND id=?::uuid")) {
            s.setString(1, tenant);
            s.setString(2, eventId);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(mapEvent(r)) : Optional.empty();
            }
        }
    }

    private List<EventSummary> loadEvents(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<EventSummary>();
        try (var s = statement(c, EVENT_SELECT + " WHERE tenant_id=?::uuid ORDER BY last_occurred_at DESC, id DESC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) list.add(mapEvent(r));
            }
        }
        return list;
    }

    private static final String EVENT_SELECT =
        "SELECT id::text,tenant_id::text,asset_id::text,version,rule_code,rule_name,severity,severity_rank,fingerprint,occurrence_count,flap_flip_count,flap_silenced_until,monitor_state,handling_state,suppression_state,suppression_reason,notification_empty_state,sla_unknown_marked,review_policy_status,problem_at,last_occurred_at,recovered_at,first_ack_at,resolved_at,closed_at,root_cause_note,ticket_id::text,updated_by::text,updated_at FROM public.mop_monitoring_event";

    private static EventSummary mapEvent(ResultSet r) throws SQLException {
        NotificationEmptyState emptyState = NotificationEmptyState.valueOf(r.getString("notification_empty_state"));
        return new EventSummary(
            r.getString("id"),
            r.getString("tenant_id"),
            r.getString("asset_id"),
            r.getLong("version"),
            r.getString("rule_code"),
            r.getString("rule_name"),
            Severity.valueOf(r.getString("severity")),
            r.getInt("severity_rank"),
            r.getString("fingerprint"),
            r.getInt("occurrence_count"),
            r.getInt("flap_flip_count"),
            nullableTime(r, "flap_silenced_until"),
            MonitorState.valueOf(r.getString("monitor_state")),
            HandlingState.valueOf(r.getString("handling_state")),
            SuppressionState.valueOf(r.getString("suppression_state")),
            r.getString("suppression_reason"),
            emptyState,
            emptyState.label(),
            r.getBoolean("sla_unknown_marked"),
            r.getString("review_policy_status"),
            time(r, "problem_at"),
            time(r, "last_occurred_at"),
            nullableTime(r, "recovered_at"),
            nullableTime(r, "first_ack_at"),
            nullableTime(r, "resolved_at"),
            nullableTime(r, "closed_at"),
            r.getString("root_cause_note"),
            r.getString("ticket_id"),
            r.getString("updated_by"),
            time(r, "updated_at")
        );
    }

    private List<EventMember> loadEventMembers(Connection c, String tenant, String eventId) throws SQLException {
        var list = new ArrayList<EventMember>();
        try (var s = statement(c, "SELECT id::text,tenant_id::text,event_id::text,raw_event_id::text,sequence_no,source_event_id,source_phase,occurred_at,merged_within_window,recorded_at FROM public.mop_monitoring_event_member WHERE tenant_id=?::uuid AND event_id=?::uuid ORDER BY sequence_no ASC")) {
            s.setString(1, tenant);
            s.setString(2, eventId);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    list.add(new EventMember(
                        r.getString("id"), r.getString("tenant_id"), r.getString("event_id"), r.getString("raw_event_id"),
                        r.getInt("sequence_no"), r.getString("source_event_id"), MonitorState.valueOf(r.getString("source_phase")),
                        time(r, "occurred_at"), r.getBoolean("merged_within_window"), time(r, "recorded_at")
                    ));
                }
            }
        }
        return list;
    }

    private List<NoiseRecord> loadNoiseRecords(Connection c, String tenant, String eventId) throws SQLException {
        var list = new ArrayList<NoiseRecord>();
        try (var s = statement(c, "SELECT id::text,tenant_id::text,event_id::text,layer,detail,recorded_at FROM public.mop_event_noise_record WHERE tenant_id=?::uuid AND event_id=?::uuid ORDER BY recorded_at ASC, id ASC")) {
            s.setString(1, tenant);
            s.setString(2, eventId);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    list.add(new NoiseRecord(
                        r.getString("id"), r.getString("tenant_id"), r.getString("event_id"),
                        NoiseLayer.valueOf(r.getString("layer")), r.getString("detail"), time(r, "recorded_at")
                    ));
                }
            }
        }
        return list;
    }

    private Optional<MaintenanceWindow> findMaintenanceWindow(Connection c, String tenant, String id) throws SQLException {
        try (var s = statement(c, "SELECT id::text,tenant_id::text,version,name,asset_id::text,starts_at,ends_at,silence_mode,status,sla_exclusion_requested,sla_exclusion_confirmed,sla_exclusion_confirmed_by::text,sla_exclusion_confirmed_at,reason,created_by::text,updated_by::text,updated_at FROM public.mop_maintenance_window WHERE tenant_id=?::uuid AND id=?::uuid")) {
            s.setString(1, tenant);
            s.setString(2, id);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(mapMaintenanceWindow(r)) : Optional.empty();
            }
        }
    }

    private List<MaintenanceWindow> loadMaintenanceWindows(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<MaintenanceWindow>();
        try (var s = statement(c, "SELECT id::text,tenant_id::text,version,name,asset_id::text,starts_at,ends_at,silence_mode,status,sla_exclusion_requested,sla_exclusion_confirmed,sla_exclusion_confirmed_by::text,sla_exclusion_confirmed_at,reason,created_by::text,updated_by::text,updated_at FROM public.mop_maintenance_window WHERE tenant_id=?::uuid ORDER BY starts_at DESC, id DESC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) list.add(mapMaintenanceWindow(r));
            }
        }
        return list;
    }

    private static MaintenanceWindow mapMaintenanceWindow(ResultSet r) throws SQLException {
        return new MaintenanceWindow(
            r.getString("id"), r.getString("tenant_id"), r.getLong("version"), r.getString("name"), r.getString("asset_id"),
            time(r, "starts_at"), time(r, "ends_at"), r.getString("silence_mode"), r.getString("status"),
            r.getBoolean("sla_exclusion_requested"), r.getBoolean("sla_exclusion_confirmed"),
            r.getString("sla_exclusion_confirmed_by"), nullableTime(r, "sla_exclusion_confirmed_at"),
            r.getString("reason"), r.getString("created_by"), r.getString("updated_by"), time(r, "updated_at")
        );
    }

    private Optional<QuarantineItem> findQuarantineItem(Connection c, String tenant, String quarantineId) throws SQLException {
        try (var s = statement(c, QUARANTINE_SELECT + " WHERE q.id=?::uuid AND (q.claimed_tenant_id=?::uuid OR q.claimed_tenant_id IS NULL)")) {
            s.setString(1, quarantineId);
            s.setString(2, tenant);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(mapQuarantine(r)) : Optional.empty();
            }
        }
    }

    private List<QuarantineItem> loadQuarantineItems(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<QuarantineItem>();
        try (var s = statement(c, QUARANTINE_SELECT + " WHERE q.claimed_tenant_id=?::uuid OR q.claimed_tenant_id IS NULL ORDER BY q.received_epoch_second DESC, q.id DESC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) list.add(mapQuarantine(r));
            }
        }
        return list;
    }

    private static final String QUARANTINE_SELECT =
        "SELECT q.id::text AS q_id, q.pool_id::text, q.source_event_id, q.source_phase, q.claimed_tenant_id::text, q.claimed_asset_id::text, q.quarantine_reason, q.occurred_epoch_second, q.occurred_nano, q.received_epoch_second, q.received_nano, q.quarantine_status, d.decision, d.bound_asset_id::text, d.result_event_id::text, d.reason AS decision_reason, d.decided_by::text, d.decided_at "
        + "FROM public.mop_monitoring_quarantine q LEFT JOIN public.mop_monitoring_quarantine_decision d ON d.quarantine_id=q.id";

    private static QuarantineItem mapQuarantine(ResultSet r) throws SQLException {
        return new QuarantineItem(
            r.getString("q_id"),
            r.getString("pool_id"),
            r.getString("source_event_id"),
            r.getString("source_phase"),
            r.getString("claimed_tenant_id"),
            r.getString("claimed_asset_id"),
            r.getString("quarantine_reason"),
            Instant.ofEpochSecond(r.getLong("occurred_epoch_second"), r.getInt("occurred_nano")),
            Instant.ofEpochSecond(r.getLong("received_epoch_second"), r.getInt("received_nano")),
            r.getString("quarantine_status"),
            r.getString("decision"),
            r.getString("bound_asset_id"),
            r.getString("result_event_id"),
            r.getString("decision_reason"),
            r.getString("decided_by"),
            nullableTime(r, "decided_at")
        );
    }

    private Optional<AutoTicketAttempt> findAutoTicketAttemptByEvent(Connection c, String tenant, String eventId) throws SQLException {
        try (var s = statement(c, ATTEMPT_SELECT + " WHERE tenant_id=?::uuid AND event_id=?::uuid")) {
            s.setString(1, tenant);
            s.setString(2, eventId);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(mapAttempt(r)) : Optional.empty();
            }
        }
    }

    private Optional<AutoTicketAttempt> findAutoTicketAttemptById(Connection c, String tenant, String attemptId) throws SQLException {
        try (var s = statement(c, ATTEMPT_SELECT + " WHERE tenant_id=?::uuid AND id=?::uuid")) {
            s.setString(1, tenant);
            s.setString(2, attemptId);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(mapAttempt(r)) : Optional.empty();
            }
        }
    }

    private List<AutoTicketAttempt> loadAutoTicketAttempts(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<AutoTicketAttempt>();
        try (var s = statement(c, ATTEMPT_SELECT + " WHERE tenant_id=?::uuid ORDER BY updated_at DESC, id DESC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) list.add(mapAttempt(r));
            }
        }
        return list;
    }

    private static final String ATTEMPT_SELECT =
        "SELECT id::text,tenant_id::text,event_id::text,version,attempt_count,max_retries,backoff_schedule_sec,status,mapped_priority,mapping_policy_status,ticket_id::text,next_retry_at,last_error,updated_by::text,updated_at FROM public.mop_autoticket_attempt";

    private static AutoTicketAttempt mapAttempt(ResultSet r) throws SQLException {
        return new AutoTicketAttempt(
            r.getString("id"), r.getString("tenant_id"), r.getString("event_id"), r.getLong("version"),
            r.getInt("attempt_count"), r.getInt("max_retries"), r.getString("backoff_schedule_sec"),
            AutoTicketStatus.valueOf(r.getString("status")), r.getString("mapped_priority"),
            r.getString("mapping_policy_status"), r.getString("ticket_id"),
            nullableTime(r, "next_retry_at"), r.getString("last_error"),
            r.getString("updated_by"), time(r, "updated_at")
        );
    }

    private static String requireAssetName(Connection c, String tenant, String assetId) throws SQLException {
        try (var s = statement(c, "SELECT name FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid")) {
            s.setString(1, tenant);
            s.setString(2, assetId);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该资产");
                return r.getString(1);
            }
        }
    }

    private static String normalizeWindow(String window) {
        if (window == null || window.isBlank()) return "24h";
        String w = window.trim().toLowerCase(Locale.ROOT);
        if (!"24h".equals(w) && !"7d".equals(w) && !"30d".equals(w)) throw invalid();
        return w;
    }

    private static String member(Connection c, Access a, String capability, boolean internalOnly, boolean lock) throws SQLException {
        if (a == null) throw expired();
        PostgresAuthorization.require(c, a.actorId(), a.tenantId(), a.permissionVersion(), capability);
        try (var s = statement(c, "SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=?" + (lock ? " FOR SHARE OF m,u" : ""))) {
            s.setString(1, a.tenantId());
            s.setString(2, a.actorId());
            s.setLong(3, a.permissionVersion());
            try (var r = s.executeQuery()) {
                if (!r.next()) throw expired();
                String role = r.getString(1);
                if (internalOnly && !Capabilities.internal(role))
                    throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份无权访问监控事件中心或维护窗口");
                return role;
            }
        }
    }

    private static void lock(Connection c, String key) throws SQLException {
        try (var s = statement(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) { r.next(); }
        }
    }

    @Override
    public List<String> readinessProblems() {
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true);
            try (var s = statement(c, "SELECT current_database()='mop_test' AND current_user='mop_test_runtime'"
                + " AND has_table_privilege(current_user,'public.mop_monitoring_event','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_monitoring_event','DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_monitoring_event_member','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_monitoring_event_member','UPDATE,DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_event_noise_record','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_event_noise_record','UPDATE,DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_maintenance_window','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_maintenance_window','DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_monitoring_quarantine_decision','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_monitoring_quarantine_decision','UPDATE,DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_autoticket_attempt','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_autoticket_attempt','DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_configuration','SELECT')"
                + " AND has_table_privilege(current_user,'public.mop_runtime_configuration_release','SELECT')"
                + " AND has_table_privilege(current_user,'public.mop_ticket_workflow_authority','SELECT')"
                + " AND has_table_privilege(current_user,'public.mop_ticket_workflow_event','SELECT')"
                + " AND has_table_privilege(current_user,'public.mop_ticket_lifecycle_revision','SELECT')"
                + " AND (SELECT count(*)=6 FROM pg_trigger WHERE tgname IN ('mop_monitoring_event_guard','mop_monitoring_event_member_guard','mop_event_noise_record_guard','mop_maintenance_window_guard','mop_monitoring_quarantine_decision_guard','mop_autoticket_attempt_guard') AND tgenabled='O')");
                 var r = s.executeQuery()) {
                if (!r.next() || !r.getBoolean(1)) return List.of("MONITORING_EVENT_CENTER_SCHEMA_OR_ACL_INVALID");
            }
            return List.of();
        } catch (SQLException | RuntimeException e) {
            return List.of("MONITORING_EVENT_CENTER_SCHEMA_OR_ACCESS_UNAVAILABLE");
        }
    }

    private static PreparedStatement statement(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(10);
        return s;
    }
    private static void instant(PreparedStatement s, int n, Instant t) throws SQLException {
        s.setObject(n, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
    }
    private static Instant time(ResultSet r, String k) throws SQLException {
        return r.getObject(k, OffsetDateTime.class).toInstant();
    }
    private static Instant nullableTime(ResultSet r, String k) throws SQLException {
        OffsetDateTime v = r.getObject(k, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
    private static Instant now(Connection c) throws SQLException {
        try (var s = statement(c, "SELECT date_trunc('milliseconds',clock_timestamp())"); var r = s.executeQuery()) {
            r.next();
            return r.getObject(1, OffsetDateTime.class).toInstant();
        }
    }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "监控事件中心或维护窗口请求参数无效"); }
    private static DomainException expired() { return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员上下文已失效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "监控事件中心持久化不可用，未返回替代结果"); }
}
