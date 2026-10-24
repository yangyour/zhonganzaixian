package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.Access;
import cn.mop.domain.monitoring.MonitoringEventPipeline;
import cn.mop.domain.monitoring.RecoveryObservation;
import cn.mop.domain.monitoring.RecoverySourceBatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import static cn.mop.persistence.PostgresRuntimeConfigurationRepository.map;

/** V29/V37 immutable evidence and event transition in one authorized idempotent transaction. No startup migration. */
public final class PostgresMonitoringRecoveryRepository {
    public static final String POLICY_KEY = "monitor.threshold_matrix";

    @FunctionalInterface
    public interface SourceReader {
        RecoverySourceBatch read(String tenant, String asset, String metric);
    }

    public record Command(String eventId, long expectedVersion, String releaseId, long releaseVersion,
                          String commandSha256) {
        public Command {
            uuid(eventId);
            uuid(releaseId);
            if (expectedVersion < 1 || releaseVersion < 1 || commandSha256 == null
                    || !commandSha256.matches("[0-9a-f]{64}")) {
                throw invalid();
            }
        }
    }

    private record Event(String id, String asset, String fingerprint, String ruleCode, long version,
                         int sequence, String monitorState, String handlingState, Instant lastOccurredAt,
                         String ticket, String suppression, int flips, Instant silenceUntil) {}
    private record Policy(String id, long version, Instant effectiveAt, RecoveryObservation.Rule rule) {}

    private final DataSource source;
    private final PostgresPlatformRepository platform;
    private final SourceReader reader;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final String definitionHash;

    public PostgresMonitoringRecoveryRepository(DataSource source, PostgresPlatformRepository platform,
                                               SourceReader reader, String definitionHash) {
        if (definitionHash == null || !definitionHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Recovery policy definition hash required");
        }
        this.source = source;
        this.platform = platform;
        this.reader = reader;
        this.definitionHash = definitionHash;
    }

