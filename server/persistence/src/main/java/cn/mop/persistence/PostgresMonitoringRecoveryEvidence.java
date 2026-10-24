package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.RecoveryObservation;
import cn.mop.domain.monitoring.RecoverySourceBatch;
import cn.mop.domain.sla.MonitoringRecoveryClockEvidence.SourceTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Validates current-cycle schema2 proof before any SLA read, worker or closing gate consumes exact source time. */
final class PostgresMonitoringRecoveryEvidence {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PostgresMonitoringRecoveryEvidence() { }

    /** Immutable proof envelope plus the current event/member projection, all read on the caller's transaction. */
    record Stored(String tenant, String event, String asset, String address, String ruleCode, String fingerprint,
                  long eventVersion, Instant problemBoundary, Instant summaryRecovery, Instant lastOccurred,
                  Instant memberOccurred, Instant memberRecorded, UUID proofId, UUID sourceNamespace,
                  Instant occurred, Instant received, String payloadSha256, String auditActor, long auditCount) { }

    static Optional<SourceTime> read(Connection connection, String tenant, String event) throws SQLException {
        String sql = "SELECT e.asset_id::text,e.rule_code,e.fingerprint,e.version,e.monitor_state,e.recovered_at,e.last_occurred_at,"
                + "a.management_ip,rm.occurred_at AS member_occurred,rm.recorded_at AS member_recorded,"
                + "rp.id AS proof_id,rp.pool_id,rp.source_event_id,rp.payload,rp.payload_sha256,"
                + "rp.occurred_epoch_second,rp.occurred_nano,rp.received_epoch_second,rp.received_nano,"
                + "(SELECT max(pm.occurred_at) FROM public.mop_monitoring_event_member pm "
                + "WHERE pm.tenant_id=e.tenant_id AND pm.event_id=e.id AND pm.source_phase='PROBLEM') AS problem_boundary,"
                + "au.audit_count,au.audit_actor FROM public.mop_monitoring_event e "
                + "JOIN public.mop_asset a ON a.tenant_id=e.tenant_id AND a.id=e.asset_id "
                + "LEFT JOIN public.mop_monitoring_event_member rm ON rm.tenant_id=e.tenant_id AND rm.event_id=e.id "
                + "AND rm.sequence_no=e.occurrence_count AND rm.source_phase='RECOVERED' "
                + "LEFT JOIN public.mop_raw_monitoring_event rp ON rp.id=rm.raw_event_id AND rp.tenant_id=rm.tenant_id "
                + "AND rp.asset_id=e.asset_id AND rp.source_event_id=rm.source_event_id AND rp.source_phase='RECOVERED' "
                + "LEFT JOIN LATERAL (SELECT count(*) AS audit_count,min(ra.payload->>'actor_id') AS audit_actor "
                + "FROM public.mop_audit ra WHERE ra.tenant_id=e.tenant_id "
                + "AND ra.payload->>'action'='monitoring.recovery.observe' "
                + "AND ra.payload->>'resource_type'='monitoring_recovery_observation' "
                + "AND ra.payload->>'resource_id'=rp.id::text AND ra.payload->>'result'='SUCCEEDED') au ON true "
                + "WHERE e.tenant_id=?::uuid AND e.id=?::uuid";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            statement.setString(1, tenant);
            statement.setString(2, event);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"RECOVERED".equals(rows.getString("monitor_state"))
                        || rows.getString("proof_id") == null) {
                    return Optional.empty();
                }
                String sourceId = rows.getString("source_event_id");
                if (sourceId == null || !sourceId.matches("recovery-observation:" + event + ":[0-9a-f]{64}")) {
                    return Optional.empty();
                }
                byte[] payload = rows.getBytes("payload");
                JsonNode proof = parse(payload, rows.getString("payload_sha256"));
                // Old WIP evidence used the processing time as t2; it must never become precise proof by upgrade.
                if (proof.path("schema_version").intValue() != 2) {
                    return Optional.empty();
                }
                var stored = new Stored(tenant, event, rows.getString("asset_id"), rows.getString("management_ip"),
                        rows.getString("rule_code"), rows.getString("fingerprint"), rows.getLong("version"),
                        time(rows, "problem_boundary"), time(rows, "recovered_at"), time(rows, "last_occurred_at"),
                        time(rows, "member_occurred"), time(rows, "member_recorded"),
                        UUID.fromString(rows.getString("proof_id")), UUID.fromString(rows.getString("pool_id")),
                        Instant.ofEpochSecond(rows.getLong("occurred_epoch_second"), rows.getInt("occurred_nano")),
                        Instant.ofEpochSecond(rows.getLong("received_epoch_second"), rows.getInt("received_nano")),
                        rows.getString("payload_sha256"), rows.getString("audit_actor"), rows.getLong("audit_count"));
                if (rows.next()) {
                    throw invalid();
                }
                validateRelease(connection, proof, stored);
                return Optional.of(verify(stored, payload));
            }
        } catch (DomainException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** Pure proof binding/re-evaluation, separately testable without implying database authentication. */
    static SourceTime verify(Stored stored, byte[] payload) {
        try {
            JsonNode proof = parse(payload, stored.payloadSha256());
            JsonNode result = proof.path("result");
            Instant candidate = instant(result, "candidate_recovered_at");
            Instant confirmed = instant(result, "confirmed_at");
            Instant summary = candidate.truncatedTo(ChronoUnit.MILLIS);
            long before = integer(proof, "event_version_before");
            if (integer(proof, "schema_version") != 2 || integer(result, "evidence_version") != 2
                    || !"SOURCE_NTH_MATCHING_SAMPLE".equals(text(result, "recovery_time_basis"))
                    || !stored.tenant().equals(text(proof, "tenant_id")) || !stored.tenant().equals(text(result, "tenant_id"))
                    || !stored.event().equals(text(proof, "event_id")) || !stored.event().equals(text(result, "event_id"))
                    || !stored.asset().equals(text(proof, "asset_id")) || !stored.address().equals(text(proof, "source_address"))
                    || !stored.ruleCode().equals(text(proof, "rule_code")) || !stored.fingerprint().equals(text(proof, "event_fingerprint"))
                    || !stored.proofId().toString().equals(text(result, "proof_id"))
                    || !stored.sourceNamespace().toString().equals(text(proof, "source_namespace"))
                    || before < 1 || before >= 9_007_199_254_740_990L
                    || integer(result, "event_version") != before + 1 || stored.eventVersion() < before + 1
                    || !result.path("applied").isBoolean() || !result.path("applied").booleanValue()
                    || !"RECOVERY_CONFIRMED".equals(text(result, "status"))
                    || !candidate.equals(stored.occurred()) || !candidate.equals(instant(result, "recovered_at"))
                    || !confirmed.equals(stored.received()) || !confirmed.equals(instant(result, "observed_at"))
                    || candidate.isAfter(confirmed) || !summary.equals(stored.summaryRecovery())
                    || !summary.equals(stored.lastOccurred()) || !summary.equals(stored.memberOccurred())
                    || !confirmed.truncatedTo(ChronoUnit.MILLIS).equals(stored.memberRecorded())
                    || stored.auditCount() != 1 || !text(result, "actor_id").equals(stored.auditActor())
                    || !stored.problemBoundary().equals(instant(proof, "problem_boundary_at"))
                    || !"ZABBIX_SIMPLE_CHECK".equals(text(proof, "source"))
                    || !"ZABBIX_SIMPLE_CHECK".equals(text(result, "source"))
                    || !"COMPLETE_RETURNED_SOURCE_WINDOW".equals(text(proof, "sample_selection"))) {
                throw invalid();
            }
            String binding = text(proof, "source_binding_sha256");
            if (!binding.matches("[0-9a-f]{64}") || !binding.equals(text(result, "source_binding_sha256"))) {
                throw invalid();
            }
            var rule = rule(proof.path("rule"));
            var samples = new ArrayList<RecoveryObservation.Sample>();
            if (!proof.path("samples").isArray() || proof.path("samples").size() > RecoveryObservation.MAX_SAMPLES) {
                throw invalid();
            }
            for (JsonNode sample : proof.path("samples")) {
                samples.add(new RecoveryObservation.Sample(instant(sample, "recorded_at"),
                        RecoveryObservation.Quality.valueOf(text(sample, "quality")),
                        sample.path("value").isNull() ? null : new BigDecimal(text(sample, "value"))));
            }
            if (integer(proof, "source_points_read") != samples.size()) {
                throw invalid();
            }
            var batch = new RecoverySourceBatch(stored.sourceNamespace(), UUID.fromString(stored.tenant()),
                    UUID.fromString(stored.asset()), stored.address(), rule.metric(), text(proof, "sample_unit"), binding,
                    instant(proof, "history_from"), instant(proof, "history_to_exclusive"), samples);
            var eligible = instant(proof, "eligible_from");
            var evaluation = RecoveryObservation.evaluate(rule, batch.unit(), eligible, confirmed, samples);
            var predecessor = RecoveryObservation.requireCoveredTransition(rule, stored.problemBoundary(), eligible, batch, evaluation);
            if (!evaluation.confirmable() || !candidate.equals(evaluation.candidateRecoveredAt())
                    || !predecessor.equals(instant(proof, "transition_predecessor_at"))
                    || !evaluation.spanStartedAt().equals(instant(result, "span_started_at"))
                    || !evaluation.latestSampleAt().equals(instant(result, "latest_sample_at"))
                    || evaluation.matchingSamples() != integer(result, "matching_samples")
                    || evaluation.spanSeconds().compareTo(new BigDecimal(text(result, "span_seconds"))) != 0) {
                throw invalid();
            }
            return new SourceTime(candidate, stored.proofId());
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** Bind the frozen rule to its actual immutable published release, at the historical confirmation time. */
    private static void validateRelease(Connection connection, JsonNode proof, Stored stored) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT version,action,definition_hash,effective_at,expires_at,value_json "
                + "FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid AND id=?::uuid "
                + "AND configuration_key='monitor.threshold_matrix'")) {
            statement.setQueryTimeout(10);
            statement.setString(1, stored.tenant());
            statement.setString(2, text(proof, "policy_release_id"));
            try (var rows = statement.executeQuery()) {
                Instant eligible = instant(proof, "eligible_from");
                if (!rows.next()) {
                    throw invalid();
                }
                Instant effective = time(rows, "effective_at");
                Instant expires = time(rows, "expires_at");
                if (rows.getLong("version") != integer(proof, "policy_release_version")
                        || !text(proof, "policy_release_id").equals(text(proof.path("result"), "policy_release_id"))
                        || integer(proof, "policy_release_version") != integer(proof.path("result"), "policy_release_version")
                        || "DISABLE".equals(rows.getString("action"))
                        || !rows.getString("definition_hash").equals(text(proof, "policy_definition_hash"))
                        || effective.isAfter(stored.received()) || expires != null && !stored.received().isBefore(expires)
                        || !eligible.equals(effective.isAfter(stored.problemBoundary()) ? effective : stored.problemBoundary())) {
                    throw invalid();
                }
                try {
                    var published = MonitoringRecoveryRuleSet.resolve(JSON.readTree(rows.getString("value_json")),
                            stored.asset(), stored.ruleCode());
                    if (!published.equals(rule(proof.path("rule")))) {
                        throw invalid();
                    }
                } catch (java.io.IOException | RuntimeException malformed) {
                    throw invalid();
                }
            }
        }
    }

    private static RecoveryObservation.Rule rule(JsonNode value) {
        return new RecoveryObservation.Rule(text(value, "metric"), RecoveryObservation.Comparison.valueOf(text(value, "comparison")),
                new BigDecimal(text(value, "threshold")), text(value, "unit"), new BigDecimal(text(value, "hold_seconds")),
                new BigDecimal(text(value, "max_gap_seconds")), Math.toIntExact(integer(value, "minimum_samples")));
    }

    private static JsonNode parse(byte[] bytes, String expectedHash) {
        try {
            if (bytes == null || bytes.length > 1_048_576
                    || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedHash)) {
                throw invalid();
            }
            JsonNode value = JSON.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw invalid();
            }
            return value;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static String text(JsonNode value, String key) {
        if (!value.path(key).isTextual()) {
            throw invalid();
        }
        return value.path(key).textValue();
    }

    private static long integer(JsonNode value, String key) {
        if (!value.path(key).isIntegralNumber() || !value.path(key).canConvertToLong()) {
            throw invalid();
        }
        return value.path(key).longValue();
    }

    private static Instant instant(JsonNode value, String key) {
        return Instant.parse(text(value, key));
    }

    private static Instant time(java.sql.ResultSet rows, String column) throws SQLException {
        var value = rows.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static DomainException invalid() {
        return new DomainException(503, "RECOVERY_EVIDENCE_INVALID", "恢复证据的原文、来源绑定、时间或当前事件周期不一致，未采用摘要停钟");
    }
}