    public void authorizeCommand(Access access, String eventId) {
        try {
            Connection c = platform.idempotentCommandConnection();
            authorize(c, access, true);
            event(c, access.tenantId(), eventId);
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /** GET never reads the external source or creates evidence. Missing publication remains explicit. */
    public Map<String, Object> inspect(Access access, String eventId) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                authorize(c, access, false);
                Event event = event(c, access.tenantId(), eventId);
                Instant observed = now(c);
                String proofStatus = event.monitorState().equals("PROBLEM") ? "NOT_CONFIRMED"
                    : PostgresTicketWorkflowRepository.recoveryConfirmed(c, access.tenantId(), eventId) ? "CONFIRMED" : "UNVERIFIED";
                Map<String, Object> result;
                try {
                    Policy policy = policy(c, access.tenantId(), event, observed);
                    result = context(access, event, policy, observed, null, proofStatus);
                } catch (DomainException pending) {
                    if (!List.of("CONFIG_PENDING", "RECOVERY_POLICY_INVALID", "RECOVERY_UNIT_MISMATCH").contains(pending.code)) {
                        throw pending;
                    }
                    result = context(access, event, null, observed, pending.code, proofStatus);
                }
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    public Map<String, Object> observe(Access access, Command command) {
        Connection c = platform.idempotentCommandConnection();
        try {
            authorize(c, access, true);
            Event initial = event(c, access.tenantId(), command.eventId());
            lock(c, "mop.monitoring_event|" + initial.fingerprint());
            lock(c, "mop.runtime.configuration|" + access.tenantId() + "|" + POLICY_KEY);
            if (initial.ticket() != null) {
                lock(c, "mop.ticket.workflow|" + access.tenantId() + "|" + initial.ticket());
            }
            Event current = event(c, access.tenantId(), command.eventId());
            if (current.version() != command.expectedVersion()) {
                throw new DomainException(409, "VERSION_CONFLICT", "监控事件版本已变化，请重新读取");
            }
            if (current.handlingState().equals("CLOSED")
                    || PostgresTicketWorkflowRepository.recoveryConfirmed(c, access.tenantId(), current.id())) {
                throw new DomainException(422, "RECOVERY_NOT_APPLICABLE", "已关闭或已经具备恢复观察证据的事件不能重复确认");
            }
            String assetAddress;
            try (var asset = statement(c, "SELECT management_ip FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid FOR SHARE")) {
                asset.setString(1, access.tenantId());
                asset.setString(2, current.asset());
                try (var rows = asset.executeQuery()) {
                    if (!rows.next()) {
                        throw new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在关联资产");
                    }
                    assetAddress = rows.getString(1);
                }
            }
            Policy policy = policy(c, access.tenantId(), current, now(c));
            if (!policy.id().equals(command.releaseId()) || policy.version() != command.releaseVersion()) {
                throw new DomainException(409, "CONFIG_RELEASE_CONFLICT", "恢复策略已变化，请重新核对当前发布");
            }
            RecoverySourceBatch batch = reader.read(access.tenantId(), current.asset(), policy.rule().metric());
            if (batch == null || !batch.tenantId().toString().equals(access.tenantId())
                    || !batch.assetId().toString().equals(current.asset()) || !batch.metric().equals(policy.rule().metric())
                    || !batch.assetAddress().equals(assetAddress)) {
                throw unavailable();
            }
            Instant observed = now(c);
            if (event(c, access.tenantId(), current.id()).version() != current.version()) {
                throw new DomainException(409, "VERSION_CONFLICT", "来源读取期间事件版本已变化");
            }
            // Expiry during a slow source read cannot authorize a new recovery confirmation.
            Policy afterRead = policy(c, access.tenantId(), current, observed);
            if (!afterRead.id().equals(policy.id()) || afterRead.version() != policy.version()) {
                throw new DomainException(409, "CONFIG_RELEASE_CONFLICT", "来源读取期间恢复策略已变化");
            }
            Instant eligibleFrom = current.lastOccurredAt().isAfter(policy.effectiveAt())
                ? current.lastOccurredAt() : policy.effectiveAt();
            var evaluation = RecoveryObservation.evaluate(policy.rule(), batch.unit(), eligibleFrom, observed, batch.samples());
            // t2 is the source time at which N consecutive samples first passed, confirmed only after the hold.
            // V37 event columns are millisecond precision; the original nanos remain in the evidence/candidate field.
            Instant recoveredAt = evaluation.confirmable()
                ? evaluation.candidateRecoveredAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS) : null;
            String proofId = UUID.randomUUID().toString();
            var response = map("tenant_id", access.tenantId(), "actor_id", access.actorId(), "permission_version", access.permissionVersion(),
                "event_id", current.id(), "event_version", current.version() + (evaluation.confirmable() ? 1 : 0),
                "proof_id", proofId, "status", evaluation.confirmable() ? "RECOVERY_CONFIRMED" : evaluation.status().name(),
                "applied", evaluation.confirmable(), "candidate_recovered_at", string(evaluation.candidateRecoveredAt()),
                "recovered_at", string(recoveredAt), "confirmed_at", evaluation.confirmable() ? observed.toString() : null,
                "policy_release_id", policy.id(), "policy_release_version", policy.version(), "observed_at", observed.toString(),
                "latest_sample_at", string(evaluation.latestSampleAt()), "span_started_at", string(evaluation.spanStartedAt()),
                "span_seconds", evaluation.spanSeconds() == null ? null : evaluation.spanSeconds().toPlainString(),
                "matching_samples", evaluation.matchingSamples(), "source_binding_sha256", batch.bindingSha256(),
                "source", "ZABBIX_SIMPLE_CHECK", "production_approved", false);
            Map<String, Object> evidence = map("schema_version", 1, "event_id", current.id(),
                "event_version_before", current.version(), "policy_release_id", policy.id(),
                "policy_release_version", policy.version(), "policy_definition_hash", definitionHash,
                "eligible_from", eligibleFrom.toString(), "rule", ruleDto(policy.rule()),
                "source_binding_sha256", batch.bindingSha256(), "source", "ZABBIX_SIMPLE_CHECK",
                "sample_unit", batch.unit(), "source_points_read", batch.samples().size(),
                "sample_selection", "AT_OR_AFTER_ELIGIBLE_FROM", "samples", batch.samples().stream()
                    .filter(sample -> !sample.recordedAt().isBefore(eligibleFrom)).map(sample -> map(
                    "recorded_at", sample.recordedAt().toString(), "quality", sample.quality().name(),
                    "value", sample.value() == null ? null : sample.value().toPlainString())).toList(), "result", response);
            String sourceEventId = "recovery-observation:" + current.id() + ":" + command.commandSha256();
            storeProof(c, access, current, batch, proofId, sourceEventId,
                evaluation.confirmable() ? evaluation.candidateRecoveredAt() : observed, observed, evidence);
            if (evaluation.confirmable()) {
                applyRecovery(c, access, current, proofId, sourceEventId, recoveredAt, observed);
            }
            platform.audit(access.actorId(), access.tenantId(), "monitoring.recovery.observe",
                "monitoring_recovery_observation", proofId, "SUCCEEDED",
                "EVENT_" + current.id() + "_RESULT_" + response.get("status") + "_RELEASE_" + policy.id(), access.traceId());
            return response;
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /** Original-command GET recovery. A missing receipt stays 404; it is never inferred from current event state. */
    public Map<String, Object> receipt(Access access, String eventId, String commandSha256) {
        if (commandSha256 == null || !commandSha256.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                authorize(c, access, false);
                event(c, access.tenantId(), eventId);
                try (var s = statement(c, "SELECT r.id::text,r.payload,r.payload_sha256,"
                        + "EXISTS(SELECT 1 FROM public.mop_monitoring_event_member m WHERE m.tenant_id=r.tenant_id "
                        + "AND m.event_id=?::uuid AND m.raw_event_id=r.id) applied "
                        + "FROM public.mop_raw_monitoring_event r WHERE r.tenant_id=?::uuid AND r.source_event_id=? "
                        + "AND r.source_phase='RECOVERED' LIMIT 2")) {
                    s.setString(1, eventId);
                    s.setString(2, access.tenantId());
                    s.setString(3, "recovery-observation:" + eventId + ":" + commandSha256);
                    try (var r = s.executeQuery()) {
                        if (!r.next()) {
                            throw new DomainException(404, "RESOURCE_NOT_FOUND", "尚未找到原恢复观察请求的已提交证据");
                        }
                        byte[] payload = r.getBytes("payload");
                        if (!sha256(payload).equals(r.getString("payload_sha256"))) {
                            throw unavailable();
                        }
                        JsonNode proof;
                        try {
                            proof = json.readTree(payload);
                        } catch (java.io.IOException failure) {
                            throw unavailable();
                        }
                        JsonNode result = proof.path("result");
                        if (proof.path("schema_version").intValue() != 1 || !eventId.equals(proof.path("event_id").asText())
                                || !eventId.equals(result.path("event_id").asText())
                                || !r.getString("id").equals(result.path("proof_id").asText())
                                || !result.path("applied").isBoolean() || result.path("applied").booleanValue() != r.getBoolean("applied")) {
                            throw unavailable();
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> response = json.convertValue(result, java.util.LinkedHashMap.class);
                        if (r.next()) {
                            throw unavailable();
                        }
                        c.commit();
                        return response;
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private void storeProof(Connection c, Access a, Event event, RecoverySourceBatch batch, String id,
                            String sourceId, Instant at, Instant recordedAt, Map<String, Object> evidence) throws SQLException {
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(evidence);
        } catch (java.io.IOException failure) {
            throw unavailable();
        }
        if (payload.length > 1_048_576) {
            throw new DomainException(422, "RECOVERY_EVIDENCE_TOO_LARGE", "恢复证据超出存储预算，未截断采样");
        }
        try (var s = statement(c, "INSERT INTO public.mop_raw_monitoring_event "
                + "(id,pool_id,source_event_id,source_phase,tenant_id,asset_id,occurred_epoch_second,occurred_nano,"
                + "received_epoch_second,received_nano,payload) VALUES(?::uuid,?::uuid,?,'RECOVERED',?::uuid,?::uuid,?,?,?,?,?)")) {
            s.setString(1, id);
            s.setString(2, batch.sourceNamespace().toString());
            s.setString(3, sourceId);
            s.setString(4, a.tenantId());
            s.setString(5, event.asset());
            s.setLong(6, at.getEpochSecond());
            s.setInt(7, at.getNano());
            s.setLong(8, recordedAt.getEpochSecond());
            s.setInt(9, recordedAt.getNano());
            s.setBytes(10, payload);
            s.executeUpdate();
        }
    }

    private void applyRecovery(Connection c, Access a, Event event, String proof, String sourceId, Instant at, Instant recordedAt) throws SQLException {
        var handling = MonitoringEventPipeline.HandlingState.PENDING_REVIEW;
        var flap = MonitoringEventPipeline.evaluateFlap(event.flips(), !event.monitorState().equals("RECOVERED"),
            event.lastOccurredAt(), at, event.silenceUntil());
        try (var s = statement(c, "UPDATE public.mop_monitoring_event SET version=version+1,occurrence_count=occurrence_count+1,"
                + "monitor_state='RECOVERED',handling_state=?,last_occurred_at=?,recovered_at=?,flap_flip_count=?,"
                + "flap_silenced_until=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
            s.setString(1, handling.name());
            instant(s, 2, at);
            instant(s, 3, at);
            s.setInt(4, flap.flipCountInWindow());
            instant(s, 5, flap.silencedUntil());
            s.setString(6, a.actorId());
            instant(s, 7, recordedAt);
            s.setString(8, a.tenantId());
            s.setString(9, event.id());
            s.setLong(10, event.version());
            if (s.executeUpdate() != 1) {
                throw new DomainException(409, "VERSION_CONFLICT", "恢复提交前事件已变化，本次证据与状态均未提交");
            }
        }
        try (var s = statement(c, "INSERT INTO public.mop_monitoring_event_member"
                + "(id,tenant_id,event_id,raw_event_id,sequence_no,source_event_id,source_phase,occurred_at,merged_within_window,recorded_at) "
                + "VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'RECOVERED',?,false,?)")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, a.tenantId());
            s.setString(3, event.id());
            s.setString(4, proof);
            s.setInt(5, Math.incrementExact(event.sequence()));
            s.setString(6, sourceId);
            instant(s, 7, at);
            instant(s, 8, recordedAt);
            s.executeUpdate();
        }
        try (var s = statement(c, "INSERT INTO public.mop_event_noise_record(id,tenant_id,event_id,layer,detail,recorded_at) "
                + "VALUES(?::uuid,?::uuid,?::uuid,'RAW_INTAKE',?,?)")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, a.tenantId());
            s.setString(3, event.id());
            s.setString(4, "已发布策略与服务端来源观察通过；正式恢复t2=" + at + "；证据=" + proof);
            instant(s, 5, recordedAt);
            s.executeUpdate();
        }
        if (event.ticket() != null && event.suppression().equals("NONE")) {
            try (var s = statement(c, "INSERT INTO public.mop_ticket_workflow_outbox"
                    + "(id,tenant_id,ticket_id,event_id,workflow_version,effect_key,event_type,payload,created_at) "
                    + "SELECT ?::uuid,tenant_id,ticket_id,id,version,?,'ticket.monitoring_recovered',"
                    + "jsonb_build_object('ticket_id',ticket_id::text,'workflow_version',version,'event_id',id::text,"
                    + "'event_type','ticket.monitoring_recovered','snapshot_hash',snapshot_hash,'proof_id',?::text),? "
                    + "FROM public.mop_ticket_workflow_event WHERE tenant_id=?::uuid AND ticket_id=?::uuid "
                    + "AND version=(SELECT max(version) FROM public.mop_ticket_workflow_event WHERE tenant_id=?::uuid AND ticket_id=?::uuid) "
                    + "AND snapshot->>'state' NOT IN ('CLOSED','CANCELLED') ON CONFLICT(tenant_id,ticket_id,effect_key) DO NOTHING")) {
                s.setString(1, UUID.randomUUID().toString());
                s.setString(2, "monitoring-recovered:" + event.id() + ":" + (event.version() + 1));
                s.setString(3, proof);
                instant(s, 4, recordedAt);
                s.setString(5, a.tenantId());
                s.setString(6, event.ticket());
                s.setString(7, a.tenantId());
                s.setString(8, event.ticket());
                s.executeUpdate();
            }
        }
    }

    private Policy policy(Connection c, String tenant, Event event, Instant observed) throws SQLException {
        try (var s = statement(c, "SELECT id::text,version,action,value_json,definition_hash,effective_at,expires_at "
                + "FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid AND configuration_key=? "
                + "AND effective_at<=? ORDER BY version DESC LIMIT 1")) {
            s.setString(1, tenant);
            s.setString(2, POLICY_KEY);
            instant(s, 3, observed);
            try (var r = s.executeQuery()) {
                if (!r.next() || r.getString("action").equals("DISABLE")
                        || !definitionHash.equals(r.getString("definition_hash"))
                        || time(r, "expires_at") != null && !observed.isBefore(time(r, "expires_at"))) {
                    throw new DomainException(422, "CONFIG_PENDING", "恢复观察策略尚未有效发布、已失效或定义已变化");
                }
                try {
                    JsonNode matrix = json.readTree(r.getString("value_json"));
                    return new Policy(r.getString("id"), r.getLong("version"), time(r, "effective_at"),
                        MonitoringRecoveryRuleSet.resolve(matrix, event.asset(), event.ruleCode()));
                } catch (java.io.IOException failure) {
                    throw unavailable();
                }
            }
        }
    }

    private static Event event(Connection c, String tenant, String id) throws SQLException {
        uuid(id);
        try (var s = statement(c, "SELECT id::text,asset_id::text,fingerprint,rule_code,version,occurrence_count,monitor_state,"
                + "handling_state,last_occurred_at,ticket_id::text,suppression_state,flap_flip_count,flap_silenced_until "
                + "FROM public.mop_monitoring_event WHERE tenant_id=?::uuid AND id=?::uuid")) {
            s.setString(1, tenant);
            s.setString(2, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该监控事件");
                }
                return new Event(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5),
                    r.getInt(6), r.getString(7), r.getString(8), time(r, "last_occurred_at"), r.getString(10),
                    r.getString(11), r.getInt(12), time(r, "flap_silenced_until"));
            }
        }
    }

    private static Map<String, Object> context(Access a, Event e, Policy p, Instant at, String missing, String proofStatus) {
        return map("tenant_id", a.tenantId(), "actor_id", a.actorId(), "permission_version", a.permissionVersion(),
            "event_id", e.id(), "asset_id", e.asset(), "event_version", e.version(), "monitor_state", e.monitorState(),
            "handling_state", e.handlingState(), "recovery_evidence_status", proofStatus,
            "configuration_status", p == null ? "CONFIG_PENDING" : "ACTIVE", "missing_reason", missing,
            "policy_release_id", p == null ? null : p.id(), "policy_release_version", p == null ? null : p.version(),
            "rule", p == null ? null : ruleDto(p.rule()), "observed_at", at.toString(), "source", "postgresql");
    }

    private static Map<String, Object> ruleDto(RecoveryObservation.Rule rule) {
        return map("metric", rule.metric(), "comparison", rule.comparison().name(), "threshold", rule.threshold().toPlainString(),
            "unit", rule.unit(), "hold_seconds", rule.holdSeconds().toPlainString(),
            "max_gap_seconds", rule.maxGapSeconds().toPlainString(), "minimum_samples", rule.minimumSamples());
    }

    private static void authorize(Connection c, Access a, boolean write) throws SQLException {
        PostgresAuthorization.require(c, a.actorId(), a.tenantId(), a.permissionVersion(),
            write ? "ticket_notes:write_internal" : "tickets:read");
        try (var s = statement(c, "SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id "
                + "WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active "
                + "AND m.role_code IN ('msp','padmin') AND m.permission_version=?" + (write ? " FOR SHARE OF m,u" : ""))) {
            s.setString(1, a.tenantId());
            s.setString(2, a.actorId());
            s.setLong(3, a.permissionVersion());
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new DomainException(403, "ACTION_FORBIDDEN", "仅当前有效内部成员可核验监控恢复");
                }
            }
        }
    }

    private static void lock(Connection c, String value) throws SQLException {
        try (var s = statement(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            s.setString(1, value);
            s.execute();
        }
    }
    private static PreparedStatement statement(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(10);
        return s;
    }
    private static Instant now(Connection c) throws SQLException {
        try (var s = statement(c, "SELECT date_trunc('milliseconds',clock_timestamp())"); var r = s.executeQuery()) {
            r.next();
            return r.getObject(1, OffsetDateTime.class).toInstant();
        }
    }
    private static void instant(PreparedStatement s, int index, Instant value) throws SQLException {
        s.setObject(index, value == null ? null : value.atOffset(ZoneOffset.UTC));
    }
    private static Instant time(ResultSet r, String name) throws SQLException {
        OffsetDateTime value = r.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static String string(Instant value) {
        return value == null ? null : value.toString();
    }
    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static void uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw invalid();
            }
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }
    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "恢复观察请求范围或版本无效");
    }
    private static DomainException unavailable() {
        return new DomainException(503, "RECOVERY_OBSERVATION_UNAVAILABLE", "恢复观察来源或证据存储不可用，本次未确认恢复");
    }
}
